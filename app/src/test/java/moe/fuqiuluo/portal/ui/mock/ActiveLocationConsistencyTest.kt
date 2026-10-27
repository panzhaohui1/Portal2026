package moe.fuqiuluo.portal.ui.mock

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveLocationConsistencyTest {
    @Test
    fun `matching fixed coordinates remain matched despite tiny rounding`() {
        assertEquals(
            ActiveLocationConsistency.MATCH,
            ActiveLocationConsistency.compare(30.0 to 120.0, 30.00000001 to 120.0),
        )
    }

    @Test
    fun `different running coordinates are flagged`() {
        assertEquals(
            ActiveLocationConsistency.MISMATCH,
            ActiveLocationConsistency.compare(30.0 to 120.0, 30.0003 to 120.0),
        )
    }

    @Test
    fun `missing or invalid coordinates cannot be reported as matched`() {
        assertEquals(ActiveLocationConsistency.UNAVAILABLE, ActiveLocationConsistency.compare(null, 30.0 to 120.0))
        assertEquals(ActiveLocationConsistency.UNAVAILABLE, ActiveLocationConsistency.compare(30.0 to 120.0, null))
        assertEquals(ActiveLocationConsistency.UNAVAILABLE, ActiveLocationConsistency.compare(30.0 to 120.0, Double.NaN to 120.0))
        assertEquals(ActiveLocationConsistency.UNAVAILABLE, ActiveLocationConsistency.compare(30.0 to 120.0, 91.0 to 120.0))
    }
}
