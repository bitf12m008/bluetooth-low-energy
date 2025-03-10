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
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.UUID
import android.content.Intent
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
    private lateinit var saveWorkout: Button
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>> // Declare launcher here

    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var permissionLauncherHC: ActivityResultLauncher<Set<String>>

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
                Log.e(TAG, "BluetoothLeScanner is null. Check Bluetooth state.")
            }

            requestPermissions()
        }

        checkAndRequestPermissions()
    }

    private fun onPermissionsGranted() {
        Log.e("HealthConnect", "Permission granted! Saving workout.")
    }

    private fun onPermissionsDenied() {
        Log.e("HealthConnect", "Permission denied! Cannot save workout.")
        Toast.makeText(this, "Permission denied! Cannot save workout.", Toast.LENGTH_LONG).show()
    }

    private fun checkAndRequestPermissions() {
        lifecycleScope.launch {
            if (!healthConnectManager.hasAllPermissions()) {
                permissionLauncherHC.launch(PERMISSIONS)
            } else {
                onPermissionsGranted()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        permissionLauncher.launch(permissions)
    }

    @SuppressLint("MissingPermission")
    private fun startBLEScan() {
        if (!hasPermissions()) {
            Log.e(TAG, "Missing permissions: SCAN=${ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)}, CONNECT=${ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)}, LOCATION=${ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)}")
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(SERVICE_UUID)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
            Log.d(TAG, "Started BLE scanning with filter for UUID: $SERVICE_UUID")
            Handler(Looper.getMainLooper()).postDelayed({ stopBLEScan() }, 20000) // Scan for 20 seconds
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: BLE scanning requires permissions.", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBLEScan() {
        if (!hasPermissions()) {
            Log.e(TAG, "Missing required permissions. Cannot stop BLE scan.")
            return
        }

        try {
            bluetoothLeScanner.stopScan(scanCallback)
            Log.d(TAG, "Stopped BLE scanning")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: BLE stopping requires permissions.", e)
        }
    }

    private fun hasPermissions(): Boolean {
        val scanGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val connectGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val locationGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return scanGranted && connectGranted && locationGranted
    }

    @SuppressLint("MissingPermission")
    private fun connectToTreadmill(device: BluetoothDevice) {
        if (!hasPermissions()) {
            Log.e(TAG, "Missing permissions for connection.")
            return
        }

        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to treadmill: ${device.address}")
                        gatt?.discoverServices() // Discover services after connecting
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
                    // Add logic to interact with services/characteristics here
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
                    // After successful read, proceed to the next characteristic
                    readNextCharacteristic()
                } else {
                    Log.w(TAG, "Characteristic read failed with status: $status")
                    // Even on failure, proceed to the next to avoid stalling
                    readNextCharacteristic()
                }
            }
        })
    }

    private fun readWorkoutData(service: BluetoothGattService) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission. Cannot read characteristics.")
            return
        }

        timeElapsed = null
        avgHeartRate = null
        totalDistance = null
        caloriesBurned = null
        workoutTitle = null
        // Queue all characteristics to read sequentially
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

        // Start reading the first characteristic
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
                        readNextCharacteristic() // Skip to next if failed
                    } else {

                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException: Unable to read ${nextChar.uuid}", e)
                    readNextCharacteristic() // Skip to next on exception
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
                    stopBLEScan() // Stop scanning once found
                    connectToTreadmill(it.device) // Connect to the treadmill
                } else {
                    Log.d(TAG, "Not the treadmill. Service UUIDs: $serviceUuids")
                }

                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Device name: ${it.device.name ?: "Unknown"}")
                } else {
                    Log.w(TAG, "Missing BLUETOOTH_CONNECT permission. Cannot access device name.")
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

                Log.d("HealthConnect", "$workoutTitle workout saved successfully!")
                Toast.makeText(this@MainActivity, "$workoutTitle workout saved successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("HealthConnect", "Failed to save manual workout", e)
                Toast.makeText(this@MainActivity, "Failed to save manual workout", Toast.LENGTH_LONG).show()
            }
        }
    }
}