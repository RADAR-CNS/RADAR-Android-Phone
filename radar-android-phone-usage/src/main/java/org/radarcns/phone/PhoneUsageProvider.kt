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

import android.os.Build
import org.radarbase.android.device.BaseDeviceState
import org.radarbase.android.device.DeviceServiceProvider
import org.radarcns.phone.usage.BuildConfig
import org.radarcns.phone.usage.PhoneUsageService
import org.radarcns.phone.usage.R

class PhoneUsageProvider : DeviceServiceProvider<BaseDeviceState>() {

    override val description: String?
        get() = radarService!!.getString(R.string.phone_usage_description)

    override val serviceClass: Class<PhoneUsageService> = PhoneUsageService::class.java

    override val displayName: String
        get() = radarService!!.getString(R.string.phoneUsageServiceDisplayName)

    override val permissionsNeeded: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            listOf(android.Manifest.permission.PACKAGE_USAGE_STATS)
        } else {
            emptyList()
        }

    override val sourceProducer: String = "ANDROID"
    override val sourceModel: String = "PHONE"

    override val version: String = BuildConfig.VERSION_NAME

    override val isDisplayable: Boolean = false
}
