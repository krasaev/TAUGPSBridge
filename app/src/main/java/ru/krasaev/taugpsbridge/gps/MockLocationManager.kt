package ru.krasaev.taugpsbridge.gps

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import ru.krasaev.taugpsbridge.model.GpsData

class MockLocationManager(private val context: Context) {

    companion object {
        private const val TAG = "MockLocationManager"
        const val PROVIDER_NAME = LocationManager.GPS_PROVIDER
    }

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var isProviderAdded = false
    var isEnabled: Boolean = false
        private set

    @Suppress("DEPRECATION", "WrongConstant")
    fun startMock(): Result<Unit> {
        if (locationManager == null) {
            return Result.failure(IllegalStateException("LocationManager is null"))
        }

        return try {
            try {
                // In case it was previously added and not removed
                locationManager.removeTestProvider(PROVIDER_NAME)
            } catch (e: Exception) {
                // Ignore if it wasn't registered
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    PROVIDER_NAME,
                    false, // requiresNetwork
                    false, // requiresSatellite
                    false, // requiresCell
                    false, // hasMonetaryCost
                    true,  // supportsAltitude
                    true,  // supportsSpeed
                    true,  // supportsBearing
                    android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                    android.location.provider.ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    PROVIDER_NAME,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
            }

            locationManager.setTestProviderEnabled(PROVIDER_NAME, true)
            isProviderAdded = true
            isEnabled = true
            Log.i(TAG, "Mock location test provider initialized successfully")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Mock location permission not enabled in Developer options", e)
            isProviderAdded = false
            isEnabled = false
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mock location provider", e)
            isProviderAdded = false
            isEnabled = false
            Result.failure(e)
        }
    }

    fun pushLocation(gpsData: GpsData): Result<Unit> {
        if (!isEnabled || !isProviderAdded || locationManager == null) {
            return Result.failure(IllegalStateException("Mock provider not started"))
        }

        val lat = gpsData.latitude
        val lon = gpsData.longitude
        if (lat == null || lon == null) {
            return Result.failure(IllegalArgumentException("Coordinates are null"))
        }

        return try {
            val location = Location(PROVIDER_NAME).apply {
                latitude = lat
                longitude = lon
                altitude = gpsData.altitudeMeters ?: 0.0
                speed = (gpsData.speedMps ?: 0.0).toFloat()
                bearing = gpsData.bearingDegrees ?: 0.0f
                accuracy = gpsData.accuracyMeters ?: 3.0f
                time = if (gpsData.timestampUtcMillis > 0) gpsData.timestampUtcMillis else System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 5.0f
                    speedAccuracyMetersPerSecond = 0.5f
                    verticalAccuracyMeters = (gpsData.vdop ?: 1.0f) * 3.0f
                }
            }

            locationManager.setTestProviderLocation(PROVIDER_NAME, location)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while pushing mock location", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing mock location", e)
            Result.failure(e)
        }
    }

    fun stopMock() {
        if (isProviderAdded && locationManager != null) {
            try {
                locationManager.setTestProviderEnabled(PROVIDER_NAME, false)
                locationManager.removeTestProvider(PROVIDER_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing test provider", e)
            } finally {
                isProviderAdded = false
                isEnabled = false
            }
        }
    }
}
