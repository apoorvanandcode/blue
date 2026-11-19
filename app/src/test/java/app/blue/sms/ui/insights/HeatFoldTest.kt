package app.blue.sms.ui.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatFoldTest {
    @Test fun `stops once the top rows cover 90 percent`() {
        // 50 + 30 + 12 = 92% after three rows
        assertEquals(3, HeatFold.shownCount(listOf(50, 30, 12, 4, 2, 1, 1)))
    }

    @Test fun `never more than five rows`() {
        assertEquals(5, HeatFold.shownCount(List(12) { 10L }))
    }

    @Test fun `short lists show everything`() {
        assertEquals(2, HeatFold.shownCount(listOf(60, 40)))
        assertEquals(3, HeatFold.shownCount(listOf(40, 35, 25)))   // 90% is only reached at the last row
    }

    @Test fun `heat fades with share and tiny rows stay plain`() {
        val top = HeatFold.alpha(0.38f, 0.38f)
        val mid = HeatFold.alpha(0.10f, 0.38f)
        assertTrue(top > mid && mid > 0f)
        assertEquals(0f, HeatFold.alpha(0.01f, 0.38f))
        assertTrue(top <= 0.20f)
    }
}
