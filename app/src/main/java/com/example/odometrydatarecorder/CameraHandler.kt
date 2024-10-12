package com.example.odometrydatarecorder

import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast

class CameraHandler(
    private val context: Context,
    private val textureView: TextureView
) {

    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var surface: Surface

    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraId: String = "" // Will hold the ID of the rear camera

    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    // Open the camera
    fun openCamera() {
        startBackgroundThread()

        // Find the rear-facing camera ID
        try {
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

    // Close the camera
    fun closeCamera() {
        try {
            cameraCaptureSession.close()
            cameraDevice.close()
        } catch (e: Exception) {
            Log.e("CameraHandler", "Error closing camera: ${e.message}")
        } finally {
            stopBackgroundThread()
        }
    }

    // Helper method to open the camera by its ID
    private fun openCameraById(cameraId: String) {
        try {
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("CameraHandler", "Cannot open camera: ${e.message}")
        } catch (e: SecurityException) {
            Log.e("CameraHandler", "Security exception: ${e.message}")
        }
    }

    // Background thread to handle camera operations
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackgroundThread").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

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

    // TextureView listener to wait for texture availability before starting the camera preview
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
            openCameraById(cameraId)
        }

        override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean = false
        override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {}
    }

    // Camera state callback to handle camera events
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startCameraPreview()  // Once camera is opened, start the preview
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e("CameraHandler", "Camera device error: $error")
            cameraDevice.close()
        }
    }

    // Start the camera preview
    fun startCameraPreview() {
        try {
            val texture = textureView.surfaceTexture
            texture?.setDefaultBufferSize(textureView.width, textureView.height)
            surface = Surface(texture)

            // Create the CaptureRequest for camera preview
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }

            // Create the CameraCaptureSession
            cameraDevice.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        val captureRequest = captureRequestBuilder.build()
                        cameraCaptureSession.setRepeatingRequest(captureRequest, null, backgroundHandler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CameraHandler", "Failed to configure camera session")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e("CameraHandler", "Error starting camera preview: ${e.message}")
        }
    }
}
