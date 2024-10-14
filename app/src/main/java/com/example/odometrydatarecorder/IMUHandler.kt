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

    private val accelerometerBuffer: MutableList<SensorData> = mutableListOf()
    private var accCnt = 0
    private val gyroscopeBuffer: MutableList<SensorData> = mutableListOf()
    private var gyroCnt = 0

    private val handlerThread = HandlerThread("IMUHandlerThread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val logHandler = Handler()
    private val logRunnable = object : Runnable {
        override fun run() {
            val accCount = accelerometerBuffer.size
            val gyroCount = gyroscopeBuffer.size
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
                    accelerometerBuffer.add(SensorData(timestamp, x, y, z))
                    if (accelerometerBuffer.size > 10000) {
                        // copy the
                        val deepCopy = accelerometerBuffer.toMutableList()
                        val filename = "accelerometer_data_$accCnt"
                        handler.post { writeDataToFile(deepCopy,
                            filename) }
                        accCnt += 1
                        accelerometerBuffer.clear()
                    } else {

                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]
                    gyroscopeBuffer.add(SensorData(timestamp, x, y, z))
                    if (gyroscopeBuffer.size > 10000) {
                        val deepCopy = gyroscopeBuffer.toMutableList()
                        val filename = "gyroscope_data_$gyroCnt"
                        handler.post { writeDataToFile(deepCopy,
                            filename) }
                        gyroCnt += 1
                        gyroscopeBuffer.clear()
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