package com.example.odometrydatarecorder

import com.example.odometrydatarecorder.capnp_compiled.OdometryData.CameraData
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.ar.core.ImageFormat
import org.capnproto.MessageBuilder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


// class to store meta info about the image exposure, iso sensitivity
class ImgMeta {
    var iso: Short = 0
    var exposureTime: Float = 0.0F
}

class CameraHandler(private val context: Context, private val textureView: TextureView) {
    private var recordingUUID = ""
    private var binDir = File(context.cacheDir, recordingUUID)
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private lateinit var cameraCharacteristics: CameraCharacteristics
    private var manualExposureTime: Long = 15L
    private var manualSensitivity: Int = 1000

    // a thread that flushes the data to file in capnp format
    private val writerThread = HandlerThread("CameraSerializerThread").apply { start() }
    private val wHandler = Handler(writerThread.looper)

    // capnproto stuff
    private val builder1 = MessageBuilder()
    private val builder2 = MessageBuilder()
    private var activeBuilder = builder1
    private var inactiveBuilder = builder2
    private val cameraData1 = builder1.initRoot(CameraData.factory)
    private val cameraData2 = builder2.initRoot(CameraData.factory)
    private var activeCameraData = cameraData1
    private var inactiveCameraData = cameraData2
    private var imageCounter = 0
    private var chunkCounter = 0
    private val imageChunkSize = 10 // the chunk size when the image buffer is flushed to disk
    // a mutable map with Long as key and array as value
    private val metaBuffer1 = mutableMapOf<Long, ImgMeta>()
    private val metaBuffer2 = mutableMapOf<Long, ImgMeta>()
    private var activeMetaBuffer = metaBuffer1
    private var inactiveMetaBuffer = metaBuffer2

    private var imgFormat = ImageFormat.YUV_420_888
    // private var imgFormat = PixelFormat.RGB_888

    // Initialize ImageReader variable
    private lateinit var imageReader: ImageReader

    fun startRecording(uuid: String) {
        recordingUUID = uuid

        binDir = File(context.cacheDir, uuid)
        if (binDir.exists()) {
            binDir.deleteRecursively()
        }
        binDir.mkdir()

        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var cameraId: String? = null
        val runtime = Runtime.getRuntime()
        var mem = runtime.freeMemory() / 1024 / 1024
        Log.i("CameraHandler", "Starting with $mem free RAM A")

        for (camera in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(camera)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = camera
                cameraCharacteristics = characteristics
                // check here for REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

                // if (capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true) {
                //     // Manual sensor control is supported
                //     Log.i("CameraHandler", "Manual sensor control is supported")
                // }
                // else {
                //     Log.i("CameraHandler", "Manual sensor control is not supported")
                // }
                 if (capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true) {
                     Log.i("CameraHandler", "RAW IS SUPPORTED LETSGOO")
                 }
                 else {
                     Log.i("CameraHandler", "RAW IS NOT SUPPORTED")
                 }
                break
            }
        }

        for (camera in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(camera)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = camera
                cameraCharacteristics = characteristics
                Log.i("CameraHandler", "$cameraId resolutions for camera $cameraId")
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val outputSizes = map?.getOutputSizes(imgFormat)
                outputSizes?.forEach { size ->
                    Log.i("CameraHandler", "$cameraId resolution: ${size.width}x${size.height}")
                }

            }
        }



        mem = runtime.freeMemory() / 1024 / 1024
        Log.i("CameraHandler", "Starting with $mem free RAM B")
        openCameraById(cameraId ?: "")
        // val tempFile = File(context.cacheDir, "$fileName.bin")
        // lets delete all files we find in cacheDir that end in bin
        var files = context.cacheDir.listFiles { _, name -> name.endsWith(".bin") }
        files?.forEach { it.delete() }
        files = context.filesDir.listFiles { _, name -> name.endsWith(".zip") }
        files?.forEach { it.delete() }
        mem = runtime.freeMemory() / 1024 / 1024
        Log.i("CameraHandler", "Starting with $mem free RAM C")
//        val maxHeapSize = runtime.maxMemory() / 1024 / 1024
//        Log.i("CameraHandler", "Max Heap Size: $maxHeapSize MB")
        // Initialize the Cap'n Proto message with size 100
        activeCameraData.initEntries(imageChunkSize)
        inactiveCameraData.initEntries(imageChunkSize)
        imageCounter = 0
        chunkCounter = 0

    }

    // Open the camera by ID and configure capture request
    private fun openCameraById(cameraId: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
            } catch (e: CameraAccessException) {
                Log.e("CameraHandler", "Camera access exception: ${e.message}")
                Toast.makeText(context, "Camera access failed", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Camera permission is not granted", Toast.LENGTH_SHORT).show()
        }
    }

    // State callback for camera device
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice.close()
        }
    }

    fun stopRecording() {
        Log.i("CameraHandler", "Close camera called")
        cameraCaptureSession.close()
        cameraDevice.close()
        stopBackgroundThread()
        imageReader.close()
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture

            texture?.setDefaultBufferSize(textureView.width, textureView.height)
            val surface = Surface(texture)
            // Initialize ImageReader with the actual width and height
            val characteristics = cameraManager.getCameraCharacteristics(cameraDevice.id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(imgFormat)
            // Select if available, otherwise select the largest available resolution
            val reqWidth = 1920
            val reqHeight = 1080
            val frameRate = 20
            val selectedSize = outputSizes?.find { it.width == reqWidth && it.height == reqHeight }
                ?: outputSizes?.maxByOrNull { it.width * it.height }
            val runtime = Runtime.getRuntime()
            val mem = runtime.freeMemory() / 1024 / 1024
            Log.i("CameraHandler", "Starting with $mem MB free RAM")
            val recordingFile = File(context.cacheDir, "recording.mp4")
            selectedSize?.let {
                initializeImageReader(it.width, it.height)
            }


            // Create CaptureRequest for preview
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface) // Add TextureView surface for preview
            imageReader.surface?.let { captureRequestBuilder.addTarget(it) } // Add ImageReader surface for image capture

            cameraDevice.createCaptureSession(listOf(surface, imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

                        // also the rest https://stackoverflow.com/questions/33151244/implement-tap-to-focus-in-camera2-api
                        // set fps
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(frameRate, frameRate))

                        // Set to manual exposure/ISO
                        // captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        // captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureTime)
                        // captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, manualSensitivity)
                        // captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), createCaptureCallback(), backgroundHandler)
                    }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(context, "Camera configuration failed", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)


        } catch (e: CameraAccessException) {
            Log.e("CameraHandler", "Camera access exception: ${e.message}")
            Toast.makeText(context, "Camera access failed", Toast.LENGTH_SHORT).show()
        }
    }

    // Method that creates the CaptureCallback
    private fun createCaptureCallback(): CameraCaptureSession.CaptureCallback {
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                // Call the custom method to handle the metadata
                processCaptureMetadata(result)
            }

            override fun onCaptureProgressed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                partialResult: CaptureResult
            ) {
                // You can handle partial results here if necessary
            }
        }
    }



    fun setManualExposure(exposureTime: Long) {
        manualExposureTime = exposureTime
        if (::cameraCaptureSession.isInitialized) {
            try {
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
            } catch (e: CameraAccessException) {
                Log.e("CameraHandler", "Camera access exception: ${e.message}")
                Toast.makeText(context, "Failed to set manual exposure", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun setIsoSensitivity(sensitivity: Int) {
        manualSensitivity = sensitivity
        if (::cameraCaptureSession.isInitialized) {
            try {
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                val sensitivityRange = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val minISO = sensitivityRange?.lower ?: 100 // Fallback to 100 if null
                val maxISO = sensitivityRange?.upper ?: 3200 // Fallback to 3200 if null
                Log.d("CameraHandler", "Iso Range should is [$minISO, $maxISO]")

                if (manualSensitivity in minISO..maxISO) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
                    cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                } else {
                    Log.e("Camera2", "ISO value $manualSensitivity is out of range: $minISO to $maxISO")
                }

            } catch (e: CameraAccessException) {
                Log.e("CameraHandler", "Camera access exception: ${e.message}")
                Toast.makeText(context, "Failed to set ISO sensitivity", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("CameraHandler", "Error stopping background thread: ${e.message}")
        }
    }

    /**
     * #######################################################################
     * ##########         Capnproto message stuff here          ##############
     * #######################################################################
     */


    // Function to switch builders
    private fun switchBuilders() {
        val tempBuilder = activeBuilder
        activeBuilder = inactiveBuilder
        inactiveBuilder = tempBuilder

        val tempCameraData = activeCameraData
        activeCameraData = inactiveCameraData
        inactiveCameraData = tempCameraData


        // switch activeMetaBuffer
        val tempMetaBuffer = activeMetaBuffer
        activeMetaBuffer = inactiveMetaBuffer
        inactiveMetaBuffer = tempMetaBuffer
    }


    // New function to handle image frame availability
    private fun onImageFrameAvailable(image: Image) {
        if (imageCounter == 0 && chunkCounter == 0) {
            // read SENSOR_INFO_TIMESTAMP_SOURCE and log it to console
            // https://developer.android.com/reference/android/hardware/camera2/CameraMetadata#SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            // if this does not evaluate to 1, then the clock base is not the same as the clockbase of the IMU -> BAD
            val timestampSource = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            Log.d("CameraHandler", "Timestamp source: $timestampSource")
        }
        // do it at the beginning so that the write to file is certainly available
        if (imageCounter >= imageChunkSize) {
            val filename = "camera_data_${chunkCounter}"
            switchBuilders()
            imageCounter = 0
            chunkCounter += 1
            wHandler.post { writeDataToFile(filename) }
        }
        // Capture image to ByteArray
        val timeStamp: Long = image.timestamp
        // get the height and width
        val width = image.width
        val height = image.height
        val entry = activeCameraData.entries[imageCounter]

        entry.initCaptureData(3)
        for ((index, value) in image.planes.withIndex()) {
            val planeBuf = image.planes[index].buffer
            val bytes = ByteArray(planeBuf.remaining())
            planeBuf.get(bytes)
            entry.captureData[index].setCapture(bytes)
            entry.captureData[index].pixelStride = value.pixelStride.toShort()
            entry.captureData[index].rowStride = value.rowStride.toShort()
        }
        entry.timestamp = timeStamp
        entry.setPixelFormat("YUV_420_888")
        imageCounter += 1
        Log.d("CameraHandler", "#${imageCounter} Captured image data size: {$width x $height}}, timestamp: $timeStamp")
        image.close()
    }

    // Method to process and log the metadata from TotalCaptureResult
    fun processCaptureMetadata(result: TotalCaptureResult) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L  // Get frame timestamp
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0  // Get ISO level
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L  // Get exposure time
        val nExposureTime = exposureTime * 1e-9 // in nanoseconds
        val metadata = ImgMeta()
        metadata.iso = iso.toShort()
        metadata.exposureTime = nExposureTime.toFloat()
        activeMetaBuffer.set(timestamp, metadata)
    }


    // Method to initialize ImageReader
    private fun initializeImageReader(width: Int, height: Int) {
        imageReader = ImageReader.newInstance(width, height, imgFormat, 10).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() // apparently faster than acquire next image
                image?.let {
                    onImageFrameAvailable(it) // Pass the image for processing
                }
            }, backgroundHandler)
        }
    }

    private fun writeDataToFile(fileName: String) {
        Log.i("CameraHandlerCapnp", "Writing capnproto File!")
        val tempFile = File(binDir, "$fileName.bin")
        for (entry in inactiveCameraData.entries) {
            val timestamp = entry.timestamp
            entry.isoSensitivity = inactiveMetaBuffer[timestamp]?.iso ?: 0
            entry.exposureTime = inactiveMetaBuffer[timestamp]?.exposureTime ?: 0.0f
        }

        // Write Cap'n Proto message to file
        FileOutputStream(tempFile).use { fos ->
            try {
                org.capnproto.Serialize.write(fos.channel, inactiveBuilder)
            } catch (e: Exception) {
                Log.e("CameraHandlerCapnp", "Error writing Cap'n Proto data to file: ${e.message}")
            }
        }
        // reset the builder to free memory
        inactiveBuilder = MessageBuilder()
        inactiveCameraData = inactiveBuilder.initRoot(CameraData.factory)
        inactiveCameraData.initEntries(imageChunkSize)
        // reset the inactive builder
        inactiveMetaBuffer.clear()

        System.gc() // hope for garbage collector to do its thing

        // Log the size of the written file
        val fileSize = tempFile.length() / (1024 * 1024)
        Log.d("CameraHandlerCapnp", "Data written to file: ${tempFile.absolutePath} of size ${fileSize}MB")
    }

}
 