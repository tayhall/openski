package com.openski.android

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/** GATT operations are serialised; callbacks from a replaced connection are ignored. */
class BleSensorClient(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onSample: (SensorSample) -> Unit,
    private val onReady: (Boolean) -> Unit = {},
    private val onRecorder: (RecorderStatus) -> Unit = {},
    private val onChunk: (FlashChunk?) -> Unit = {},
    private val onBattery: (Int?) -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onRssi: (Int) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private data class Operation(val start: (BluetoothGatt) -> Boolean, val optional: Boolean = false,
        val done: () -> Unit = {}, val rssi: Boolean=false, val mtu: Boolean=false,
        val description: String="GATT operation")
    private val queue = ArrayDeque<Operation>()
    private var current: Operation? = null
    private var ready = false
    val isReady get() = ready
    private var control: BluetoothGattCharacteristic? = null
    private val timeout = Runnable {
        if (current?.optional == true) finish(false)
        else fail("${current?.description ?: "Bluetooth connection"} timed out")
    }

    fun connect(device: BluetoothDevice) {
        disconnect()
        try {
            onStatus("Connecting to ${device.name ?: device.address}")
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            handler.postDelayed(timeout, 15_000)
        } catch (_: SecurityException) { fail("Bluetooth permission required") }
    }

    fun disconnect() {
        handler.removeCallbacks(timeout)
        queue.clear()
        current = null
        ready = false
        control = null
        val old = gatt
        gatt = null
        try { old?.close() } catch (_: SecurityException) { /* Permission can be revoked during a connection. */ }
    }

    private fun fail(message: String) {
        Log.w("OpenSkiBLE", "${gatt?.device?.address ?: "unknown sensor"}: $message")
        disconnect()
        onStatus(message)
        onDisconnected()
    }

    private fun enqueue(operation: Operation) { queue.addLast(operation); advance() }
    private fun advance() {
        if (current != null) return
        val connection = gatt ?: return
        val next = queue.removeFirstOrNull() ?: return
        current = next
        handler.postDelayed(timeout, 10_000)
        val started = try { next.start(connection) } catch (_: SecurityException) { false }
        if (!started) finish(false)
    }
    private fun finish(success: Boolean) {
        handler.removeCallbacks(timeout)
        val operation = current ?: return
        current = null
        if (!success && !operation.optional) { fail("${operation.description} failed; retrying"); return }
        operation.done()
        advance()
    }
    private fun dispatch(connection: BluetoothGatt, action: () -> Unit) {
        handler.post { if (gatt === connection) action() }
    }

    fun command(opcode: Int, offset: Int = 0): Boolean {
        val characteristic = control ?: return false
        if (!ready) return false
        val bytes = RecorderCommand.bytes(opcode, offset)
        enqueue(Operation({ connection ->
            try {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            connection.writeCharacteristic(characteristic)
            } catch (_: SecurityException) { false }
        }))
        return true
    }

    fun pollRssi() {
        if(!ready || current!=null || queue.isNotEmpty()) return
        enqueue(Operation({ try { it.readRemoteRssi() } catch(_: SecurityException) { false } },optional=true,rssi=true))
    }

    private fun subscribe(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, done: () -> Unit = {}) {
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        val enabled = try { connection.setCharacteristicNotification(characteristic, true) } catch (_: SecurityException) { false }
        if (descriptor == null || !enabled) {
            fail("Notification subscription unavailable; retrying")
            return
        }
        enqueue(Operation({ g ->
            try {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
            } catch (_: SecurityException) { false }
        }, done = done, description="Subscribe ${characteristic.uuid}"))
    }

    private fun receive(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        when (characteristic.uuid) {
            LIVE_UUID -> SensorSample.decode(bytes)?.let(onSample)
            CONTROL_UUID -> RecorderStatus.decode(bytes)?.let(onRecorder)
            DATA_UUID -> onChunk(FlashChunk.decode(bytes))
            BATTERY_UUID -> onBattery(bytes.firstOrNull()?.toInt()?.and(255)?.takeIf { it <= 100 })
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) = dispatch(g) {
            if(status==BluetoothGatt.GATT_SUCCESS) onRssi(rssi)
            if(current?.rssi==true) finish(status==BluetoothGatt.GATT_SUCCESS)
        }
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) = dispatch(g) {
            Log.i("OpenSkiBLE","${g.device.address}: connection status=$status state=$state")
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Connection failed ($status)"); return@dispatch }
            if (state == BluetoothProfile.STATE_CONNECTED) {
                handler.removeCallbacks(timeout)
                onStatus("Connected; discovering service")
                enqueue(Operation({ try { it.discoverServices() } catch (_: SecurityException) { false } }))
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) fail("Disconnected")
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = dispatch(g) {
            finish(status == BluetoothGatt.GATT_SUCCESS)
            if (gatt !== g) return@dispatch
            val service = g.getService(SERVICE_UUID)
            val live = service?.getCharacteristic(LIVE_UUID)
            if (live == null) { fail("OpenSki service not found"); return@dispatch }
            control = service.getCharacteristic(CONTROL_UUID)
            val data = service.getCharacteristic(DATA_UUID)
            val hasRecorder = control != null && data != null
            if (!hasRecorder) control = null
            onBattery(null)
            // Queue every subscription before announcing readiness.
            if (hasRecorder) {
                subscribe(g, control!!)
                if (gatt !== g) return@dispatch
                subscribe(g, data!!)
                if (gatt !== g) return@dispatch
            }
            val battery = g.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_UUID)
            if (battery != null) enqueue(Operation({ try { it.readCharacteristic(battery) } catch (_: SecurityException) { false } }, optional = true))
            // Negotiate before readiness so recorder commands cannot overtake MTU negotiation.
            enqueue(Operation({ try { it.requestMtu(247) } catch (_: SecurityException) { false } }, optional = true,mtu=true,description="MTU negotiation"))
            subscribe(g, live) {
                ready = true
                onStatus("Live stream ready")
                onReady(hasRecorder)
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = dispatch(g) {
            finish(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = dispatch(g) {
            finish(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = dispatch(g) {
            Log.i("OpenSkiBLE","${g.device.address}: MTU=$mtu status=$status")
            // Android can report an MTU change independently of our queued request.
            // It must not complete service discovery or a descriptor/characteristic write.
            if(current?.mtu==true) finish(status==BluetoothGatt.GATT_SUCCESS)
        }
        @Deprecated("Required below API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = dispatch(g) {
            @Suppress("DEPRECATION")
            if (status == BluetoothGatt.GATT_SUCCESS) receive(c, c.value ?: byteArrayOf())
            finish(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = dispatch(g) {
            if (status == BluetoothGatt.GATT_SUCCESS) receive(c, value)
            finish(status == BluetoothGatt.GATT_SUCCESS)
        }
        @Deprecated("Required below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = c.value?.copyOf() ?: return
            dispatch(g) { receive(c, bytes) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val bytes = value.copyOf()
            dispatch(g) { receive(c, bytes) }
        }
    }
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("b1e7a100-3c31-4d59-a2c8-1e9f2f810001")
        private val LIVE_UUID = UUID.fromString("b1e7a100-3c31-4d59-a2c8-1e9f2f810002")
        private val CONTROL_UUID = UUID.fromString("b1e7a100-3c31-4d59-a2c8-1e9f2f810004")
        private val DATA_UUID = UUID.fromString("b1e7a100-3c31-4d59-a2c8-1e9f2f810005")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }
}
