package app.blue.sms.domain.upcoming

import app.blue.sms.data.db.UpcomingItemEntity
import app.blue.sms.domain.model.UpcomingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcomingMatcherTest {
    private val day = 24L * 60 * 60 * 1000
    private fun item(id: Long, merchant: String, amount: Long?) = UpcomingItemEntity(
        id = id, smsId = id, kind = UpcomingKind.MANDATE, title = merchant, amountPaise = amount, dueAt = null,
        matchKey = UpcomingMatcher.moneyKey(merchant),
    )

    @Test fun `keys use the first word`() {
        assertEquals("money:APPLE", UpcomingMatcher.moneyKey("APPLE MEDIA SER"))
        assertEquals("money:FACEBOOK", UpcomingMatcher.moneyKey("FACEBOOK INDIA ONLIN"))
        assertNull(UpcomingMatcher.moneyKey(null))
    }

    @Test fun `prefix of at least 4 letters matches`() {
        assertTrue(UpcomingMatcher.moneyKeysMatch("money:GRAMMARLYINC", "money:GRAMMARLY"))
        assertTrue(UpcomingMatcher.moneyKeysMatch("money:PYANNOTEAI", "money:PYANNOTEAI"))
        assertFalse(UpcomingMatcher.moneyKeysMatch("money:APPLE", "money:AMAZON"))
        assertFalse(UpcomingMatcher.moneyKeysMatch("money:ABC", "money:ABCDEF"))
    }

    @Test fun `debit within the mandate maximum closes the oldest matching item`() {
        val open = listOf(item(1, "Amazon India", 39900), item(2, "APPLE MEDIA SER", 38900), item(3, "APPLE MEDIA SERVICES", 38900))
        val m = UpcomingMatcher.findMoneyMatch(open, "APPLE MEDIA", 38900, 10 * day) { 5 * day }
        assertEquals(2L, m?.id)
    }

    @Test fun `debit above the maximum does not match`() {
        val open = listOf(item(1, "Amazon India", 39900))
        assertNull(UpcomingMatcher.findMoneyMatch(open, "AMAZON", 50000, 10 * day) { 5 * day })
    }

    @Test fun `debit long before the item's SMS does not match`() {
        val open = listOf(item(1, "APPLE MEDIA SER", 38900))
        assertNull(UpcomingMatcher.findMoneyMatch(open, "APPLE MEDIA", 38900, 2 * day) { 10 * day })
    }

    @Test fun `unknown amounts still match (USD card alerts)`() {
        val open = listOf(item(1, "pyannoteAI", null))
        assertEquals(1L, UpcomingMatcher.findMoneyMatch(open, "PYANNOTEAI", 933443, 10 * day) { 9 * day }?.id)
    }
}
