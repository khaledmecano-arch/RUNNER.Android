package com.example.runnerble

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private val serviceUuid = UUID.fromString("0000fe20-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val knownChars = listOf("FE21","FE22","FE23","FE25","FE27","FE28","FE29")
    private lateinit var adapter: BluetoothAdapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val devices = mutableListOf<BluetoothDevice>()
    private val characteristics = linkedMapOf<String, BluetoothGattCharacteristic>()
    private lateinit var deviceSpinner: Spinner
    private lateinit var txSpinner: Spinner
    private lateinit var notifySpinner: Spinner
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var scanButton: Button
    private lateinit var connectButton: Button
    private lateinit var openButton: Button
    private lateinit var unlockButton: Button
    private lateinit var customInput: EditText
    private lateinit var sendButton: Button
    private lateinit var notifyButton: Button
    private lateinit var charInfo: TextView

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            if (devices.none { it.address == d.address }) {
                devices.add(d)
                runOnUiThread {
                    val names = devices.map { deviceLabel(it) }
                    deviceSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
                    log("Found ${deviceLabel(d)} RSSI=${result.rssi}")
                }
            }
        }
        override fun onScanFailed(errorCode: Int) { runOnUiThread { log("Scan failed: $errorCode") } }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS) {
                    status.text = "Connected — discovering services"
                    log("Connected to ${g.device.address}; discovering services")
                    g.discoverServices()
                } else {
                    log("Connection state=$newState status=$statusCode")
                    status.text = if (newState == BluetoothProfile.STATE_DISCONNECTED) "Disconnected" else "Connection error ($statusCode)"
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        characteristics.clear()
                        refreshCharacteristicSpinners()
                        g.close()
                        if (gatt === g) gatt = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            runOnUiThread {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    log("Service discovery failed: $statusCode"); return@runOnUiThread
                }
                characteristics.clear()
                g.services.forEach { service ->
                    log("Service ${service.uuid}")
                    service.characteristics.forEach { c ->
                        val short = c.uuid.toString().substring(4, 8).uppercase(Locale.US)
                        characteristics[short] = c
                        log("  $short properties=${propertyNames(c.properties)}")
                    }
                }
                refreshCharacteristicSpinners()
                status.text = "Connected — ${characteristics.size} characteristics found"
                log("Service discovery complete")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            runOnUiThread { log("Write callback ${shortUuid(characteristic.uuid)} status=$statusCode (${if (statusCode == 0) "GATT success" else "failed"})") }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            runOnUiThread { log("Notification ${shortUuid(characteristic.uuid)}: ${hex(value)} | ${value.toString(Charsets.UTF_8)}") }
        }
        @Deprecated("Deprecated on older Android versions")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: byteArrayOf()
            runOnUiThread { log("Notification ${shortUuid(characteristic.uuid)}: ${hex(bytes)} | ${bytes.toString(Charsets.UTF_8)}") }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            runOnUiThread { log("Descriptor write ${descriptor.uuid} status=$statusCode") }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
            runOnUiThread { log("Read ${shortUuid(characteristic.uuid)} status=$statusCode: ${hex(value)} | ${value.toString(Charsets.UTF_8)}") }
        }
        @Deprecated("Deprecated on older Android versions")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: byteArrayOf()
            runOnUiThread { log("Read ${shortUuid(characteristic.uuid)} status=$statusCode: ${hex(bytes)} | ${bytes.toString(Charsets.UTF_8)}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        scanner = adapter.bluetoothLeScanner
        buildUi()
        requestBlePermissions()
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,18,20,12) }
        val title = TextView(this).apply { text = "RUNNER BLE Controller"; textSize = 24f; setTextColor(0xFF12345A.toInt()) }
        outer.addView(title)
        status = TextView(this).apply { text = "Not connected"; textSize = 15f; setPadding(0,10,0,12) }
        outer.addView(status)
        scanButton = Button(this).apply { text = "Scan for devices"; setOnClickListener { startScan() } }
        outer.addView(scanButton)
        deviceSpinner = Spinner(this)
        outer.addView(deviceSpinner)
        connectButton = Button(this).apply { text = "Connect to selected device"; setOnClickListener { connectSelected() } }
        outer.addView(connectButton)
        outer.addView(label("Command characteristic (selectable)"))
        txSpinner = Spinner(this)
        outer.addView(txSpinner)
        openButton = Button(this).apply { text = "Send OPEN"; setOnClickListener { sendText("OPEN") } }
        outer.addView(openButton)
        unlockButton = Button(this).apply { text = "Send UNLOCK"; setOnClickListener { sendText("UNLOCK") } }
        outer.addView(unlockButton)
        outer.addView(label("Custom UTF-8 command"))
        customInput = EditText(this).apply { hint = "Enter command"; singleLine = true }
        outer.addView(customInput)
        sendButton = Button(this).apply { text = "Send custom command"; setOnClickListener { sendText(customInput.text.toString()) } }
        outer.addView(sendButton)
        outer.addView(label("Notification characteristic"))
        notifySpinner = Spinner(this)
        outer.addView(notifySpinner)
        notifyButton = Button(this).apply { text = "Enable notifications"; setOnClickListener { toggleNotifications() } }
        outer.addView(notifyButton)
        charInfo = TextView(this).apply { textSize = 12f; setPadding(0,6,0,6) }
        outer.addView(charInfo)
        val logTitle = TextView(this).apply { text = "Communication log"; textSize = 17f }
        outer.addView(logTitle)
        logScroll = ScrollView(this)
        logView = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        logScroll.addView(logView)
        outer.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer)
        refreshCharacteristicSpinners()
    }

    private fun label(s: String) = TextView(this).apply { text = s; textSize = 14f; setPadding(0,8,0,2) }

    private fun requestBlePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                     else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) log(if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) "Bluetooth permissions granted" else "Bluetooth permissions denied")
    }

    private fun startScan() {
        if (!hasPermissions()) { requestBlePermissions(); log("Grant Bluetooth permissions first"); return }
        if (!adapter.isEnabled) { log("Bluetooth is disabled"); return }
        if (scanning) { scanner?.stopScan(scanCallback); scanning = false }
        devices.clear()
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Scanning…"))
        try {
            scanner = adapter.bluetoothLeScanner
            scanner?.startScan(scanCallback)
            scanning = true
            log("BLE scan started (showing nearby devices)")
            scanButton.postDelayed({
                if (scanning) {
                    try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
                    scanning = false
                    log("Scan stopped")
                    if (devices.isEmpty()) deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No devices found"))
                }
            }, 12000)
        } catch (e: SecurityException) { log("Scan permission error: ${e.message}") }
    }

    private fun connectSelected() {
        if (!hasPermissions()) { requestBlePermissions(); return }
        if (devices.isEmpty()) { log("Scan and select a device first"); return }
        val d = devices.getOrNull(deviceSpinner.selectedItemPosition) ?: return
        gatt?.let { it.disconnect(); it.close() }
        try {
            status.text = "Connecting…"
            log("Connecting to ${deviceLabel(d)}")
            gatt = d.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) { log("Connect error: ${e.message}") }
    }

    private fun refreshCharacteristicSpinners() {
        val tx = characteristics.filterValues { it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 }.keys.toList()
        val rx = characteristics.filterValues { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }.keys.toList()
        txSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, if (tx.isEmpty()) listOf("No writable characteristics") else tx)
        notifySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, if (rx.isEmpty()) listOf("No notify/indicate characteristics") else rx)
        charInfo.text = "Writable: ${tx.joinToString().ifEmpty { "none" }}\nNotify/indicate: ${rx.joinToString().ifEmpty { "none" }}"
    }

    private fun sendText(text: String) {
        if (text.isBlank()) { log("Command is empty"); return }
        val key = txSpinner.selectedItem?.toString() ?: return
        val c = characteristics[key] ?: run { log("No writable characteristic selected"); return }
        val g = gatt ?: run { log("Not connected"); return }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val supportsResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val supportsNoResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        if (!supportsResponse && !supportsNoResponse) { log("$key is not writable"); return }
        try {
            val writeType = if (supportsResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = writeType
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            log("TX $key (${if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) "with response" else "without response"}): $text [${hex(bytes)}] accepted=$accepted")
        } catch (e: Exception) { log("Write error: ${e.message}") }
    }

    private fun toggleNotifications() {
        val key = notifySpinner.selectedItem?.toString() ?: return
        val c = characteristics[key] ?: run { log("No notification characteristic selected"); return }
        val g = gatt ?: run { log("Not connected"); return }
        val descriptor = c.getDescriptor(cccdUuid) ?: run { log("$key has no CCCD descriptor"); return }
        val enable = notifyButton.text.toString().startsWith("Enable")
        try {
            val local = g.setCharacteristicNotification(c, enable)
            val value = if (enable && c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else if (enable)
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            notifyButton.text = if (enable) "Disable notifications" else "Enable notifications"
            log("${if (enable) "Enable" else "Disable"} $key notifications: local=$local descriptorAccepted=$accepted")
        } catch (e: Exception) { log("Notification setup error: ${e.message}") }
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31)
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun deviceLabel(d: BluetoothDevice): String {
        return try { "${d.name ?: "Unknown"} (${d.address})" } catch (_: SecurityException) { d.address }
    }
    private fun propertyNames(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
    }.joinToString("|")
    private fun shortUuid(uuid: UUID) = uuid.toString().substring(4,8).uppercase(Locale.US)
    private fun hex(bytes: ByteArray) = bytes.joinToString("-") { "%02X".format(it) }
    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logView.append("[$time] $message\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        try { if (scanning) scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        super.onDestroy()
    }
}
