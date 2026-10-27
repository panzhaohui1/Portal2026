package moe.fuqiuluo.xposed

import moe.fuqiuluo.xposed.utils.LocationSanitizationGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSanitizationGuardTest {
    @Test
    fun `nested construction remains guarded until the outer operation exits`() {
        assertFalse(LocationSanitizationGuard.isActive())
        LocationSanitizationGuard.withSanitization {
            assertTrue(LocationSanitizationGuard.isActive())
            LocationSanitizationGuard.withSanitization {
                assertTrue(LocationSanitizationGuard.isActive())
            }
            assertTrue(LocationSanitizationGuard.isActive())
        }
        assertFalse(LocationSanitizationGuard.isActive())
    }

    @Test
    fun `guard is released after an exception`() {
        assertThrows(IllegalStateException::class.java) {
            LocationSanitizationGuard.withSanitization { error("test failure") }
        }
        assertFalse(LocationSanitizationGuard.isActive())
    }
}
