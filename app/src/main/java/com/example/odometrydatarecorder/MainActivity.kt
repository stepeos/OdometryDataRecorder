package com.example.odometrydatarecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.odometrydatarecorder.ui.theme.OdometryDataRecorderTheme
import android.view.TextureView
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private lateinit var cameraHandler: CameraHandler
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
        super.onCreate(savedInstanceState)

        setContent {
            OdometryDataRecorderTheme {
                MainScreen()
            }
        }
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

            setupCamera()

        }
        // cameraHandler.openCamera()

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            CameraPreviewWithButton(modifier = Modifier.padding(innerPadding))
        }
    }

    private fun setupCamera() {
        // Initialize the TextureView and CameraHandler
        textureView = TextureView(this)
        cameraHandler = CameraHandler(this, textureView)
    }

    @Composable
    fun CameraPreviewWithButton(modifier: Modifier = Modifier) {
        // Initialize textureView within the Composable function
        val context = LocalContext.current
        val textureView = remember { TextureView(context) }
        val cameraHandler = remember { CameraHandler(context, textureView) }

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
                    .fillMaxWidth()
                    .height(400.dp) // Adjust height as needed
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Button to start camera preview
            Button(onClick = {
                // Your button click logic here
                cameraHandler.openCamera()
            }) {
                Text("Start Camera")
            }
        }
    }
}
