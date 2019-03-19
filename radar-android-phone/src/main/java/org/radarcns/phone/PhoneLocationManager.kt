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

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Process
import org.radarbase.android.data.DataCache
import org.radarbase.android.device.AbstractDeviceManager
import org.radarbase.android.device.BaseDeviceState
import org.radarbase.android.device.DeviceStatusListener
import org.radarbase.android.util.BatteryLevelReceiver
import org.radarbase.android.util.SafeHandler
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.phone.LocationProvider
import org.radarcns.passive.phone.PhoneRelativeLocation
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.ThreadLocalRandom

class PhoneLocationManager(context: PhoneLocationService) : AbstractDeviceManager<PhoneLocationService, BaseDeviceState>(context), LocationListener, BatteryLevelReceiver.BatteryLevelListener {
    private val locationTopic: DataCache<ObservationKey, PhoneRelativeLocation> = createCache("android_phone_relative_location", PhoneRelativeLocation::class.java)
    private val locationManager: LocationManager? = service.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
    private val handler: SafeHandler = SafeHandler("PhoneLocation", Process.THREAD_PRIORITY_BACKGROUND)
    private val batteryLevelReceiver: BatteryLevelReceiver = BatteryLevelReceiver(context, this)
    private var latitudeReference: BigDecimal? = null
    private var longitudeReference: BigDecimal? = null
    private var altitudeReference: Double = 0.toDouble()
    private var frequency: Int = 0
    private var batteryLevelMinimum: Float = 0.toFloat()
    private var batteryLevelReduced: Float = 0.toFloat()
    private var gpsInterval: Int = 0
    private var gpsIntervalReduced: Int = 0
    private var networkInterval: Int = 0
    private var networkIntervalReduced: Int = 0
    private var isStarted: Boolean = false

    private val preferences: SharedPreferences
        get() = service.getSharedPreferences(PhoneLocationService::class.java.name, Context.MODE_PRIVATE)

    init {
        this.frequency = FREQUENCY_OFF
        initializeReferences()
        isStarted = false
    }

    private fun initializeReferences() {
        val preferences = preferences
        latitudeReference = preferences.getString(LATITUDE_REFERENCE, null)
                ?.let { BigDecimal(it) }

        longitudeReference = preferences.getString(LONGITUDE_REFERENCE, null)
                ?.let { BigDecimal(it) }

        if (preferences.contains(ALTITUDE_REFERENCE)) {
            try {
                altitudeReference = Double.fromBits(preferences.getLong(ALTITUDE_REFERENCE, 0))
            } catch (ex: ClassCastException) {
                // to fix bug where this was stored as String
                altitudeReference = preferences.getString(ALTITUDE_REFERENCE, "0.0")!!.toDouble()
                preferences.edit()
                        .putLong(ALTITUDE_REFERENCE, altitudeReference.toRawBits())
                        .apply()
            }
        } else {
            altitudeReference = Double.NaN
        }
    }

    override fun start(acceptableIds: Set<String>) {
        if (locationManager == null) {
            return
        }
        register()
        handler.start()

        updateStatus(DeviceStatusListener.Status.READY)

        handler.execute {
            batteryLevelReceiver.register()
            updateStatus(DeviceStatusListener.Status.CONNECTED)
            isStarted = true
        }
    }

    override fun onLocationChanged(location: Location?) {
        if (location == null) {
            return
        }

        val eventTimestamp = location.time / 1000.0
        val timestamp = currentTime

        val provider = when(location.provider) {
            LocationManager.GPS_PROVIDER -> LocationProvider.GPS
            LocationManager.NETWORK_PROVIDER -> LocationProvider.NETWORK
            else -> LocationProvider.OTHER
        }

        // Coordinates in degrees from the first coordinate registered
        val latitude = getRelativeLatitude(location.latitude).normalize()
        val longitude = getRelativeLongitude(location.longitude).normalize()
        val altitude = if (location.hasAltitude()) getRelativeAltitude(location.altitude).normalize() else null
        val accuracy = if (location.hasAccuracy()) location.accuracy.normalize() else null
        val speed = if (location.hasSpeed()) location.speed.normalize() else null
        val bearing = if (location.hasBearing()) location.bearing.normalize() else null

        val value = PhoneRelativeLocation(
                eventTimestamp, timestamp, provider,
                latitude, longitude,
                altitude, accuracy, speed, bearing)
        send(locationTopic, value)

        logger.info("Location: {} {} {} {} {} {} {} {} {}", provider, eventTimestamp, latitude,
                longitude, accuracy, altitude, speed, bearing, timestamp)
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    @SuppressLint("MissingPermission")
    fun setLocationUpdateRate(periodGPS: Long, periodNetwork: Long) {
        handler.executeReentrant {
            if (!isStarted) {
                return@executeReentrant
            }

            // Remove updates, if any
            locationManager!!.removeUpdates(this@PhoneLocationManager)

            // Initialize with last known and start listening
            when {
                periodGPS <= 0 -> logger.info("Location GPS gathering disabled in settings")
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> {
                    onLocationChanged(locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, periodGPS * 1000, 0f, this@PhoneLocationManager)
                    logger.info("Location GPS listener activated and set to a period of {}", periodGPS)
                }
                else -> logger.warn("Location GPS listener not found")
            }

            when {
                periodNetwork <= 0 -> logger.info("Location network gathering disabled in settings")
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> {
                    onLocationChanged(locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, periodNetwork * 1000, 0f, this@PhoneLocationManager)
                    logger.info("Location Network listener activated and set to a period of {}", periodNetwork)
                }
                else -> logger.warn("Location Network listener not found")
            }
        }
    }

    private fun getRelativeLatitude(absoluteLatitude: Double): Double {
        if (absoluteLatitude.isNaN()) {
            return Double.NaN
        }

        val latitude = BigDecimal.valueOf(absoluteLatitude)
        if (latitudeReference == null) {
            // Create reference within 8 degrees of actual latitude
            // corresponds mildly with the UTM zones used to make flat coordinates estimations.
            val reference = ThreadLocalRandom.current().nextDouble(-4.0, 4.0) // interval [-4,4)
            latitudeReference = BigDecimal.valueOf(reference)
                    .also {
                        preferences.edit()
                                .putString(LATITUDE_REFERENCE, it.toString())
                                .apply()
                    }
        }

        return latitude.subtract(latitudeReference).toDouble()
    }

    private fun getRelativeLongitude(absoluteLongitude: Double): Double {
        if (absoluteLongitude.isNaN()) {
            return Double.NaN
        }
        val longitude = BigDecimal.valueOf(absoluteLongitude)
        if (longitudeReference == null) {
            longitudeReference = longitude

            preferences.edit()
                    .putString(LONGITUDE_REFERENCE, longitude.toString())
                    .apply()
        }

        val relativeLongitude = longitude.subtract(longitudeReference).toDouble()

        // Wraparound if relative longitude outside range of valid values [-180,180]
        // assumption: relative longitude in interval [-540,540]
        if (relativeLongitude > 180.0) {
            return relativeLongitude - 360.0
        } else if (relativeLongitude < -180.0) {
            return relativeLongitude + 360.0
        }

        return relativeLongitude
    }

    private fun getRelativeAltitude(absoluteAltitude: Double): Float {
        if (absoluteAltitude.isNaN()) {
            return Float.NaN
        }
        if (altitudeReference.isNaN()) {
            altitudeReference = absoluteAltitude

            preferences.edit()
                    .putLong(ALTITUDE_REFERENCE, absoluteAltitude.toRawBits())
                    .apply()
        }
        return (absoluteAltitude - altitudeReference).toFloat()
    }

    override fun onBatteryLevelChanged(level: Float, isPlugged: Boolean) {
        handler.executeReentrant {
            val useGpsInterval: Long
            val useNetworkInterval: Long

            val newFrequency: Int = if (isPlugged || level >= batteryLevelReduced) {
                FREQUENCY_NORMAL
            } else if (level >= batteryLevelMinimum) {
                FREQUENCY_REDUCED
            } else {
                FREQUENCY_OFF
            }

            if (frequency == newFrequency) {
                return@executeReentrant
            }
            frequency = newFrequency

            if (frequency == FREQUENCY_NORMAL) {
                useGpsInterval = gpsInterval.toLong()
                useNetworkInterval = networkInterval.toLong()
            } else {
                useGpsInterval = gpsIntervalReduced.toLong()
                useNetworkInterval = networkIntervalReduced.toLong()
            }

            if (frequency == FREQUENCY_OFF) {
                locationManager!!.removeUpdates(this@PhoneLocationManager)
            } else {
                setLocationUpdateRate(useGpsInterval, useNetworkInterval)
            }
        }
    }

    @Throws(IOException::class)
    override fun close() {
        locationManager?.let { manager ->
            handler.stop {
                batteryLevelReceiver.unregister()
                manager.removeUpdates(this@PhoneLocationManager)
            }
        }

        super.close()
    }

    fun setBatteryLevels(batteryLevelMinimum: Float, batteryLevelReduced: Float) {
        handler.execute {
            if (this.batteryLevelMinimum == batteryLevelMinimum && this.batteryLevelReduced == batteryLevelReduced) {
                return@execute
            }
            this.batteryLevelMinimum = batteryLevelMinimum
            this.batteryLevelReduced = batteryLevelReduced
            this.onBatteryLevelChanged(batteryLevelReceiver.level, batteryLevelReceiver.isPlugged)
        }
    }

    fun setIntervals(gpsInterval: Int, gpsIntervalReduced: Int, networkInterval: Int, networkIntervalReduced: Int) {
        handler.execute {
            if (this.gpsInterval == gpsInterval
                    && this.gpsIntervalReduced == gpsIntervalReduced
                    && this.networkInterval == networkInterval
                    && this.networkIntervalReduced == networkIntervalReduced) {
                return@execute
            }

            this.gpsInterval = gpsInterval
            this.gpsIntervalReduced = gpsIntervalReduced
            this.networkInterval = networkInterval
            this.networkIntervalReduced = networkIntervalReduced

            // reset intervals
            this.frequency = -1
            this.onBatteryLevelChanged(batteryLevelReceiver.level, batteryLevelReceiver.isPlugged)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PhoneLocationManager::class.java)

        private const val FREQUENCY_OFF = 1
        private const val FREQUENCY_REDUCED = 2
        private const val FREQUENCY_NORMAL = 3

        // storage with keys
        private const val LATITUDE_REFERENCE = "latitude.reference"
        private const val LONGITUDE_REFERENCE = "longitude.reference"
        private const val ALTITUDE_REFERENCE = "altitude.reference"

        private val PROVIDER_TYPES = HashMap<String, LocationProvider>()

        init {
            PROVIDER_TYPES[LocationManager.GPS_PROVIDER] = LocationProvider.GPS
            PROVIDER_TYPES[LocationManager.NETWORK_PROVIDER] = LocationProvider.NETWORK
        }

        /** Replace special float values with regular numbers.  */
        private fun Double.normalize(): Double? {
            return when {
                isNaN() -> null
                isInfinite() && this < 0 -> -1e308
                isInfinite() && this > 0 -> 1e308
                else -> this
            }
        }

        /** Replace special float values with regular numbers.  */
        private fun Float.normalize(): Float? {
            return when {
                isNaN() -> null
                isInfinite() && this < 0 -> -3e38f
                isInfinite() && this > 0 -> 3e38f
                else -> this
            }
        }
    }
}
