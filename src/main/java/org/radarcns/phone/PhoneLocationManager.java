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

package org.radarcns.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.support.annotation.NonNull;

import org.radarcns.android.data.DataCache;
import org.radarcns.android.data.TableDataHandler;
import org.radarcns.android.device.AbstractDeviceManager;
import org.radarcns.android.device.BaseDeviceState;
import org.radarcns.android.device.DeviceStatusListener;
import org.radarcns.android.util.BatteryLevelReceiver;
import org.radarcns.key.MeasurementKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

class PhoneLocationManager extends AbstractDeviceManager<PhoneLocationService, BaseDeviceState> implements LocationListener, BatteryLevelReceiver.BatteryLevelListener {
    private static final Logger logger = LoggerFactory.getLogger(PhoneLocationManager.class);

    private enum Frequency {
        OFF, REDUCED, NORMAL
    }

    // storage with keys
    private static final String LATITUDE_REFERENCE = "latitude.reference";
    private static final String LONGITUDE_REFERENCE = "longitude.reference";
    private static final String ALTITUDE_REFERENCE = "altitude.reference";

    // update intervals
    private static final long LOCATION_GPS_INTERVAL_DEFAULT = 60*60; // seconds
    private static final long LOCATION_NETWORK_INTERVAL_DEFAULT = 10*60; // seconds

    private static final float MINIMUM_BATTERY_LEVEL = 0.15f;
    private static final float REDUCED_BATTERY_LEVEL = 0.3f;

    private static final Map<String, LocationProvider> PROVIDER_TYPES = new HashMap<>();

    static {
        PROVIDER_TYPES.put(LocationManager.GPS_PROVIDER, LocationProvider.GPS);
        PROVIDER_TYPES.put(LocationManager.NETWORK_PROVIDER, LocationProvider.NETWORK);
    }

    private final DataCache<MeasurementKey, PhoneRelativeLocation> locationTable;
    private final LocationManager locationManager;
    private final BatteryLevelReceiver batteryLevelReceiver;
    private final SharedPreferences preferences;
    private BigDecimal latitudeReference;
    private BigDecimal longitudeReference;
    private double altitudeReference;
    private final HandlerThread handlerThread;
    private Handler handler;
    private Frequency frequency;

    public PhoneLocationManager(PhoneLocationService context, TableDataHandler dataHandler, String groupId, String sourceId) {
        super(context, new BaseDeviceState(), dataHandler, groupId, sourceId);
        this.locationTable = dataHandler.getCache(PhoneLocationTopics.getInstance().getRelativeLocationTopic());

        locationManager = (LocationManager) getService().getSystemService(Context.LOCATION_SERVICE);
        this.handlerThread = new HandlerThread("PhoneLocation", Process.THREAD_PRIORITY_BACKGROUND);

        batteryLevelReceiver = new BatteryLevelReceiver(context, this);
        this.frequency = Frequency.OFF;
        this.preferences = context.getSharedPreferences(PhoneLocationService.class.getName(), Context.MODE_PRIVATE);

        if (preferences.contains(LATITUDE_REFERENCE)
                && preferences.contains(LONGITUDE_REFERENCE)
                && preferences.contains(ALTITUDE_REFERENCE)) {
            latitudeReference = new BigDecimal(preferences.getString(LATITUDE_REFERENCE, null));
            longitudeReference = new BigDecimal(preferences.getString(LONGITUDE_REFERENCE, null));
            altitudeReference = Double.longBitsToDouble(preferences.getLong(ALTITUDE_REFERENCE, Double.doubleToLongBits(Double.NaN)));
        } else {
            latitudeReference = null;
            longitudeReference = null;
            altitudeReference = Double.NaN;
        }

        setName(android.os.Build.MODEL);
        updateStatus(DeviceStatusListener.Status.READY);
    }

    @Override
    public void start(@NonNull Set<String> set) {
        this.handlerThread.start();
        this.handler = new Handler(this.handlerThread.getLooper());

        batteryLevelReceiver.register();

        // Location
        setLocationUpdateRate(LOCATION_GPS_INTERVAL_DEFAULT, LOCATION_NETWORK_INTERVAL_DEFAULT);
        updateStatus(DeviceStatusListener.Status.CONNECTED);
    }

    public void onLocationChanged(Location location) {
        if (location == null) {
            return;
        }

        double latitude;
        double longitude;
        float altitude;

        if (latitudeReference == null) {
            latitudeReference = BigDecimal.valueOf(location.getLatitude());
            longitudeReference = BigDecimal.valueOf(location.getLongitude());
            altitudeReference = location.hasAltitude() ? getRelativeAltitude(location.getAltitude()) : Double.NaN;
            preferences.edit()
                    .putString(LATITUDE_REFERENCE, latitudeReference.toString())
                    .putString(LONGITUDE_REFERENCE, longitudeReference.toString())
                    .putLong(ALTITUDE_REFERENCE, Double.doubleToLongBits(altitudeReference))
                    .apply();
        }

        // Coordinates in degrees from a new (random) reference point
        latitude = getRelativeLatitude(location.getLatitude());
        longitude = getRelativeLongitude(location.getLongitude());
        altitude = location.hasAltitude() ? getRelativeAltitude(location.getAltitude()) : Float.NaN;

        double eventTimestamp = location.getTime() / 1000d;
        double timestamp = System.currentTimeMillis() / 1000d;

        LocationProvider provider = PROVIDER_TYPES.get(location.getProvider());
        if (provider == null) {
            provider = LocationProvider.OTHER;
        }

        float accuracy = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        float speed = location.hasSpeed() ? location.getSpeed() : Float.NaN;
        float bearing = location.hasBearing() ? location.getBearing() : Float.NaN;

        PhoneRelativeLocation value = new PhoneRelativeLocation(
                eventTimestamp, timestamp, provider,
                latitude, longitude,
                altitude, accuracy, speed, bearing);
        send(locationTable, value);

        logger.info("Location: {} {} {} {} {} {} {} {} {}", provider, eventTimestamp, latitude,
                longitude, accuracy, altitude, speed, bearing, timestamp);
    }

    public void onStatusChanged(String provider, int status, Bundle extras) {}

    public void onProviderEnabled(String provider) {}

    public void onProviderDisabled(String provider) {}

    public synchronized void setLocationUpdateRate(final long periodGPS, final long periodNetwork) {
        handler.post(new Runnable() {
             @Override
             public void run() {
                 // Remove updates, if any
                 locationManager.removeUpdates(PhoneLocationManager.this);

                 // Initialize with last known and start listening
                 if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                     onLocationChanged(locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER));
                     locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, periodGPS * 1000, 0, PhoneLocationManager.this);
                     logger.info("Location GPS listener activated and set to a period of {}", periodGPS);
                 } else {
                     logger.warn("Location GPS listener not found");
                 }

                 if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                     onLocationChanged(locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
                     locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, periodNetwork * 1000, 0, PhoneLocationManager.this);
                     logger.info("Location Network listener activated and set to a period of {}", periodNetwork);
                 } else {
                     logger.warn("Location Network listener not found");
                 }
             }
         });
    }

    private double getRelativeLatitude(double absoluteLatitude) {
        if (Double.isNaN(absoluteLatitude)) {
            return Double.NaN;
        }
        BigDecimal latitude = BigDecimal.valueOf(absoluteLatitude);
        if (latitudeReference == null) {
            latitudeReference = latitude;
            preferences.edit().putString(LATITUDE_REFERENCE, latitude.toString()).apply();
        }
        return latitude.subtract(latitudeReference).doubleValue();
    }

    private double getRelativeLongitude(double absoluteLongitude) {
        if (Double.isNaN(absoluteLongitude)) {
            return Double.NaN;
        }
        BigDecimal longitude = BigDecimal.valueOf(absoluteLongitude);
        if (longitudeReference == null) {
            longitudeReference = longitude;
            preferences.edit().putString(LONGITUDE_REFERENCE, longitude.toString()).apply();
        }
        return longitude.subtract(longitudeReference).doubleValue();
    }

    private float getRelativeAltitude(double absoluteAltitude) {
        if (Double.isNaN(absoluteAltitude)) {
            return Float.NaN;
        }
        if (Double.isNaN(altitudeReference)) {
            altitudeReference = absoluteAltitude;
            preferences.edit().putString(ALTITUDE_REFERENCE, Double.toString(altitudeReference)).apply();
        }
        return (float)(absoluteAltitude - altitudeReference);
    }

    @Override
    public void onBatteryLevelChanged(float level, boolean isPlugged) {
        if (handler == null) {
            return;
        }
        Frequency newFrequency;
        if (isPlugged) {
            newFrequency = Frequency.NORMAL;
        } else if (level < MINIMUM_BATTERY_LEVEL) {
            newFrequency = Frequency.OFF;
        } else if (level < REDUCED_BATTERY_LEVEL) {
            newFrequency = Frequency.REDUCED;
        } else {
            newFrequency = Frequency.NORMAL;
        }

        if (frequency == newFrequency) {
            return;
        }
        frequency = newFrequency;

        switch (frequency) {
            case OFF:
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        locationManager.removeUpdates(PhoneLocationManager.this);
                    }
                });
                break;
            case REDUCED:
                setLocationUpdateRate(LOCATION_GPS_INTERVAL_DEFAULT * 5, LOCATION_NETWORK_INTERVAL_DEFAULT * 5);
                break;
            case NORMAL:
                setLocationUpdateRate(LOCATION_GPS_INTERVAL_DEFAULT, LOCATION_NETWORK_INTERVAL_DEFAULT);
                break;
        }
    }

    @Override
    public void close() throws IOException {
        if (handler != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    locationManager.removeUpdates(PhoneLocationManager.this);
                }
            });
            handler = null;
            handlerThread.quitSafely();
            batteryLevelReceiver.unregister();
        }

        super.close();
    }
}