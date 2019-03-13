/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.phone

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.radarbase.android.data.DataCache
import org.radarbase.android.device.AbstractDeviceManager
import org.radarbase.android.device.BaseDeviceState
import org.radarbase.android.device.DeviceStatusListener
import org.radarbase.android.util.OfflineProcessor
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.phone.PhoneBluetoothDevices
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class PhoneBluetoothManager(service: PhoneBluetoothService) : AbstractDeviceManager<PhoneBluetoothService, BaseDeviceState>(service), Runnable {
    private val processor: OfflineProcessor
    private val bluetoothDevicesTopic: DataCache<ObservationKey, PhoneBluetoothDevices> = createCache("android_phone_bluetooth_devices", PhoneBluetoothDevices::class.java)
    private var bluetoothBroadcastReceiver: BroadcastReceiver? = null

    init {
        processor = OfflineProcessor(service) {
            requestCode = SCAN_DEVICES_REQUEST_CODE
            requestName = ACTION_SCAN_DEVICES
            interval(PhoneBluetoothService.BLUETOOTH_DEVICES_SCAN_INTERVAL_DEFAULT, TimeUnit.SECONDS)
            wake = true
        }
    }

    override fun start(acceptableIds: Set<String>) {
        updateStatus(DeviceStatusListener.Status.READY)
        service.ensureRegistration(appLocalId, name, emptyMap())
        processor.start()
        updateStatus(DeviceStatusListener.Status.CONNECTED)
    }

    override fun run() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            logger.error("Bluetooth is not available.")
            return
        }
        val wasEnabled = bluetoothAdapter.isEnabled

        if (!wasEnabled) {
            bluetoothAdapter.enable()
        }

        val filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)

        bluetoothBroadcastReceiver = object : BroadcastReceiver() {
            private var numberOfDevices: Int = 0

            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                when (action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        numberOfDevices++
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        service.unregisterReceiver(this)
                        bluetoothBroadcastReceiver = null

                        val bondedDevices = bluetoothAdapter.bondedDevices.size

                        if (!wasEnabled) {
                            bluetoothAdapter.disable()
                        }

                        if (!isClosed) {
                            val now = System.currentTimeMillis() / 1000.0
                            send(bluetoothDevicesTopic,
                                    PhoneBluetoothDevices(now, now, bondedDevices, numberOfDevices, wasEnabled))
                        }
                    }
                }
            }
        }

        service.registerReceiver(bluetoothBroadcastReceiver, filter)
        bluetoothAdapter.startDiscovery()
    }

    @Throws(IOException::class)
    override fun close() {
        processor.close()
        bluetoothBroadcastReceiver?.let {
            try {
                service.unregisterReceiver(it)
            } catch (ex: IllegalStateException) {
                logger.warn("Bluetooth receiver already unregistered in broadcast")
            }
        }
        bluetoothBroadcastReceiver = null
        super.close()
    }

    internal fun setCheckInterval(checkInterval: Long, intervalUnit: TimeUnit) {
        processor.interval(checkInterval, intervalUnit)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PhoneBluetoothManager::class.java)

        private const val SCAN_DEVICES_REQUEST_CODE = 3248902
        private const val ACTION_SCAN_DEVICES = "org.radarcns.phone.PhoneBluetoothManager.ACTION_SCAN_DEVICES"
    }
}
