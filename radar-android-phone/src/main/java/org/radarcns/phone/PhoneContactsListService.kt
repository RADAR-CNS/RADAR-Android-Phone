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

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.device.BaseDeviceState
import org.radarbase.android.device.DeviceManager
import org.radarbase.android.device.DeviceService

import java.util.concurrent.TimeUnit

class PhoneContactsListService : DeviceService<BaseDeviceState>() {

    override val defaultState: BaseDeviceState
        get() = BaseDeviceState()

    override val isBluetoothConnectionRequired: Boolean = false

    override fun createDeviceManager(): PhoneContactListManager {
        return PhoneContactListManager(this)
    }

    override fun configureDeviceManager(manager: DeviceManager<BaseDeviceState>, configuration: RadarConfiguration) {
        val phoneManager = manager as PhoneContactListManager
        phoneManager.setCheckInterval(
                configuration.getLong(PHONE_CONTACTS_LIST_INTERVAL, PHONE_CONTACTS_LIST_INTERVAL_DEFAULT),
                TimeUnit.SECONDS)
    }

    companion object {
        private const val PHONE_CONTACTS_LIST_INTERVAL = "phone_contacts_list_interval_seconds"
        val PHONE_CONTACTS_LIST_INTERVAL_DEFAULT = TimeUnit.DAYS.toSeconds(1)
    }
}
