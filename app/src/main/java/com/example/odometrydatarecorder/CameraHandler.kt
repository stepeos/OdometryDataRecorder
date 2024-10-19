package com.example.odometrydatarecorder

import com.example.odometrydatarecorder.capnp_compiled.OdometryData.CameraData
import android.Manifest
import android.app.ActivityManager
import android.content.Context
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
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.odometrydatarecorder.capnp_compiled.OdometryData.CameraCapture
import com.google.ar.core.Camera
import com.google.ar.core.ImageFormat
import org.capnproto.MessageBuilder
import org.capnproto.StructList
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max


data class CapturedImageData(val timestamp: Long, val imageData: ByteArray)


class CameraHandler(private val context: Context, private val textureView: TextureView) {
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


    // Initialize ImageReader variable
    private lateinit var imageReader: ImageReader
    

    fun openCamera() {
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
                // log all autofocus modes that are avaiable
                val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                // Log.i("CameraHandler", "Available AF modes: ${afModes?.joinToString()}")

                // if (capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true) {
                //     // Manual sensor control is supported
                //     Log.i("CameraHandler", "Manual sensor control is supported")
                // }
                // else {
                //     Log.i("CameraHandler", "Manual sensor control is not supported")
                // }
                // if (capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true) {
                //     Log.i("CameraHandler", "RAW IS SUPPORTED LETSGOO")
                // }
                // else {
                //     Log.i("CameraHandler", "RAW IS NOT SUPPORTED")
                // }


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
                val outputSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
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
        val files = context.cacheDir.listFiles { _, name -> name.endsWith(".bin") }
        files?.forEach { it.delete() }
        mem = runtime.freeMemory() / 1024 / 1024
        Log.i("CameraHandler", "Starting with $mem free RAM C")
//        val maxHeapSize = runtime.maxMemory() / 1024 / 1024
//        Log.i("CameraHandler", "Max Heap Size: $maxHeapSize MB")
        // Initialize the Cap'n Proto message with size 100
        activeCameraData.initEntries(imageChunkSize)
        inactiveCameraData.initEntries(imageChunkSize)
        imageCounter = 0

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

    fun closeCamera() {
        cameraCaptureSession.close()
        cameraDevice.close()
        stopBackgroundThread()
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture
            val runtime = Runtime.getRuntime()
            var mem = runtime.freeMemory() / 1024 / 1024
            Log.i("CameraHandler", "Starting with $mem free RAM AA")

            texture?.setDefaultBufferSize(textureView.width, textureView.height)
            val surface = Surface(texture)
            mem = runtime.freeMemory() / 1024 / 1024
            Log.i("CameraHandler", "Starting with $mem free RAM BB")
            // Initialize ImageReader with the actual width and height
            val characteristics = cameraManager.getCameraCharacteristics(cameraDevice.id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            // Select 1920x1080 if available, otherwise select the largest available resolution
            val selectedSize = outputSizes?.find { it.width == 1920 && it.height == 1080 }
                ?: outputSizes?.maxByOrNull { it.width * it.height }
            mem = runtime.freeMemory() / 1024 / 1024
            Log.i("CameraHandler", "Starting with $mem free RAM CC")

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
                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)

                        // set fps to 15
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(10, 10))

                        // Set to autofocus to CameraMetadata.CONTROL_AF_MODE_AUTO
                        // captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)

                        // Set to manual exposure/ISO
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureTime)
                        captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, manualSensitivity)


                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
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

    private fun setAutoFocus() {
        try {

            val sensorArraySize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val centerX = sensorArraySize!!.width() / 2
            val centerY = sensorArraySize.height() / 2
            val halfFocusAreaSize = 100
            val focusArea = MeteringRectangle(
                max(centerX - halfFocusAreaSize, 0),
                max(centerY - halfFocusAreaSize, 0),
                halfFocusAreaSize * 2,
                halfFocusAreaSize * 2,
                MeteringRectangle.METERING_WEIGHT_MAX - 1
            )
            // Set autofocus settings
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusArea))
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("CameraHandler", "Failed to set auto focus: ${e.message}")
        }
    }

    fun setManualExposure(exposureTime: Long) {
        manualExposureTime = exposureTime
        if (::cameraCaptureSession.isInitialized) {
            try {
                captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
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
                captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
            } catch (e: CameraAccessException) {
                Log.e("CameraHandler", "Camera access exception: ${e.message}")
                Toast.makeText(context, "Failed to set ISO sensitivity", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
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
    }


    // New function to handle image frame availability
    private fun onImageFrameAvailable1(image: Image) {
        // Capture image to ByteArray
        val timeStamp: Long = image.timestamp
        // get the height and width
        val width = image.width
        val height = image.height
        val buffer = image.planes.first().buffer
        buffer?.let {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val entry = activeCameraData.entries[imageCounter]
            entry.timestamp = timeStamp
            entry.setCapture(bytes)
            imageCounter += 1
            if (imageCounter >= imageChunkSize) {
                val filename = "camera_data_${chunkCounter}"
                switchBuilders()
                imageCounter = 0
                chunkCounter += 1
                wHandler.post { writeDataToFile(filename) }
            }
            // Optionally, you can log the size of the captured image data
            Log.d("CameraHandler", "#${imageCounter} Captured image data size: {$width x $height}}, timestamp: $timeStamp")
//            val runtime = Runtime.getRuntime()
//            val mem = runtime.freeMemory() / 1024 / 1024
            // Log.i("CameraHandler", "captured with $mem free RAM")
        }
        image.close()
    }


    private fun onImageFrameAvailable2(image: Image) {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // Get the timestamp for the filename
        val timestamp = System.currentTimeMillis()
        val file = File(context.cacheDir, "IMG_$timestamp.jpg")

        try {
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }
            Log.d("CameraCaptureActivity", "Image saved: ${file.absolutePath}")
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // Method to initialize ImageReader
    private fun initializeImageReader(width: Int, height: Int) {
        imageReader = ImageReader.newInstance(width, height, android.graphics.ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() // Acquire the next available image
                image?.let {
                    onImageFrameAvailable1(it) // Pass the image for processing
                    // onImageFrameAvailable2(it)
                }
            }, backgroundHandler)
        }
    }

    private fun writeDataToFile(fileName: String) {
        Log.i("CameraHandlerCapnp", "Writing capnproto File!")
        val tempFile = File(context.cacheDir, "$fileName.bin")


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
        System.gc() // hope for garbage collector to do its thing

        // Log the size of the written file
        val fileSize = tempFile.length() / (1024 * 1024)
        Log.d("CameraHandlerCapnp", "Data written to file: ${tempFile.absolutePath} of size ${fileSize}MB")
    }

}
 