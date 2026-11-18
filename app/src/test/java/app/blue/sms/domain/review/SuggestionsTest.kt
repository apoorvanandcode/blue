package app.blue.sms.domain.review

import app.blue.sms.data.db.ReviewItem
import app.blue.sms.data.db.TransactionEntity
import app.blue.sms.domain.model.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {
    private val me = 1L
    private val refund = 18L

    private fun item(
        categoryId: Long? = null, forWhomId: Long? = null, isRefund: Boolean = false,
        memCat: Long? = null, memFor: Long? = null, memCatActive: Boolean? = memCat?.let { true },
    ) = ReviewItem(
        TransactionEntity(
            smsId = 1, amountPaise = 100, direction = null, merchantRaw = "ATRIA CONVER", upiId = null, accountId = null,
            method = null, refNo = null, txnAt = 0, categoryId = categoryId, forWhomId = forWhomId, notes = null,
            status = TxnStatus.PENDING, extractedBy = null, isRefund = isRefund, createdAt = 0,
        ),
        null, null, null, null, null, null, null, null, memCat, memFor, memCatActive,
    )

    @Test fun `new merchant gets no category and Me`() {
        val s = Suggestions.suggest(item(), me, refund)
        assertNull(s.categoryId)
        assertEquals(me, s.forWhomId)
    }

    @Test fun `remembered merchant pre-fills both`() {
        val s = Suggestions.suggest(item(memCat = 5, memFor = 7), me, refund)
        assertEquals(5L, s.categoryId)
        assertEquals(7L, s.forWhomId)
        assertTrue(s.categoryFromMemory)
        assertTrue(s.forWhomFromMemory)
    }

    @Test fun `card's own choice beats memory`() {
        val s = Suggestions.suggest(item(categoryId = 3, forWhomId = 4, memCat = 5, memFor = 7), me, refund)
        assertEquals(3L, s.categoryId)
        assertEquals(4L, s.forWhomId)
        assertFalse(s.categoryFromMemory)
    }

    @Test fun `hidden category in memory is not suggested`() {
        val s = Suggestions.suggest(item(memCat = 5, memFor = 7, memCatActive = false), me, refund)
        assertNull(s.categoryId)
        assertEquals(7L, s.forWhomId)
    }

    @Test fun `refund defaults to Refund category`() {
        assertEquals(refund, Suggestions.suggest(item(isRefund = true), me, refund).categoryId)
    }
}
