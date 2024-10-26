package com.example.odometrydatarecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    private lateinit var cameraHandler: CameraHandler
    private lateinit var imuHandler: IMUHandler
    private lateinit var textureView: TextureView
    private var currentRecordingUUID = ""

    private val writerThread = HandlerThread("ZipThread").apply { start() }
    private val wHandler = Handler(writerThread.looper)

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
        this.cacheDir.listFiles()

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
                    wHandler.post {
                        cameraHandler.stopRecording()
                        imuHandler.stop()
                        zipBinFiles()
                        shareFile(File(filesDir, "recording_$currentRecordingUUID.zip"))
                    }
                    isCameraStarted = false
                    showTextField = false
                    Toast.makeText(context, "Camera stopped", Toast.LENGTH_SHORT).show()
                } else {
                    // generate uuid string
                    currentRecordingUUID = java.util.UUID.randomUUID().toString()
                    cameraHandler.startRecording(currentRecordingUUID)
                    imuHandler.start(currentRecordingUUID)
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

    // function that compress the recording directory to a zip file
    private fun zipBinFiles(): File? {
        val recordingDir = File(cacheDir, currentRecordingUUID)
        val zipFile = File(filesDir, "recording_$currentRecordingUUID.zip")
        // TODO: check free space!
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                recordingDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".bin")) {
                        // print file size
                        val fileSize = file.length() / (1024 * 1024)
                        Log.i("CameraHandlerCapnp", "Adding file to zip: ${file.name} of size ${fileSize}MB")
                        FileInputStream(file).use { fis ->
                            BufferedInputStream(fis).use { bis ->
                                val zipEntry = ZipEntry(file.name)
                                zos.putNextEntry(zipEntry)
                                bis.copyTo(zos, 1024)
                                zos.closeEntry()
                            }
                        }
                    }
                }
            }
            Log.i("CameraHandlerCapnp", "Created zip file: ${zipFile.absolutePath}")
            recordingDir.deleteRecursively() // remove old files
            return zipFile
        } catch (e: IOException) {
            Log.e("CameraHandlerCapnp", "Error creating zip file: ${e.message}")
            return null
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${this.packageName}.provider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        this.startActivity(Intent.createChooser(shareIntent, "Share file using"))
    }

}
