package moe.fuqiuluo.portal.ui.mock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HistoricalLocationTest {
    @Test
    fun `legacy unquoted records remain readable`() {
        val location = HistoricalLocation.fromString("Home,Main Street,12.5,-44.25")
        assertEquals(HistoricalLocation("Home", "Main Street", 12.5, -44.25), location)
    }

    @Test
    fun `quoted names and addresses round trip without changing coordinates`() {
        val location = HistoricalLocation("A, \"B\"", "\"North\", block 1", 0.1, -79.123456)
        assertEquals(location, HistoricalLocation.fromString(location.toString()))
    }

    @Test
    fun `malformed or out of range coordinates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            HistoricalLocation.fromString("Home,Street,NaN,20")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistoricalLocation.fromString("Home,Street,91,20")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistoricalLocation.fromString("\"unterminated,Street,10,20")
        }
    }
}
