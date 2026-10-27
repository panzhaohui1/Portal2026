package moe.fuqiuluo.portal.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordConverterTest {
    // Tiananmen, widely published reference values.
    private val wgs = 39.908692 to 116.397477

    @Test
    fun `gcj02 forward matches the published offset`() {
        val (lat, lon) = CoordConverter.wgs84ToGcj02(wgs.first, wgs.second)
        assertEquals(39.910092, lat, 2e-5)
        assertEquals(116.403719, lon, 2e-5)
    }

    @Test
    fun `gcj02 inverse round trips within a centimetre`() {
        val gcj = CoordConverter.wgs84ToGcj02(wgs.first, wgs.second)
        val back = CoordConverter.toWgs84(gcj.first, gcj.second, CoordSystem.GCJ02)
        assertEquals(wgs.first, back.first, 1e-7)
        assertEquals(wgs.second, back.second, 1e-7)
    }

    @Test
    fun `bd09 round trips through gcj02 to wgs84`() {
        val gcj = CoordConverter.wgs84ToGcj02(wgs.first, wgs.second)
        val bd = CoordConverter.gcj02ToBd09(gcj.first, gcj.second)
        val back = CoordConverter.toWgs84(bd.first, bd.second, CoordSystem.BD09)
        assertEquals(wgs.first, back.first, 1e-6)
        assertEquals(wgs.second, back.second, 1e-6)
    }

    @Test
    fun `outside mainland china gcj02 is identical to wgs84`() {
        val tokyo = 35.681236 to 139.767125
        assertEquals(tokyo, CoordConverter.toWgs84(tokyo.first, tokyo.second, CoordSystem.GCJ02))
        assertEquals(0.0 to 0.0, CoordConverter.toWgs84(0.0, 0.0, CoordSystem.GCJ02))
    }

    @Test
    fun `pasted text is parsed latitude first and longitude-first pickers are detected`() {
        assertEquals(39.9087 to 116.3975, CoordinateText.parse("39.9087, 116.3975"))
        assertEquals(39.9087 to 116.3975, CoordinateText.parse("116.3975,39.9087"))
        assertEquals(39.9087 to 116.3975, CoordinateText.parse(" 39.9087，116.3975 "))
        assertEquals(-33.8 to 151.2, CoordinateText.parse("(-33.8 151.2)"))
        assertNull(CoordinateText.parse("39.9"))
        assertNull(CoordinateText.parse("abc, 1"))
        assertNull(CoordinateText.parse("200, 100"))
        assertNull(CoordinateText.parse("NaN, 1"))
    }
}
