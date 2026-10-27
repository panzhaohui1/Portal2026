package moe.fuqiuluo.xposed.utils

import kotlin.random.Random

data class FixedLocationConfig(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
)

/** Pure validation and acknowledgement logic shared by the app and Xposed side. */
object FixedLocationPolicy {
    fun validate(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
    ): FixedLocationConfig? {
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        if (!altitude.isFinite()) return null
        if (!accuracy.isFinite() || accuracy < 0.0f) return null

        return FixedLocationConfig(
            latitude = if (latitude == 0.0) 0.0 else latitude,
            longitude = if (longitude == 0.0) 0.0 else longitude,
            altitude = if (altitude == 0.0) 0.0 else altitude,
            accuracy = if (accuracy == 0.0f) 0.0f else accuracy,
        )
    }

    /**
     * Kotlin's bounded random API rejects an empty range on some runtimes.
     * Zero accuracy is a valid fixed-location setting and must produce no
     * perturbation instead of an exception.
     */
    fun safeJitterDistance(accuracy: Double, random: Random = Random.Default): Double {
        if (!accuracy.isFinite() || accuracy <= 0.0) return 0.0
        return random.nextDouble(0.0, accuracy)
    }

    fun matches(
        expected: FixedLocationConfig,
        enabled: Boolean,
        actual: FixedLocationConfig?,
    ): Boolean {
        return enabled && actual == expected
    }

    fun isConfirmed(
        expected: FixedLocationConfig,
        commandAccepted: Boolean,
        acknowledgement: Boolean,
        enabled: Boolean,
        actual: FixedLocationConfig?,
        readBackCoordinates: Pair<Double, Double>?,
        fixedMode: Boolean = true,
    ): Boolean {
        if (!commandAccepted || !acknowledgement) return false
        if (!matches(expected, enabled && fixedMode, actual)) return false
        return readBackCoordinates?.let {
            it.first == expected.latitude && it.second == expected.longitude
        } == true
    }
}
