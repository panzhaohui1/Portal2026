package moe.fuqiuluo.portal.ext

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Coordinate systems a user may copy coordinates from. Location output is always WGS84. */
enum class CoordSystem {
    /** GPS / Google Maps / Android system location. */
    WGS84,
    /** Amap (Gaode), Tencent Maps and other maps licensed in mainland China. */
    GCJ02,
    /** Baidu Maps. */
    BD09,
}

/**
 * Pure-Kotlin coordinate conversion. Unlike the Baidu native helper this works
 * before the online map SDK is enabled, and the GCJ-02 inverse is iterated so
 * that a round trip stays within a few millimetres instead of the metre-level
 * error of the single-step approximation.
 */
object CoordConverter {
    private const val A = 6378245.0
    private const val EE = 0.006693421622965943
    private const val X_PI = PI * 3000.0 / 180.0

    fun toWgs84(lat: Double, lon: Double, from: CoordSystem): Pair<Double, Double> = when (from) {
        CoordSystem.WGS84 -> lat to lon
        CoordSystem.GCJ02 -> gcj02ToWgs84(lat, lon)
        CoordSystem.BD09 -> bd09ToGcj02(lat, lon).let { gcj02ToWgs84(it.first, it.second) }
    }

    /** GCJ-02 offsets apply only inside mainland China; elsewhere it equals WGS84. */
    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        val (dLat, dLon) = delta(lat, lon)
        return (lat + dLat) to (lon + dLon)
    }

    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var wgsLat = lat
        var wgsLon = lon
        repeat(MAX_ITERATIONS) {
            val (gLat, gLon) = wgs84ToGcj02(wgsLat, wgsLon)
            val errLat = gLat - lat
            val errLon = gLon - lon
            wgsLat -= errLat
            wgsLon -= errLon
            if (abs(errLat) < THRESHOLD && abs(errLon) < THRESHOLD) return wgsLat to wgsLon
        }
        return wgsLat to wgsLon
    }

    fun bd09ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        val x = lon - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        return (z * sin(theta)) to (z * cos(theta))
    }

    fun gcj02ToBd09(lat: Double, lon: Double): Pair<Double, Double> {
        val z = sqrt(lon * lon + lat * lat) + 0.00002 * sin(lat * X_PI)
        val theta = atan2(lat, lon) + 0.000003 * cos(lon * X_PI)
        return (z * sin(theta) + 0.006) to (z * cos(theta) + 0.0065)
    }

    private const val MAX_ITERATIONS = 30
    private const val THRESHOLD = 1e-9

    private fun delta(lat: Double, lon: Double): Pair<Double, Double> {
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return dLat to dLon
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}

/**
 * Parses pasted text such as "39.9087, 116.3975", "116.3975,39.9087" (Amap and
 * Baidu pickers put longitude first) or "39.9087 116.3975". Returns latitude
 * first. Order is swapped only when the first value cannot be a latitude;
 * otherwise the conventional latitude-first order is kept.
 */
object CoordinateText {
    private val separator = Regex("[,，;；\\s]+")

    fun parse(text: String?): Pair<Double, Double>? {
        val parts = text?.trim()?.trim('(', ')', '[', ']')?.split(separator)
            ?.filter { it.isNotBlank() } ?: return null
        if (parts.size != 2) return null
        val a = parts[0].toDoubleOrNull() ?: return null
        val b = parts[1].toDoubleOrNull() ?: return null
        if (!a.isFinite() || !b.isFinite()) return null
        val (lat, lon) = if (abs(a) > 90.0 && abs(b) <= 90.0) b to a else a to b
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }
}
