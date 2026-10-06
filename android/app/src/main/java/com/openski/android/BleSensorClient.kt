package com.openski.android

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID

class BleSensorClient(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onSample: (SensorSample) -> Unit,
) {
    private var gatt: BluetoothGatt? = null

    fun connect(device: BluetoothDevice) {
        disconnect()
        onStatus("Connecting to ${device.name ?: device.address}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        val connection = gatt
        gatt = null
        connection?.close()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (this@BleSensorClient.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("Connection failed ($status)")
                this@BleSensorClient.gatt = null
                gatt.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus("Connected; discovering service")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onStatus("Disconnected")
                this@BleSensorClient.gatt = null
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(LIVE_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                onStatus("OpenSki service not found")
                return
            }
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                onStatus("Could not enable live notifications")
                return
            }
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                onStatus("Live notification descriptor missing")
                return
            }
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!gatt.writeDescriptor(cccd)) onStatus("Could not subscribe to live data")
        }

        @Deprecated("Legacy callback required on Android versions below API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            receive(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = receive(value)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                onStatus(if (status == BluetoothGatt.GATT_SUCCESS) "Live stream ready" else "Subscription failed ($status)")
            }
        }
    }

    private fun receive(value: ByteArray?) {
        value?.let(SensorSample::decode)?.let(onSample)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("b1e7a100-3c31-4d59-a2c8-1e9f2f810001")
        private val LIVE_UUID: UUID = UUID.fromString("b1e7a100-3c31-4d59-a2c8-1e9f2f810002")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
