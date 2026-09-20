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

        /** Минимальная скорость (м/с), при которой стоит передавать курс. */
        private const val MIN_SPEED_FOR_BEARING = 0.5

        /** Базовая точность по умолчанию (метры), если из модуля ничего не пришло. */
        private const val DEFAULT_ACCURACY = 5.0f
    }

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile
    private var isProviderAdded = false

    @Volatile
    var isEnabled: Boolean = false
        private set

    // ═══════════════════════════════════════════════════════════
    //  Старт / стоп
    // ═══════════════════════════════════════════════════════════

    @Suppress("DEPRECATION", "WrongConstant")
    fun startMock(): Result<Unit> {
        val lm = locationManager
            ?: return Result.failure(IllegalStateException("LocationManager is null"))

        return try {
            // На случай, если провайдер остался с прошлого запуска
            try {
                lm.removeTestProvider(PROVIDER_NAME)
            } catch (_: Exception) {
                // ok — не был зарегистрирован
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lm.addTestProvider(
                    PROVIDER_NAME,
                    /* requiresNetwork = */ false,
                    /* requiresSatellite = */ false,
                    /* requiresCell = */ false,
                    /* hasMonetaryCost = */ false,
                    /* supportsAltitude = */ true,
                    /* supportsSpeed = */ true,
                    /* supportsBearing = */ true,
                    android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                    android.location.provider.ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                lm.addTestProvider(
                    PROVIDER_NAME,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
            }

            lm.setTestProviderEnabled(PROVIDER_NAME, true)
            isProviderAdded = true
            isEnabled = true
            Log.i(TAG, "Mock location test provider initialized")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "Mock location not allowed in Developer options", e)
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

    fun stopMock() {
        val lm = locationManager ?: return
        synchronized(this) {
            if (!isProviderAdded) return
            try {
                lm.setTestProviderEnabled(PROVIDER_NAME, false)
                lm.removeTestProvider(PROVIDER_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing test provider", e)
            } finally {
                isProviderAdded = false
                isEnabled = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Push
    // ═══════════════════════════════════════════════════════════

    @Synchronized
    fun pushLocation(gpsData: GpsData): Result<Unit> {
        val lm = locationManager
            ?: return Result.failure(IllegalStateException("LocationManager is null"))

        if (!isEnabled || !isProviderAdded) {
            return Result.failure(IllegalStateException("Mock provider not started"))
        }

        val lat = gpsData.latitude
        val lon = gpsData.longitude
        if (lat == null || lon == null) {
            return Result.failure(IllegalArgumentException("Coordinates are null"))
        }

        return try {
            val speedMps = (gpsData.speedMps ?: 0.0)
            val bearingToPush = if (speedMps > MIN_SPEED_FOR_BEARING) {
                gpsData.bearingDegrees ?: 0.0f
            } else {
                0.0f
            }

            val accuracy = (gpsData.accuracyMeters ?: DEFAULT_ACCURACY)
                .coerceAtLeast(3.0f)

            val verticalAccuracy = (gpsData.vdop ?: 1.0f) * 5.0f

            val location = Location(PROVIDER_NAME).apply {
                latitude = lat
                longitude = lon
                altitude = gpsData.altitudeMeters ?: 0.0
                speed = speedMps.toFloat()
                bearing = bearingToPush
                this.accuracy = accuracy
                time = if (gpsData.timestampUtcMillis > 0) {
                    gpsData.timestampUtcMillis
                } else {
                    System.currentTimeMillis()
                }
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 10.0f
                    speedAccuracyMetersPerSecond = 0.5f
                    verticalAccuracyMeters = verticalAccuracy
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // 1 секунда неопределённости по монотонному времени
                    elapsedRealtimeUncertaintyNanos = 1_000_000_000.0
                }
            }

            lm.setTestProviderLocation(PROVIDER_NAME, location)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while pushing mock location", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing mock location", e)
            Result.failure(e)
        }
    }
}