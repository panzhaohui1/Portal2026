package moe.fuqiuluo.xposed

import moe.fuqiuluo.xposed.utils.FixedLocationConfig
import moe.fuqiuluo.xposed.utils.FixedLocationPolicy
import moe.fuqiuluo.xposed.utils.FakeLoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedLocationPolicyTest {
    @Test
    fun zeroAccuracyProducesZeroJitterWithoutThrowing() {
        assertEquals(0.0, FixedLocationPolicy.safeJitterDistance(0.0), 0.0)
        assertEquals(0.0, FixedLocationPolicy.safeJitterDistance(-1.0), 0.0)
        assertEquals(0.0, FixedLocationPolicy.safeJitterDistance(Double.NaN), 0.0)

        val location = FakeLoc.jitterLocation(lat = 12.0, lon = 34.0, n = 0.0)
        assertEquals(12.0, location.first, 0.0)
        assertEquals(34.0, location.second, 0.0)
    }

    @Test
    fun invalidCoordinatesAndNumericValuesAreRejected() {
        assertNull(FixedLocationPolicy.validate(91.0, 0.0, 0.0, 0.0f))
        assertNull(FixedLocationPolicy.validate(0.0, 181.0, 0.0, 0.0f))
        assertNull(FixedLocationPolicy.validate(Double.NaN, 0.0, 0.0, 0.0f))
        assertNull(FixedLocationPolicy.validate(0.0, 0.0, Double.POSITIVE_INFINITY, 0.0f))
        assertNull(FixedLocationPolicy.validate(0.0, 0.0, 0.0, Float.NaN))
        assertNull(FixedLocationPolicy.validate(0.0, 0.0, 0.0, -0.1f))
    }

    @Test
    fun confirmationRequiresAcknowledgementAndAllReadbacks() {
        val expected = FixedLocationConfig(10.0, 20.0, 30.0, 0.0f)

        assertFalse(
            FixedLocationPolicy.isConfirmed(
                expected,
                commandAccepted = false,
                acknowledgement = true,
                enabled = true,
                actual = expected,
                readBackCoordinates = 10.0 to 20.0,
            ),
        )
        assertFalse(
            FixedLocationPolicy.isConfirmed(
                expected,
                commandAccepted = true,
                acknowledgement = false,
                enabled = true,
                actual = expected,
                readBackCoordinates = 10.0 to 20.0,
            ),
        )
        assertFalse(
            FixedLocationPolicy.isConfirmed(
                expected,
                commandAccepted = true,
                acknowledgement = true,
                enabled = true,
                fixedMode = false,
                actual = expected,
                readBackCoordinates = 10.0 to 20.0,
            ),
        )
        assertFalse(
            FixedLocationPolicy.isConfirmed(
                expected,
                commandAccepted = true,
                acknowledgement = true,
                enabled = true,
                actual = expected,
                readBackCoordinates = 10.1 to 20.0,
            ),
        )
        assertTrue(
            FixedLocationPolicy.isConfirmed(
                expected,
                commandAccepted = true,
                acknowledgement = true,
                enabled = true,
                actual = expected,
                readBackCoordinates = 10.0 to 20.0,
            ),
        )
    }

    @Test
    fun validZeroAccuracyConfigurationIsRetainedExactly() {
        val config = FixedLocationPolicy.validate(0.0, -0.0, 0.0, 0.0f)
        assertNotNull(config)
        assertEquals(0.0, config!!.latitude, 0.0)
        assertEquals(0.0, config.longitude, 0.0)
        assertEquals(0.0, config.altitude, 0.0)
        assertEquals(0.0f, config.accuracy, 0.0f)
    }
}
