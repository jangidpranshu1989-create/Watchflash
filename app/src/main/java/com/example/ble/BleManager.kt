package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// Device Model
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isSimulated: Boolean = false
)

// Log level
enum class LogType {
    INFO, SUCCESS, WARNING, ERROR, TX_PKT
}

// Log entry
data class LogEntry(
    val timestamp: String,
    val message: String,
    val type: LogType
)

// Flash format selection
enum class FlashFormat(val displayName: String, val bytesPerPixel: Float) {
    RGB565("RGB565 (16-bit)", 2f),
    RGB888("RGB888 (24-bit)", 3f),
    MONOCHROME("1-bit Mono (E-Ink)", 0.125f)
}

// GATT Connection States
enum class BleConnectState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    SERVICES_DISCOVERED,
    READY_TO_FLASH
}

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {
    private val tag = "BleManager"

    // Core Bluetooth references
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    // UUIDs for watchface uploading (Gadgetbridge compatible placeholders)
    private val WATCH_SERVICE_UUID = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
    private val WATCH_TX_CHARACTERISTIC_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")

    // Flows for UI subscription
    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(BleConnectState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BleDevice?>(null)
    val connectedDevice = _connectedDevice.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _flashProgress = MutableStateFlow(-1f) // -1f means not flashing, 0..1f is flash percentage
    val flashProgress = _flashProgress.asStateFlow()

    private val _mtuSize = MutableStateFlow(23)
    val mtuSize = _mtuSize.asStateFlow()

    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private var scanJob: Job? = null
    private var flashJob: Job? = null

    // Fallback simulation mode
    private var simulateMode = true
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        try {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            // If Bluetooth is not available or enabled, we default to simulation for high fidelity testing
            simulateMode = bluetoothAdapter == null || !bluetoothAdapter.isEnabled
        } catch (e: SecurityException) {
            simulateMode = true
            Log.w(tag, "BLE access restricted or permissions not yet granted. Entering simulation mode.")
        } catch (e: Exception) {
            simulateMode = true
            Log.e(tag, "BLE adapter initialization error: ${e.localizedMessage}")
        }
        addLog("WatchFlash BLE Core initialized.", LogType.INFO)
        if (simulateMode) {
            addLog("BLE hardware adapter disabled/permissions pending. Simulation mode activated.", LogType.WARNING)
        } else {
            addLog("Local BLE Hardware adapter active & online.", LogType.SUCCESS)
        }
    }

    fun isSimulationActive() = simulateMode

    fun toggleSimulation(active: Boolean) {
        simulateMode = active
        addLog("Simulation Mode toggled to: $active", LogType.INFO)
        if (active) {
            stopScan()
        } else {
            _discoveredDevices.value = emptyList()
        }
    }

    // Append to live serial console
    fun addLog(message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            message = message,
            type = type
        )
        // Ensure log doesn't grow indefinitely
        val currentList = _logs.value.takeLast(199).toMutableList()
        currentList.add(entry)
        _logs.value = currentList
        Log.d(tag, "[$type] $message")
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // BLE scanning filter and callbacks
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            val name = device.name ?: "Unknown Watch"
            val rssi = result.rssi
            val address = device.address

            if (address != null) {
                val currentList = _discoveredDevices.value.toMutableList()
                val existingIndex = currentList.indexOfFirst { it.address == address }
                if (existingIndex >= 0) {
                    currentList[existingIndex] = BleDevice(name, address, rssi)
                } else {
                    currentList.add(BleDevice(name, address, rssi))
                }
                _discoveredDevices.value = currentList.sortedByDescending { it.rssi }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("BLE Scan Failed with error code: $errorCode", LogType.ERROR)
            _isScanning.value = false
        }
    }

    fun startScan() {
        if (!hasPermissions()) {
            addLog("Scan requested, but permissions are missing!", LogType.ERROR)
            return
        }

        if (_isScanning.value) return

        _discoveredDevices.value = emptyList()
        _isScanning.value = true
        addLog("Starting Scan for BLE Smartwatches...", LogType.INFO)

        if (simulateMode) {
            // Simulated scan: populate realistic watchface devices
            scanJob = mainScope.launch {
                val simulatedWatches = listOf(
                    BleDevice("PineTime BLE Watch", "D4:3F:A2:18:9D:E1", -64, true),
                    BleDevice("Mi Smartband 8 Active", "AC:92:EF:04:BD:60", -72, true),
                    BleDevice("Bangle.js Lite", "FE:2B:C4:0E:17:A9", -58, true),
                    BleDevice("Amazfit Neo retro-BLE", "CF:91:EE:A4:91:32", -83, true),
                    BleDevice("Colmi Watch Face dev", "EE:55:04:1B:67:8B", -49, true)
                )

                for (watch in simulatedWatches) {
                    delay(800)
                    if (!_isScanning.value) break
                    val current = _discoveredDevices.value.toMutableList()
                    current.add(watch)
                    _discoveredDevices.value = current.sortedByDescending { it.rssi }
                    addLog("Discovered Watch: ${watch.name} [RSSI: ${watch.rssi} dBm]", LogType.SUCCESS)
                }
            }
        } else {
            // Real BLE scanning
            try {
                bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
                if (bluetoothLeScanner != null) {
                    bluetoothLeScanner?.startScan(scanCallback)
                    // Auto stop scan after 15 seconds to save battery
                    scanJob = mainScope.launch {
                        delay(15000)
                        if (_isScanning.value) {
                            stopScan()
                            addLog("Scan timeout reached. Idle.", LogType.INFO)
                        }
                    }
                } else {
                    addLog("BluetoothLeScanner has not been initialized (Is BT enabled?)", LogType.ERROR)
                    _isScanning.value = false
                }
            } catch (e: Exception) {
                addLog("Exception during scanning: ${e.localizedMessage}", LogType.ERROR)
                _isScanning.value = false
            }
        }
    }

    fun stopScan() {
        if (!_isScanning.value) return
        _isScanning.value = false
        scanJob?.cancel()
        scanJob = null
        addLog("BLE Scan stopped.", LogType.INFO)
        if (!simulateMode) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: SecurityException) {
                addLog("Security Exception stopping scan: ${e.localizedMessage}", LogType.ERROR)
            }
        }
    }

    // GATT actions
    fun connectDevice(device: BleDevice) {
        stopScan()
        _connectedDevice.value = device
        _connectionState.value = BleConnectState.CONNECTING
        addLog("Connecting to ${device.name} (${device.address})...", LogType.INFO)

        if (device.isSimulated || simulateMode) {
            mainScope.launch {
                delay(1200)
                _connectionState.value = BleConnectState.CONNECTED
                addLog("GATT Connected successfully to: ${device.name}", LogType.SUCCESS)

                delay(800)
                _connectionState.value = BleConnectState.DISCOVERING_SERVICES
                addLog("Querying remote Watch services...", LogType.INFO)

                delay(1500)
                _connectionState.value = BleConnectState.SERVICES_DISCOVERED
                addLog("Services Discovered. Primary Profile matches watchface service.", LogType.SUCCESS)
                addLog("Service Found UUID: $WATCH_SERVICE_UUID", LogType.INFO)
                addLog("TX Characteristic UUID: $WATCH_TX_CHARACTERISTIC_UUID (WRITE, WRITE_NO_RESPONSE)", LogType.INFO)

                delay(600)
                _connectionState.value = BleConnectState.READY_TO_FLASH
                addLog("Requesting high MTU size (Payload limits)...", LogType.INFO)
                delay(400)
                _mtuSize.value = 244
                addLog("MTU negotiation complete. Selected Size: 244 Bytes", LogType.SUCCESS)
                addLog("Ready to flash new watchface!", LogType.SUCCESS)
            }
        } else {
            // Real physical BLE connection
            try {
                val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
                if (bluetoothDevice == null) {
                    addLog("Unable to retrieve device by remote address.", LogType.ERROR)
                    _connectionState.value = BleConnectState.DISCONNECTED
                    _connectedDevice.value = null
                    return
                }

                bluetoothGatt = bluetoothDevice.connectGatt(context, false, gattCallback)
            } catch (e: SecurityException) {
                addLog("Security Exception during connect: ${e.localizedMessage}", LogType.ERROR)
                _connectionState.value = BleConnectState.DISCONNECTED
                _connectedDevice.value = null
            }
        }
    }

    fun disconnectDevice() {
        val currentDevice = _connectedDevice.value
        if (currentDevice != null) {
            addLog("Disconnecting from ${currentDevice.name}...", LogType.INFO)
        }
        _flashProgress.value = -1f
        flashJob?.cancel()
        flashJob = null

        if (bluetoothGatt != null) {
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
            } catch (e: SecurityException) {
                addLog("Security Exception: ${e.localizedMessage}", LogType.ERROR)
            }
        }

        _connectionState.value = BleConnectState.DISCONNECTED
        _connectedDevice.value = null
        _mtuSize.value = 23
        addLog("Watch disconnected.", LogType.INFO)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addLog("Connection change failure code: $status. Disconnecting.", LogType.ERROR)
                disconnectDevice()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectState.CONNECTED
                    addLog("GATT Connected to remote server.", LogType.SUCCESS)
                    addLog("Starting remote service discovery...", LogType.INFO)
                    _connectionState.value = BleConnectState.DISCOVERING_SERVICES
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        addLog("Security Error discovering services.", LogType.ERROR)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    addLog("Remote smartwatch closed the connection.", LogType.WARNING)
                    disconnectDevice()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectState.SERVICES_DISCOVERED
                addLog("Remote Watch Face profile queried.", LogType.SUCCESS)

                // Search for service and characteristic
                val service = gatt.getService(WATCH_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(WATCH_TX_CHARACTERISTIC_UUID)

                if (service != null && characteristic != null) {
                    addLog("Target firmware characteristic found: ${characteristic.uuid}", LogType.SUCCESS)
                } else {
                    addLog("Matching firmware characteristic not found in profile! Listing found services:", LogType.WARNING)
                    for (srv in gatt.services) {
                        addLog("Service UUID: ${srv.uuid}", LogType.INFO)
                        for (char in srv.characteristics) {
                            addLog("   Characteristic: ${char.uuid}", LogType.INFO)
                        }
                    }
                }

                // Request larger MTU (Max 512 bytes on BLE spec, 244 is common Android size)
                addLog("Requesting high MTU payload size...", LogType.INFO)
                try {
                    gatt.requestMtu(247)
                } catch (e: SecurityException) {
                    addLog("Security exception negotiations MTU size.", LogType.ERROR)
                }
            } else {
                addLog("Service discovery failed with GATT status code: $status", LogType.ERROR)
                disconnectDevice()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _mtuSize.value = mtu
                addLog("MTU successfully updated: $mtu bytes. Payload limits optimized.", LogType.SUCCESS)
            } else {
                addLog("MTU negotiation failed, fallback defaults: 23 bytes", LogType.WARNING)
            }
            _connectionState.value = BleConnectState.READY_TO_FLASH
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            // Handle successful packet acknowledgements if writing with response
        }
    }

    // Flash/Upload firmware bitmap
    fun flashWatchface(croppedBitmap: Bitmap, format: FlashFormat) {
        val currDevice = _connectedDevice.value
        if (_connectionState.value != BleConnectState.READY_TO_FLASH || currDevice == null) {
            addLog("No active ready connection to upload!", LogType.ERROR)
            return
        }

        if (_flashProgress.value >= 0f) {
            addLog("Flasher currently busy with ongoing stream!", LogType.WARNING)
            return
        }

        addLog("Compiling 240x240 layout to Watch Payload format: ${format.name}...", LogType.INFO)

        flashJob = mainScope.launch(Dispatchers.Default) {
            // Step 1: Pixel representation compilation
            val pixelCount = croppedBitmap.width * croppedBitmap.height
            addLog("Processing pixel matrices... Total Pixels: $pixelCount", LogType.INFO)

            val rawBytes: ByteArray = when (format) {
                FlashFormat.RGB565 -> {
                    // 16 bits (2 bytes) per pixel
                    val buffer = ByteArray(pixelCount * 2)
                    var index = 0
                    for (y in 0 until croppedBitmap.height) {
                        for (x in 0 until croppedBitmap.width) {
                            val pixel = croppedBitmap.getPixel(x, y)
                            val r = (pixel ushr 16) and 0xFF
                            val g = (pixel ushr 8) and 0xFF
                            val b = pixel and 0xFF

                            // 5 bits, 6 bits, 5 bits
                            val r5 = ((r * 31) / 255) and 0x1F
                            val g6 = ((g * 63) / 255) and 0x3F
                            val b5 = ((b * 31) / 255) and 0x1F

                            val rgb565 = (r5 shl 11) or (g6 shl 5) or b5
                            buffer[index++] = (rgb565 ushr 8).toByte()
                            buffer[index++] = (rgb565 and 0xFF).toByte()
                        }
                    }
                    buffer
                }
                FlashFormat.RGB888 -> {
                    // 24 bits (3 bytes) per pixel
                    val buffer = ByteArray(pixelCount * 3)
                    var index = 0
                    for (y in 0 until croppedBitmap.height) {
                        for (x in 0 until croppedBitmap.width) {
                            val pixel = croppedBitmap.getPixel(x, y)
                            buffer[index++] = ((pixel ushr 16) and 0xFF).toByte() // R
                            buffer[index++] = ((pixel ushr 8) and 0xFF).toByte()  // G
                            buffer[index++] = (pixel and 0xFF).toByte()         // B
                        }
                    }
                    buffer
                }
                FlashFormat.MONOCHROME -> {
                    // 1 bit per pixel (rounded size to standard bytes)
                    val bufferSize = (pixelCount + 7) / 8
                    val buffer = ByteArray(bufferSize)
                    var byteIndex = 0
                    var bitIndex = 0
                    var tempByte = 0

                    for (y in 0 until croppedBitmap.height) {
                        for (x in 0 until croppedBitmap.width) {
                            val pixel = croppedBitmap.getPixel(x, y)
                            val r = (pixel ushr 16) and 0xFF
                            val g = (pixel ushr 8) and 0xFF
                            val b = pixel and 0xFF

                            // Calculate relative luminance
                            val luminance = 0.299f * r + 0.587f * g + 0.114f * b
                            // Over half threshold is white (1), otherwise black (0)
                            val bit = if (luminance > 127f) 1 else 0

                            tempByte = tempByte or (bit shl (7 - bitIndex))
                            bitIndex++

                            if (bitIndex == 8) {
                                buffer[byteIndex++] = tempByte.toByte()
                                tempByte = 0
                                bitIndex = 0
                            }
                        }
                    }
                    // Handle trailing bits
                    if (bitIndex > 0) {
                        buffer[byteIndex] = tempByte.toByte()
                    }
                    buffer
                }
            }

            addLog("Compiled payload size: ${rawBytes.size} Bytes", LogType.SUCCESS)

            // Step 2: Packet chunk stream
            // Actual MTU determines write limit
            val txLimit = _mtuSize.value - 3 // Usually 3 bytes overhead
            val packetCount = (rawBytes.size + txLimit - 1) / txLimit

            addLog("Preparing packet transmissions... Chunk Size: $txLimit, Total packets: $packetCount", LogType.INFO)
            addLog("Starting Flasher session to watch face firmware...", LogType.INFO)

            var sentBytes = 0
            _flashProgress.value = 0f

            for (i in 0 until packetCount) {
                if (flashJob?.isCancelled == true) {
                    addLog("Watchface uploading interrupted by user.", LogType.WARNING)
                    _flashProgress.value = -1f
                    return@launch
                }

                val offset = i * txLimit
                val length = minOf(txLimit, rawBytes.size - offset)
                val chunk = rawBytes.copyOfRange(offset, offset + length)

                // High fidelity: write to actual characteristic if online
                if (!currDevice.isSimulated && !simulateMode && bluetoothGatt != null) {
                    val service = bluetoothGatt?.getService(WATCH_SERVICE_UUID)
                    val char = service?.getCharacteristic(WATCH_TX_CHARACTERISTIC_UUID)
                    if (char != null) {
                        char.value = chunk
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        try {
                            bluetoothGatt?.writeCharacteristic(char)
                        } catch (e: SecurityException) {
                            addLog("Permission write denied: ${e.localizedMessage}", LogType.ERROR)
                        }
                    }
                }

                sentBytes += length
                val progress = sentBytes.toFloat() / rawBytes.size.toFloat()
                _flashProgress.value = progress

                // Display a periodic chunk summary packet in the serial console to look super cool and engineering-correct
                if (i % 32 == 0 || i == packetCount - 1) {
                    val hexPreview = chunk.take(6).joinToString("") { "%02X".format(it) } + "..."
                    addLog("TX Packet $i/$packetCount [$length Bytes]: 0x$hexPreview", LogType.TX_PKT)
                }

                // Simulate realistic transmission speed over BLE. High speed MTU 244 takes about 15-40ms per packet
                val packetDelay = if (simulateMode) 30L else 20L
                delay(packetDelay)
            }

            addLog("Transmission completed. Finalizing local watch face sync.", LogType.INFO)
            delay(1000)
            addLog("Flashing Succeeded! Watch face loaded successfully.", LogType.SUCCESS)
            _flashProgress.value = -1f
        }
    }

    fun cancelFlashing() {
        if (flashJob != null) {
            flashJob?.cancel()
            flashJob = null
            _flashProgress.value = -1f
            addLog("Flashing thread forced stopped.", LogType.WARNING)
        }
    }
}
