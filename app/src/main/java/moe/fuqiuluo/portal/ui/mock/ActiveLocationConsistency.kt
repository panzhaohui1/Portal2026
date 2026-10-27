package moe.fuqiuluo.portal.ui.mock

import kotlin.math.abs

/** Result of comparing a saved selection with the active system configuration. */
internal enum class ActiveLocationConsistency {
    MATCH,
    MISMATCH,
    UNAVAILABLE;

    companion object {
        private const val COORDINATE_TOLERANCE = 0.0000001

        fun compare(selected: Pair<Double, Double>?, active: Pair<Double, Double>?): ActiveLocationConsistency {
            fun valid(point: Pair<Double, Double>?): Boolean = point != null &&
                point.first.isFinite() && point.first in -90.0..90.0 &&
                point.second.isFinite() && point.second in -180.0..180.0

            if (!valid(selected) || !valid(active)) return UNAVAILABLE
            return if (abs(selected!!.first - active!!.first) <= COORDINATE_TOLERANCE &&
                abs(selected.second - active.second) <= COORDINATE_TOLERANCE
            ) MATCH else MISMATCH
        }
    }
}
