package com.example.odometrydatarecorder

import com.example.odometrydatarecorder.capnp_compiled.OdometryData.CameraData
import android.Manifest
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
import com.google.ar.core.ImageFormat
import org.capnproto.MessageBuilder
import org.capnproto.StructList
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter


data class CapturedImageData(val timestamp: Long, val imageData: ByteArray)


class CameraHandler(private val context: Context, private val textureView: TextureView) {
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private val capturedImages: MutableList<CameraCapture.Builder> = mutableListOf()
    private lateinit var cameraCharacteristics: CameraCharacteristics
    private var manualExposureTime: Long = 0L
    private var manualSensitivity: Int = 0

    // a thread that flushes the data to file in capnp format
    private val writerThread = HandlerThread("CameraSerializerThread").apply { start() }
    private val wHandler = Handler(writerThread.looper)


    // Local variable to store the image data
    private var imageData: ByteArray? = null

    // Initialize ImageReader variable
    private lateinit var imageReader: ImageReader

    fun openCamera() {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var cameraId: String? = null

        for (camera in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(camera)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = camera
                cameraCharacteristics = characteristics
                break
            }
        }

        openCameraById(cameraId ?: "")
//        cameraCharacteristics.
        // initialize capnproto message
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
            texture?.setDefaultBufferSize(textureView.width, textureView.height)
            val surface = Surface(texture)

            // Initialize ImageReader with the actual width and height
            val characteristics = cameraManager.getCameraCharacteristics(cameraDevice.id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val largest = map?.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull { it.width * it.height }

            largest?.let {
                initializeImageReader(it.width, it.height)
            }

            // Create CaptureRequest for preview
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface) // Add TextureView surface for preview
            imageReader?.surface?.let { captureRequestBuilder.addTarget(it) } // Add ImageReader surface for image capture
            
            cameraDevice.createCaptureSession(listOf(surface, imageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), combinedCaptureCallback, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(context, "Camera configuration failed", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)

            // Set up touch listener for autofocus
            textureView.setOnTouchListener { _, _ ->
                setAutoFocus()
                true
            }
        } catch (e: CameraAccessException) {
            Log.e("CameraHandler", "Camera access exception: ${e.message}")
            Toast.makeText(context, "Camera access failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureCaptureRequest(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureTime)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualSensitivity)
    }

    private fun setAutoFocus() {
        try {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), combinedCaptureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("CameraHandler", "Failed to set auto focus: ${e.message}")
        }
    }

    fun setManualExposure(exposureTime: Long) {
        manualExposureTime = exposureTime
        if (::cameraCaptureSession.isInitialized) {
            try {
                captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), combinedCaptureCallback, backgroundHandler)
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
                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), combinedCaptureCallback, backgroundHandler)
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

    // New function to handle image frame availability
    private fun onImageFrameAvailable(image: Image) {
        // Capture image to ByteArray
        val timeStamp: Long = image.timestamp
        // get the height and width
        val width = image.width
        val height = image.height
        val buffer = image.planes.first().buffer
        buffer?.let {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            imageData = bytes

            // initialize new message
            val builder  = MessageBuilder();
            val msg = builder.initRoot(CameraCapture.factory)
            msg.timestamp = timeStamp
            msg.setCapture(bytes)
            //val capturedImage = CapturedImageData(timeStamp, bytes)
            capturedImages.add(msg)
            if (capturedImages.size > 100 || checkMemory(100)) {
                // copy the
                val deepCopy = capturedImages.toMutableList()
                val filename = "camera_data_${capturedImages.size}"
                wHandler.post { writeDataToFile(deepCopy, filename) }
            }
            // Optionally, you can log the size of the captured image data
            Log.d("CameraHandler", "#${capturedImages.size} Captured image data size: ${imageData?.size} = {$width x $height}}, timestamp: $timeStamp")
        }
        image.close()
    }

    // check if there is less memory the threshold in MB
    private fun checkMemory(threshold: Long): Boolean {
        val runtime = Runtime.getRuntime()
        val freeMemoryInBytes = runtime.freeMemory()
        val freeMemoryInMB = freeMemoryInBytes / (1024 * 1024)
        return freeMemoryInMB < threshold
    }

    // CaptureCallback to handle new image frames
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
            // Acquire the latest image from the ImageReader
            val image = imageReader.acquireLatestImage()
            image?.let {
                onImageFrameAvailable(it)
            }
        }
    }

    // Combined CaptureCallback to handle both preview and custom callback
    private val combinedCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
            captureCallback.onCaptureCompleted(session, request, result)
        }

        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            super.onCaptureProgressed(session, request, partialResult)
            captureCallback.onCaptureProgressed(session, request, partialResult)
        }
    }

    // Method to initialize ImageReader
    private fun initializeImageReader(width: Int, height: Int) {
        imageReader = ImageReader.newInstance(width, height, android.graphics.ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() // Acquire the next available image
                image?.let {
                    onImageFrameAvailable(it) // Pass the image for processing
                }
            }, backgroundHandler)
        }
    }

    // Method to write the captured image data to a file in capnp format
    private fun writeDataToFile(data: MutableList<CameraCapture.Builder>, fileName: String) {
        Log.i("CameraHandler", "Writing capnproto File!")
        val tempFile = File(context.cacheDir, "$fileName.bin")

        // Initialize the Cap'n Proto message
        val builder  = MessageBuilder();
        val cameraData = builder.initRoot(CameraData.factory)

        // Set the number of entries
        cameraData?.initEntries(data.size)

        // Set the data into the message entries
        for ((index, dataEntry) in data.withIndex()) {
            val entry = cameraData?.entries?.get(index)
            entry?.setCapture(dataEntry.asReader().capture)
            entry?.timestamp = dataEntry.asReader().timestamp
        }

        // Write the Cap'n Proto message to a file
        FileOutputStream(tempFile).use { fos ->
            try {
                // Serialize the message
                org.capnproto.Serialize.write(fos.channel, builder)
            } catch (e: Exception) {
                Log.e("CameraHandler", "Error writing Cap'n Proto data to file: ${e.message}")
            }
        }

        // Get the file size in MB
        val fileSize = tempFile.length() / (1024 * 1024)

        // Log the file path and size
        Log.d("CameraHandler", "Data written to file: ${tempFile.absolutePath} of size ${fileSize}MB")
    }

}
