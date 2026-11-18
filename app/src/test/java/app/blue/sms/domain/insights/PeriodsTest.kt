package app.blue.sms.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PeriodsTest {
    private val today = LocalDate.of(2026, 11, 17) // a Tuesday

    @Test fun `month in progress compares with the same days of last month`() {
        val p = Period.of(PeriodKind.MONTH, today)
        assertEquals(LocalDate.of(2026, 11, 1), p.start)
        assertEquals(LocalDate.of(2026, 11, 30), p.end)
        val prev = p.previous(today)!!
        assertEquals(LocalDate.of(2026, 10, 1), prev.start)
        assertEquals(LocalDate.of(2026, 10, 17), prev.end)
        assertEquals(p.soFar(today).days, prev.days)
    }

    @Test fun `finished month compares with the whole previous month`() {
        val prev = Period.of(PeriodKind.MONTH, LocalDate.of(2026, 3, 10)).previous(today)!!
        assertEquals(LocalDate.of(2026, 2, 1), prev.start)
        assertEquals(LocalDate.of(2026, 2, 28), prev.end)
    }

    @Test fun `31st day is clipped to a shorter previous month`() {
        val prev = Period.of(PeriodKind.MONTH, LocalDate.of(2026, 3, 31)).previous(LocalDate.of(2026, 3, 31))!!
        assertEquals(LocalDate.of(2026, 2, 28), prev.end)
    }

    @Test fun `week starts Monday and compares like for like`() {
        val p = Period.of(PeriodKind.WEEK, today)
        assertEquals(LocalDate.of(2026, 11, 16), p.start)
        val prev = p.previous(today)!!
        assertEquals(LocalDate.of(2026, 11, 9), prev.start)
        assertEquals(LocalDate.of(2026, 11, 10), prev.end)
    }

    @Test fun `today compares with yesterday`() {
        val prev = Period.of(PeriodKind.TODAY, today).previous(today)!!
        assertEquals(today.minusDays(1), prev.start)
        assertEquals(today.minusDays(1), prev.end)
    }

    @Test fun `custom range compares with the same number of days before it`() {
        val p = Period(PeriodKind.CUSTOM, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 14))
        val prev = p.previous(today)!!
        assertEquals(LocalDate.of(2026, 10, 26), prev.start)
        assertEquals(LocalDate.of(2026, 11, 4), prev.end)
        assertEquals(p.days, prev.days)
    }

    @Test fun `all time has no comparison`() {
        assertNull(Period.of(PeriodKind.ALL, today).previous(today))
    }

    @Test fun `arrows step whole periods`() {
        assertEquals(LocalDate.of(2026, 10, 1), Period.of(PeriodKind.MONTH, today).shift(-1).start)
        assertEquals(LocalDate.of(2026, 10, 31), Period.of(PeriodKind.MONTH, today).shift(-1).end)
    }
}
