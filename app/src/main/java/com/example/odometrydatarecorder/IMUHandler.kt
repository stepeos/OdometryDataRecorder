package com.example.odometrydatarecorder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.odometrydatarecorder.capnp_compiled.OdometryData.IMUData
import org.capnproto.MessageBuilder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


class IMUHandler(private val context: Context) : SensorEventListener {

    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // capnproto stuff
    // acc
    private val builderAcc1 = MessageBuilder()
    private val builderAcc2 = MessageBuilder()
    private var activeAccBuilder = builderAcc1
    private var inactiveAccBuilder = builderAcc2
    private val accData1 = builderAcc1.initRoot(IMUData.factory)
    private val accData2 = builderAcc2.initRoot(IMUData.factory)
    private var activeAccData = accData1
    private var inactiveAccData = accData2
    // gryo
    private val builderGyr1 = MessageBuilder()
    private val builderGyr2 = MessageBuilder()
    private var activeGyrBuilder = builderGyr1
    private var inactiveGyrBuilder = builderGyr2
    private val gyrData1 = builderGyr1.initRoot(IMUData.factory)
    private val gyrData2 = builderGyr2.initRoot(IMUData.factory)
    private var activeGyrData = gyrData1
    private var inactiveGyrData = gyrData2


    private var accEntryCounter = 0
    private var gyrEntryCounter = 0
    private var accChunkCounter = 0
    private var gyrChunkCounter = 0

    private var chunkSize = 1000

    private val writerHandlerThread = HandlerThread("IMUHandlerThread").apply { start() }
    private val whandler = Handler(writerHandlerThread.looper)

    private val zipHandlerThread = HandlerThread("IMUZipThread").apply { start() }
    private val zhandler = Handler(zipHandlerThread.looper)


    // Function to switch builders
    private fun switchAccBuilder() {
        val tempBuilder = activeAccBuilder
        activeAccBuilder = inactiveAccBuilder
        inactiveAccBuilder = tempBuilder

        val tempAccData = activeAccData
        activeAccData = inactiveAccData
        inactiveAccData = tempAccData
    }

    // Function to switch builders
    private fun switchGyrBuilder(){
        val tempBuilder = activeGyrBuilder
        activeGyrBuilder = inactiveGyrBuilder
        inactiveGyrBuilder = tempBuilder

        val tempAccData = activeAccData
        activeAccData = inactiveAccData
        inactiveAccData = tempAccData

        val tempGyrData = activeGyrData
        activeGyrData = inactiveGyrData
        inactiveGyrData = tempGyrData
    }

    // Start listening to IMU sensors
    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        // capnproto stuff
        activeAccData.initEntries(chunkSize)
        inactiveAccData.initEntries(chunkSize)
        activeGyrData.initEntries(chunkSize)
        inactiveGyrData.initEntries(chunkSize)
        accChunkCounter = 0
        gyrChunkCounter = 0
        accEntryCounter = 0
        gyrEntryCounter = 0

    }

    // Stop listening to IMU sensors
    fun stop() {
        sensorManager.unregisterListener(this)
        writerHandlerThread.quitSafely()
        zhandler.post {
            var filename = "gyro_data_$gyrChunkCounter"
            writeGyrDataToFile(filename)
            filename = "acc_data_$accChunkCounter"
            writeAccDataToFile(filename)
            zipBinFiles()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val timestamp = it.timestamp
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val entry = activeAccData.entries[accEntryCounter]
                    entry.timestamp = timestamp
                    entry.initEntryAcc()
                    entry.entryAcc.xAcc = it.values[0]
                    entry.entryAcc.yAcc = it.values[1]
                    entry.entryAcc.zAcc = it.values[2]
                    accEntryCounter += 1
                    if (accEntryCounter >= chunkSize) {
                        switchAccBuilder()
                        val filename = "acc_data_$accChunkCounter"
                        whandler.post { writeAccDataToFile(filename) }
                        accEntryCounter = 0
                        accChunkCounter += 1
                    } else {

                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val entry = activeGyrData.entries[gyrEntryCounter]
                    entry.timestamp = timestamp
                    entry.initEntryGyro()
                    entry.entryGyro.rollAng = it.values[0]
                    entry.entryGyro.pitchAng = it.values[1]
                    entry.entryGyro.yawAng = it.values[2]
                    gyrEntryCounter += 1
                    if (gyrEntryCounter >= chunkSize) {
                        switchGyrBuilder()
                        val filename = "gyro_data_$gyrChunkCounter"
                        whandler.post { writeGyrDataToFile(filename) }
                        gyrEntryCounter = 0
                        gyrChunkCounter += 1
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

    // literally no way to avoid code duplication here, i hate kotlin, cannot
    // pass members by reference
    private fun writeAccDataToFile(fileName: String) {
        val tempFile = File(context.cacheDir, "$fileName.bin")

        // Write Cap'n Proto message to file
        FileOutputStream(tempFile).use { fos ->
            try {
                org.capnproto.Serialize.write(fos.channel, inactiveAccBuilder)
            } catch (e: Exception) {
                Log.e("IMUHandler", "Error writing ACC Cap'n Proto data to file: ${e.message}")
            }
        }
        // reset the builder to free memory
        inactiveAccBuilder = MessageBuilder()
        inactiveAccData = inactiveAccBuilder.initRoot(IMUData.factory)
        inactiveAccData.initEntries(chunkSize)
        // reset the inactive builder

        System.gc() // hope for garbage collector to do its thing

        // Log the size of the written file
        val fileSize = tempFile.length() / (1024 * 1024)
        Log.d("IMUHandler", "Data written to file: ${tempFile.absolutePath} of size ${fileSize}MB")
    }

    private fun writeGyrDataToFile(fileName: String) {
        val tempFile = File(context.cacheDir, "$fileName.bin")

        // Write Cap'n Proto message to file
        FileOutputStream(tempFile).use { fos ->
            try {
                org.capnproto.Serialize.write(fos.channel, inactiveGyrBuilder)
            } catch (e: Exception) {
                Log.e("IMUHandler", "Error writing Gyr Cap'n Proto data to file: ${e.message}")
            }
        }
        // reset the builder to free memory
        inactiveGyrBuilder = MessageBuilder()
        inactiveGyrData = inactiveGyrBuilder.initRoot(IMUData.factory)
        inactiveGyrData.initEntries(chunkSize)
        // reset the inactive builder

        System.gc() // hope for garbage collector to do its thing

        // Log the size of the written file
        val fileSize = tempFile.length() / (1024 * 1024)
        Log.d("IMUHandler", "Data written to file: ${tempFile.absolutePath} of size ${fileSize}MB")
    }

    private fun zipBinFiles(): File? {
        val cacheDir = context.cacheDir
        val zipFile = File(cacheDir, "IMUdata.zip")

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                cacheDir.listFiles()?.forEach { file ->
                    var bCond = file.name.startsWith("gyro_data")
                    bCond = bCond || file.name.startsWith("acc_data")
                    val eCond = file.name.endsWith(".bin")
                    if (file.isFile && bCond && eCond) {
                        // print file size
                        val fileSize = file.length() / (1024 * 1024)
                        Log.i("IMUHandler", "Adding file to zip: ${file.name} of size ${fileSize}MB")
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
            Log.i("IMUHandler", "Created zip file: ${zipFile.absolutePath}")
            return zipFile
        } catch (e: IOException) {
            Log.e("IMUHandler", "Error creating zip file: ${e.message}")
            return null
        }
    }

}