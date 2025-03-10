package com.precor.btfitness

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.activity.result.contract.ActivityResultContracts
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.UUID
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.ZoneOffset

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BLEScanner"
        private val SERVICE_UUID = ParcelUuid(UUID.fromString("12345678-1234-5678-1234-56789abcdef0"))
        private val TIME_ELAPSED_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
        private val AVG_HEART_RATE_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
        private val TOTAL_DISTANCE_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef3")
        private val CALORIES_BURNED_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef4")
        private val WORKOUT_TITLE_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef5")
    }
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null
    private val characteristicQueue = LinkedList<BluetoothGattCharacteristic>()
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>> 

    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var permissionLauncherHC: ActivityResultLauncher<Set<String>>

    private lateinit var saveWorkout: Button

    private var timeElapsed: Long? = null
    private var avgHeartRate: Double? = null
    private var totalDistance: Double? = null
    private var caloriesBurned: Double? = null
    private var workoutTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        saveWorkout = findViewById(R.id.discoverButton)

        healthConnectManager = HealthConnectManager(this)

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsGranted ->
            Log.d(TAG, "Permissions requested: $permissionsGranted")
            if (permissionsGranted.all { it.value }) {
                Log.d(TAG, "All permissions granted. Starting BLE scan.")
                startBLEScan()
            } else {
                Log.e(TAG, "Permissions not granted: $permissionsGranted. BLE scanning will not start.")
            }
        }

        permissionLauncherHC =
            registerForActivityResult(healthConnectManager.requestPermissionsActivityContract()) { grantedPermissions ->
                if (grantedPermissions.containsAll(PERMISSIONS)) {
                    onPermissionsGranted()
                } else {
                    onPermissionsDenied()
                }
            }

        saveWorkout.setOnClickListener {
            if (!bluetoothAdapter.isEnabled) {
                Log.e(TAG, "Bluetooth is disabled. Please enable it.")
                Toast.makeText(this@MainActivity, "Bluetooth is disabled. Please enable it.", Toast.LENGTH_SHORT).show()
            }

            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Toast.makeText(this@MainActivity, "BluetoothLeScanner is null. Check Bluetooth state.", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "BluetoothLeScanner is null. Check Bluetooth state.")
            }

            requestBTPermissions()
        }

        requestHCPermissions()
    }

    private fun onPermissionsGranted() {
        Log.e(TAG, "HealthConnect Permissions granted!")
    }

    private fun onPermissionsDenied() {
        Log.e(TAG, "HealthConnect Permission denied! Cannot save workout.")
        Toast.makeText(this, "Permission denied! Cannot save workout.", Toast.LENGTH_LONG).show()
    }

    private fun requestHCPermissions() {
        permissionLauncherHC.launch(PERMISSIONS)
    }

    private fun requestBTPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        permissionLauncher.launch(permissions)
    }

    private fun startBLEScan() {
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(SERVICE_UUID)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
            Log.d(TAG, "Started BLE scanning with filter for UUID: $SERVICE_UUID")
            Handler(Looper.getMainLooper()).postDelayed({ stopBLEScan() }, 30000)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: BLE scanning requires permissions.", e)
        }
    }

    private fun stopBLEScan() {
        try {
            bluetoothLeScanner.stopScan(scanCallback)
            Log.d(TAG, "Stopped BLE scanning")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: BLE stopping requires permissions.", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToTreadmill(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to treadmill: ${device.address}")
                        gatt?.discoverServices()
                    }
                    BluetoothGatt.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from treadmill: ${device.address}")
                    }
                    else -> Log.d(TAG, "Connection state changed: $newState")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Services discovered: ${gatt?.services?.map { it.uuid }}")
                    gatt?.getService(UUID.fromString("12345678-1234-5678-1234-56789abcdef0"))?.let { service ->
                        readWorkoutData(service)
                    } ?: Log.w(TAG, "Service not found")
                } else {
                    Log.e(TAG, "Service discovery failed: $status")
                }
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    val uuid = characteristic.uuid
                    val value = characteristic.value
                    val buffer = ByteBuffer.wrap(value)

                    when (uuid) {
                        TIME_ELAPSED_CHAR_UUID -> {
                            timeElapsed = buffer.int.toLong()
                            Log.d(TAG, "Time Elapsed: ${timeElapsed}s")
                        }
                        AVG_HEART_RATE_CHAR_UUID -> {
                            avgHeartRate = buffer.short.toDouble()
                            Log.d(TAG, "Avg Heart Rate: $avgHeartRate bpm")
                        }
                        TOTAL_DISTANCE_CHAR_UUID -> {
                            totalDistance = buffer.float.toDouble()
                            Log.d(TAG, "Total Distance: $totalDistance km")
                        }
                        CALORIES_BURNED_CHAR_UUID -> {
                            caloriesBurned = buffer.short.toDouble()
                            Log.d(TAG, "Calories Burned: $caloriesBurned")
                        }
                        WORKOUT_TITLE_CHAR_UUID -> {
                            workoutTitle = String(value, StandardCharsets.UTF_8)
                            Log.d(TAG, "Workout Title: $workoutTitle")
                        }
                    }
                    readNextCharacteristic()
                } else {
                    Log.w(TAG, "Characteristic read failed with status: $status")
                    readNextCharacteristic()
                }
            }
        })
    }

    private fun readWorkoutData(service: BluetoothGattService) {
        timeElapsed = null
        avgHeartRate = null
        totalDistance = null
        caloriesBurned = null
        workoutTitle = null

        characteristicQueue.clear()
        listOf(
            TIME_ELAPSED_CHAR_UUID,
            AVG_HEART_RATE_CHAR_UUID,
            TOTAL_DISTANCE_CHAR_UUID,
            CALORIES_BURNED_CHAR_UUID,
            WORKOUT_TITLE_CHAR_UUID
        ).forEach { uuid ->
            service.getCharacteristic(uuid)?.let { char ->
                characteristicQueue.add(char)
                Log.d(TAG, "Queued characteristic: $uuid")
            } ?: Log.w(TAG, "Characteristic $uuid not found in service")
        }

        readNextCharacteristic()
    }

    private fun readNextCharacteristic() {
        bluetoothGatt?.let { gatt ->
            if (characteristicQueue.isNotEmpty()) {
                val nextChar = characteristicQueue.poll()
                Log.d(TAG, "Reading next characteristic: ${nextChar.uuid}, Queue size: ${characteristicQueue.size}")
                try {
                    val success = gatt.readCharacteristic(nextChar)
                    if (!success) {
                        Log.w(TAG, "Failed to initiate read for ${nextChar.uuid}")
                        readNextCharacteristic()
                    } else {

                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException: Unable to read ${nextChar.uuid}", e)
                    readNextCharacteristic()
                }
            } else {
                Log.d(TAG, "All characteristics read")
                saveManualWorkout(
                    totalDistance!!,
                    caloriesBurned!!,
                    timeElapsed!!,
                    avgHeartRate!!,
                    workoutTitle!!
                )
            }
        } ?: Log.w(TAG, "BluetoothGatt is null, cannot read characteristics")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                val deviceAddress = it.device.address
                val rssi = it.rssi
                val scanRecord = it.scanRecord?.bytes?.contentToString() ?: "No data"
                Log.d(TAG, "Device detected - Address: $deviceAddress, RSSI: $rssi, Data: $scanRecord")

                val serviceUuids: List<ParcelUuid>? = it.scanRecord?.serviceUuids
                if (serviceUuids?.contains(SERVICE_UUID) == true) {
                    Log.d(TAG, "TREADMILL DETECTED! Address: $deviceAddress, Name: ${it.device.name ?: "Unknown"}")
                    stopBLEScan()
                    connectToTreadmill(it.device)
                } else {
                    Log.d(TAG, "Not the treadmill. Service UUIDs: $serviceUuids")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed with error: $errorCode")
            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> Log.e(TAG, "Scan failed: Already started")
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> Log.e(TAG, "Scan failed: App registration failed")
                SCAN_FAILED_INTERNAL_ERROR -> Log.e(TAG, "Scan failed: Internal error")
                SCAN_FAILED_FEATURE_UNSUPPORTED -> Log.e(TAG, "Scan failed: Feature unsupported")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        stopBLEScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        Log.d(TAG, "Cleaned up GATT connection")
    }

    private fun saveManualWorkout(
        totalDistance: Double,
        caloriesBurned: Double,
        timeElapsed: Long,
        avgHeartRate: Double,
        workoutTitle: String,
    ) {
        lifecycleScope.launch {
            try {
                val client = healthConnectManager.healthConnectClient
                val now = Instant.now()
                val startTime = now.minusSeconds(timeElapsed)

                val distanceRecord = DistanceRecord(
                    metadata = androidx.health.connect.client.records.metadata.Metadata(),
                    distance = Length.meters(totalDistance),
                    startTime = startTime,
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = now,
                    endZoneOffset = ZoneOffset.UTC
                )

                val caloriesRecord = TotalCaloriesBurnedRecord(
                    metadata = androidx.health.connect.client.records.metadata.Metadata(),
                    energy = Energy.kilocalories(caloriesBurned),
                    startTime = startTime,
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = now,
                    endZoneOffset = ZoneOffset.UTC
                )

                val heartRateRecord = HeartRateRecord(
                    metadata = androidx.health.connect.client.records.metadata.Metadata(),
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = startTime.plusSeconds(timeElapsed / 2),
                            beatsPerMinute = avgHeartRate.toLong()
                        )
                    ),
                    startTime = startTime,
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = now,
                    endZoneOffset = ZoneOffset.UTC
                )

                val workoutRecord = ExerciseSessionRecord(
                    metadata = androidx.health.connect.client.records.metadata.Metadata(),
                    title = workoutTitle,
                    startTime = startTime,
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = now,
                    endZoneOffset = ZoneOffset.UTC,
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                )

                client.insertRecords(
                    listOf(distanceRecord, caloriesRecord, heartRateRecord, workoutRecord)
                )

                Log.d(TAG, "$workoutTitle workout saved successfully!")
                Toast.makeText(this@MainActivity, "$workoutTitle workout saved successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save manual workout", e)
                Toast.makeText(this@MainActivity, "Failed to save manual workout", Toast.LENGTH_LONG).show()
            }
        }
    }
}