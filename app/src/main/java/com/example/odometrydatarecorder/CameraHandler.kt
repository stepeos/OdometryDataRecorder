package com.example.odometrydatarecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.core.content.ContextCompat

class CameraHandler(private val context: Context, private val textureView: TextureView) {

    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var cameraId: String
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var manualExposureTime: Long? = null

    // Open the camera
    fun openCamera() {
        startBackgroundThread()

        // Find the rear-facing camera ID
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (camera in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(camera)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = camera
                    break
                }
            }
            // Ensure texture is available before opening the camera
            if (textureView.isAvailable) {
                openCameraById(cameraId)
            } else {
                textureView.surfaceTextureListener = surfaceTextureListener
            }
        } catch (e: CameraAccessException) {
            Log.e("CameraHandler", "Camera access exception: ${e.message}")
            Toast.makeText(context, "Camera access failed", Toast.LENGTH_SHORT).show()
        }
    }

    // Open the camera by ID and configure capture request
    private fun openCameraById(cameraId: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
            } catch (e: CameraAccessException) {
                Log.e("CameraHandler", "Camera access exception: ${e.message}")
                Toast.makeText(context, "Camera access failed", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Camera permission is not granted", Toast.LENGTH_SHORT).show()
        }
    }

    // Configure capture request settings
    private fun configureCaptureRequest(builder: CaptureRequest.Builder) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        // Log SENSOR_INFO_SENSITIVITY_RANGE
        val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        sensitivityRange?.let {
            Log.d("CameraHandler", "SENSOR_INFO_SENSITIVITY_RANGE: $it")
        } ?: Log.d("CameraHandler", "SENSOR_INFO_SENSITIVITY_RANGE not available")

        // Log SENSOR_INFO_EXPOSURE_TIME_RANGE
        val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        exposureTimeRange?.let {
            Log.d("CameraHandler", "SENSOR_INFO_EXPOSURE_TIME_RANGE: $it")
        } ?: Log.d("CameraHandler", "SENSOR_INFO_EXPOSURE_TIME_RANGE not available")

        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)

        // Set manual exposure time if specified
        if (manualExposureTime != null) {
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureTime)
        }
        else
        {
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1000000L)
        }
        // set the ISO sensitivity to 1000
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, 1000)
    }

    // Set manual exposure time in Nanoseconds
    fun setManualExposure(exposureTime: Long) {
        manualExposureTime = exposureTime
        if (::cameraCaptureSession.isInitialized) {
            try {
                val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                builder.addTarget(Surface(textureView.surfaceTexture))
                configureCaptureRequest(builder)
                cameraCaptureSession.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } catch (e: CameraAccessException) {
                Log.e("CameraHandler", "Camera access exception: ${e.message}")
                Toast.makeText(context, "Failed to set manual exposure", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Close the camera
    fun closeCamera() {
        try {
            cameraCaptureSession.close()
            cameraDevice.close()
        } catch (e: CameraAccessException) {
            Log.e("CameraHandler", "Camera access exception: ${e.message}")
            Toast.makeText(context, "Camera access failed", Toast.LENGTH_SHORT).show()
        }
    }

    // TextureView listener to wait for texture availability before starting the camera preview
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            openCameraById(cameraId)
        }

        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean = false
        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
    }

    // State callback for camera device
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraDevice.close()
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            cameraDevice.close()
        }
    }

    // Create camera preview session
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture
            texture?.setDefaultBufferSize(textureView.width, textureView.height)
            val surface = Surface(texture)

            val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)
            configureCaptureRequest(captureRequestBuilder)

            cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
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

    // Start background thread
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    // Stop background thread
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("CameraHandler", "Background thread interruption: ${e.message}")
        }
    }
}
