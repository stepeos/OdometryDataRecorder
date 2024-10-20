package com.example.odometrydatarecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.odometrydatarecorder.ui.theme.OdometryDataRecorderTheme

class MainActivity : ComponentActivity() {
    private lateinit var cameraHandler: CameraHandler
    private lateinit var imuHandler: IMUHandler
    private lateinit var textureView: TextureView

    // Register for Activity Result to handle permission request
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Camera permission granted
            Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
        } else {
            // Permission denied
            Toast.makeText(this, "Camera permission is required to use the camera", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val runtime = Runtime.getRuntime()
        val mem = runtime.freeMemory() / 1024 / 1024
        Log.i("MainActivity", "Starting with $mem free RAM")
        super.onCreate(savedInstanceState)
        setupIMU()
        val mem1 = runtime.freeMemory() / 1024 / 1024
        Log.i("MainActivity", "Starting with $mem1 free RAM")
        setupCamera()

        setContent {
            MainScreen()
        }
    }

    private fun setupCamera() {
        // Initialize the TextureView and CameraHandler
        textureView = TextureView(this)
        cameraHandler = CameraHandler(this, textureView)
    }

    private fun setupIMU() {
        // Initialize the IMU Handler (has no visuals)
        imuHandler = IMUHandler(this)
    }

    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        var hasCameraPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            )
        }

        LaunchedEffect(hasCameraPermission) {
            if (!hasCameraPermission) {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            CameraScreen(
                context = context,
                modifier = Modifier.padding(innerPadding),
                cameraHandler = cameraHandler,
                imuHandler = imuHandler
            )
        }
    }

    @Composable
    fun CameraScreen(
        context: Context,
        modifier: Modifier = Modifier,
        cameraHandler: CameraHandler,
        imuHandler: IMUHandler
    ) {
        var showTextField by remember { mutableStateOf(false) }
        var isCameraStarted by remember { mutableStateOf(false) }
        var exposureTime by remember { mutableStateOf("") }
        var isoSensitivity by remember { mutableStateOf("") }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // TextureView for camera preview
            AndroidView(
                factory = { textureView },
                modifier = Modifier
//                    .fillMaxWidth()
                    .fillMaxWidth()
                    .height(500.dp) // Adjust height as needed
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Button to start/stop camera preview
            Button(onClick = {
                if (isCameraStarted) {
                    cameraHandler.closeCamera()
                    imuHandler.stop()
                    isCameraStarted = false
                    showTextField = false
                    Toast.makeText(context, "Camera stopped", Toast.LENGTH_SHORT).show()
                } else {
                    cameraHandler.openCamera()
                    // imuHandler.start()
                    isCameraStarted = true
                    showTextField = true
                    Toast.makeText(context, "Camera started", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text(if (isCameraStarted) "Stop Camera" else "Start Camera")
            }

            if (showTextField) {
                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = exposureTime,
                    onValueChange = { exposureTime = it },
                    label = { Text("Exposure Time (ms)") }
                )

                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = isoSensitivity,
                    onValueChange = { isoSensitivity = it },
                    label = { Text("ISO Sensitivity") }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(onClick = {
                    val exposureTimeMs = exposureTime.toLongOrNull()
                    if (exposureTimeMs != null) {
                        Log.i("MainActivity", "Got exposure time $exposureTimeMs")
                        val clippedExposureTimeMs = exposureTimeMs.coerceIn(1, 500)
                        cameraHandler.setManualExposure(clippedExposureTimeMs * 1_000_000) // Convert ms to ns
                    } else {
                        Toast.makeText(context, "Invalid exposure time", Toast.LENGTH_SHORT).show()
                    }
                    val isoSens = isoSensitivity.toIntOrNull()
                    if (isoSens != null) {
                        Log.i("MainActivity", "Got iso sensitivity $isoSens")
                        cameraHandler.setIsoSensitivity(isoSens)
                    } else {
                        Toast.makeText(context, "Invalid iso sensitivity", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Set Exposure & ISO Sen.")
                }
            }
        }
    }
}
