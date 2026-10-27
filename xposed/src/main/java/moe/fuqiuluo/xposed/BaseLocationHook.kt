package moe.fuqiuluo.xposed

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.LocationSanitizationGuard
import moe.fuqiuluo.xposed.utils.Logger
import moe.microbios.nmea.NMEA
import moe.microbios.nmea.NmeaValue
import kotlin.random.Random

abstract class BaseLocationHook: BaseDivineService() {
    /**
     * Replace a location with a value derived only from the configured state.
     * No source timestamp, elapsed time, extras or cached source Location is
     * copied across this boundary.
     */
    @Suppress("UNUSED_PARAMETER")
    fun injectLocation(originLocation: Location, realLocation: Boolean = true): Location {
        if (LocationSanitizationGuard.isActive()) return buildEmergencyLocation()
        if (!FakeLoc.enable) return originLocation
        return LocationSanitizationGuard.withSanitization {
            runCatching {
                buildSanitizedLocation(originLocation)
            }.onFailure {
                // Never fall back to the original location while active.
                Logger.error("build sanitized location failed", it)
            }.getOrElse {
                buildEmergencyLocation()
            }
        }
    }

    private fun buildSanitizedLocation(originLocation: Location): Location {
        val coordinates = FakeLoc.coordinateSnapshot()
        val target = if (FakeLoc.fixedLocationMode) {
            Pair(
                coordinates.latitude.takeIf { it.isFinite() && it in -90.0..90.0 } ?: 0.0,
                coordinates.longitude.takeIf { it.isFinite() && it in -180.0..180.0 } ?: 0.0,
            )
        } else {
            FakeLoc.jitterLocation(coordinates.latitude, coordinates.longitude)
        }
        val accuracy = safeAccuracy()
        val altitude = safeAltitude()
        val speed = safeSpeed()
        val location = Location(safeProvider(originLocation))

        location.latitude = target.first
        location.longitude = target.second
        location.altitude = altitude
        location.accuracy = accuracy
        location.speed = speed
        location.time = System.currentTimeMillis()
        // A stationary receiver reports no heading; a bearing on a zero-speed fix looks synthetic.
        if (!FakeLoc.fixedLocationMode) location.bearing = normalizedBearing()
        location.extras = Bundle()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.speedAccuracyMetersPerSecond = if (FakeLoc.fixedLocationMode) 0.5f else accuracy
            if (!FakeLoc.fixedLocationMode) location.bearingAccuracyDegrees = 3.0f
            location.verticalAccuracyMeters = accuracy
        }
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            location.elapsedRealtimeUncertaintyNanos = 0.0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            location.mslAltitudeMeters = altitude
            location.mslAltitudeAccuracyMeters = accuracy
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock = !FakeLoc.hideMock
        }

        kotlin.runCatching {
            XposedHelpers.callMethod(location, "makeComplete")
        }.onFailure {
            Logger.error("makeComplete failed", it)
        }

        // This cache is safe to share because it is the sanitized value, not
        // the source Location. IPC sync deliberately does not serialize it.
        FakeLoc.lastLocation = Location(location)
        if (FakeLoc.enableDebugLog) {
            Logger.debug("injectLocation success")
        }
        return location
    }

    private fun buildEmergencyLocation(): Location {
        val coordinates = FakeLoc.coordinateSnapshot()
        val location = Location(LocationManager.GPS_PROVIDER)
        location.latitude = coordinates.latitude.takeIf { it.isFinite() && it in -90.0..90.0 } ?: 0.0
        location.longitude = coordinates.longitude.takeIf { it.isFinite() && it in -180.0..180.0 } ?: 0.0
        location.altitude = safeAltitude()
        location.accuracy = safeAccuracy()
        location.speed = if (FakeLoc.fixedLocationMode) 0.0f else safeSpeed()
        location.bearing = normalizedBearing()
        location.time = System.currentTimeMillis()
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        location.extras = Bundle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock = !FakeLoc.hideMock
        }
        FakeLoc.lastLocation = Location(location)
        return location
    }

    private fun safeProvider(originLocation: Location): String {
        val sourceProvider = originLocation.provider?.takeIf { it.isNotBlank() }
        return if (FakeLoc.disableNetworkLocation && sourceProvider == LocationManager.NETWORK_PROVIDER) {
            LocationManager.GPS_PROVIDER
        } else {
            sourceProvider ?: LocationManager.GPS_PROVIDER
        }
    }

    private fun safeAccuracy(): Float {
        return FakeLoc.accuracy.takeIf { it.isFinite() && it >= 0.0f } ?: 0.0f
    }

    private fun safeAltitude(): Double {
        return FakeLoc.altitude.takeIf { it.isFinite() } ?: 0.0
    }

    private fun safeSpeed(): Float {
        val configuredSpeed = FakeLoc.speed.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val configuredAmplitude = FakeLoc.speedAmplitude.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val speed = if (configuredAmplitude == 0.0) {
            configuredSpeed
        } else {
            configuredSpeed + Random.nextDouble(-configuredAmplitude, configuredAmplitude)
        }
        return speed.coerceAtLeast(0.0).toFloat()
    }

    private fun normalizedBearing(): Float {
        val bearing = FakeLoc.bearing.takeIf { it.isFinite() } ?: 0.0
        return (bearing % 360.0).let { if (it < 0.0) it + 360.0 else it }.toFloat()
    }

    fun injectNMEA(nmeaStr: String): String? {
        if (!FakeLoc.enable) {
            return null
        }

        kotlin.runCatching {
            val nmea = NMEA.valueOf(nmeaStr)
            when(val value = nmea.value) {
                is NmeaValue.DTM -> {
                    return null
                }
                is NmeaValue.GGA -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.fixQuality == 0) {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    val absLat = kotlin.math.abs(FakeLoc.latitude)
                    var degree = absLat.toInt()
                    var minute = (absLat - degree) * 60
                    value.latitude = degree * 100 + minute

                    val absLon = kotlin.math.abs(FakeLoc.longitude)
                    degree = absLon.toInt()
                    minute = (absLon - degree) * 60
                    value.longitude = degree * 100 + minute

                    return value.toNmeaString()
                }
                is NmeaValue.GNS -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.mode == "N") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    val absLat = kotlin.math.abs(FakeLoc.latitude)
                    var degree = absLat.toInt()
                    var minute = (absLat - degree) * 60
                    value.latitude = degree * 100 + minute

                    val absLon = kotlin.math.abs(FakeLoc.longitude)
                    degree = absLon.toInt()
                    minute = (absLon - degree) * 60
                    value.longitude = degree * 100 + minute

                    return value.toNmeaString()
                }
                is NmeaValue.GSA -> {
                    return null
                }
                is NmeaValue.GSV -> {
                    return null
                }
                is NmeaValue.RMC -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.status == "V") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    val absLat = kotlin.math.abs(FakeLoc.latitude)
                    var degree = absLat.toInt()
                    var minute = (absLat - degree) * 60
                    value.latitude = degree * 100 + minute

                    val absLon = kotlin.math.abs(FakeLoc.longitude)
                    degree = absLon.toInt()
                    minute = (absLon - degree) * 60
                    value.longitude = degree * 100 + minute

                    return value.toNmeaString()
                }
                is NmeaValue.VTG -> {
                    return null
                }
            }
        }.onFailure {
            Logger.error("NMEA parse failed")
            return null
        }
        return null
    }
}
