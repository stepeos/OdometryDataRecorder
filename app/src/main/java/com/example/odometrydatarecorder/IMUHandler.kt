package com.example.odometrydatarecorder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileWriter

data class SensorData(val timestamp: Long, val x: Float, val y: Float, val z: Float)

class IMUHandler(private val context: Context) : SensorEventListener {

    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val accelerometerBuffer: MutableList<MutableList<SensorData>> = mutableListOf(mutableListOf(), mutableListOf())
    private val gyroscopeBuffer: MutableList<MutableList<SensorData>> = mutableListOf(mutableListOf(), mutableListOf())

    private var currentAccelerometerIndex = 0
    private var currentGyroscopeIndex = 0

    private val handlerThread = HandlerThread("IMUHandlerThread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val logHandler = Handler()
    private val logRunnable = object : Runnable {
        override fun run() {
            val accCount = accelerometerBuffer.sumOf { it.size }
            val gyroCount = gyroscopeBuffer.sumOf { it.size }
            Log.i("IMUHandler", "Accelerometer buffer count: $accCount")
            Log.i("IMUHandler", "Gyroscope buffer count: $gyroCount")
            logHandler.postDelayed(this, 1000)
        }
    }

    // Start listening to IMU sensors
    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        logHandler.post(logRunnable)
    }

    // Stop listening to IMU sensors
    fun stop() {
        sensorManager.unregisterListener(this)
        handlerThread.quitSafely()
        logHandler.removeCallbacks(logRunnable)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val timestamp = it.timestamp
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]
                    val currentBuffer = accelerometerBuffer[currentAccelerometerIndex]
                    currentBuffer.add(SensorData(timestamp, x, y, z))
                    if (currentBuffer.size > 100000) {
                        handler.post { writeDataToFile(currentBuffer, "accelerometer_data") }
                        currentAccelerometerIndex = (currentAccelerometerIndex + 1) % 2
                        accelerometerBuffer[currentAccelerometerIndex].clear()
                    } else {

                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]
                    val currentBuffer = gyroscopeBuffer[currentGyroscopeIndex]
                    currentBuffer.add(SensorData(timestamp, x, y, z))
                    if (currentBuffer.size > 100000) {
                        handler.post { writeDataToFile(currentBuffer, "gyroscope_data") }
                        currentGyroscopeIndex = (currentGyroscopeIndex + 1) % 2
                        gyroscopeBuffer[currentGyroscopeIndex].clear()
                    } else {

                    }
                }
                else -> {
                    Log.d("IMUHandler", "Unhandled sensor type: ${it.sensor.type}")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle sensor accuracy changes if needed
    }

    private fun writeDataToFile(data: MutableList<SensorData>, fileName: String) {
        val tempFile = File(context.cacheDir, "$fileName.txt")
        FileWriter(tempFile, true).use { writer ->
            data.forEach { sensorData ->
                writer.write("${sensorData.timestamp},${sensorData.x},${sensorData.y},${sensorData.z}\n")
            }
        }
        data.clear()
        Log.d("IMUHandler", "Data written to file: ${tempFile.absolutePath}")
    }
}