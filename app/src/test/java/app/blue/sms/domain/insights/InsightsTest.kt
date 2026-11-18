package app.blue.sms.domain.insights

import app.blue.sms.data.db.ReviewItem
import app.blue.sms.data.db.TransactionEntity
import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class InsightsTest {
    private fun item(
        paise: Long?, dir: Direction = Direction.OUT, status: TxnStatus = TxnStatus.CONFIRMED,
        cat: Long? = 1, catName: String? = "Food & dining", merchant: String = "ZOMATO",
        method: PaymentMethod = PaymentMethod.UPI, bank: String? = "Federal Bank", type: AccountType? = AccountType.SAVINGS, last4: String? = "4321",
    ) = ReviewItem(
        TransactionEntity(
            smsId = null, amountPaise = paise, direction = dir, merchantRaw = merchant, upiId = null, accountId = null,
            method = method, refNo = null, txnAt = 0, categoryId = cat, forWhomId = 1, notes = null, status = status,
            extractedBy = null, createdAt = 0,
        ),
        null, null, null, bank, last4, type, catName, "Me", null, null, null,
    )

    @Test fun `totals include pending and skipped but not duplicates or non-transactions`() {
        val items = listOf(
            item(10000), item(2000, status = TxnStatus.PENDING), item(3000, status = TxnStatus.SKIPPED),
            item(99900, status = TxnStatus.DUPLICATE), item(55500, status = TxnStatus.NOT_A_TRANSACTION),
            item(50000, dir = Direction.IN),
        )
        val r = Insights.compute(items, null, Direction.OUT)
        assertEquals(15000L, r.totals.outPaise)
        assertEquals(50000L, r.totals.inPaise)
        assertEquals(2, r.unconfirmed)
    }

    @Test fun `entries without an amount are counted separately`() {
        val r = Insights.compute(listOf(item(null), item(500)), null, Direction.OUT)
        assertEquals(500L, r.totals.outPaise)
        assertEquals(1, r.missingAmount)
    }

    @Test fun `category rows compare with the previous period`() {
        val r = Insights.compute(listOf(item(1000), item(500)), listOf(item(700)), Direction.OUT)
        val food = r.byCategory.single()
        assertEquals(1500L, food.amountPaise)
        assertEquals(700L, food.previousPaise)
        assertEquals(1L, food.categoryId)
    }

    @Test fun `uncategorised pending entries get their own row`() {
        val r = Insights.compute(listOf(item(100, cat = null, catName = null, status = TxnStatus.PENDING)), null, Direction.OUT)
        assertEquals("Uncategorised", r.byCategory.single().label)
        assertEquals(Insights.UNCATEGORISED, r.byCategory.single().categoryId)
    }

    @Test fun `payment method rows name the bank or card`() {
        val r = Insights.compute(
            listOf(item(100), item(200, method = PaymentMethod.CARD, bank = "HDFC Bank", type = AccountType.CREDIT_CARD, last4 = "1234")),
            null, Direction.OUT,
        )
        assertEquals(listOf("HDFC credit card", "Federal Bank"), r.byMethod.map { it.label })
    }

    @Test fun `one card spelled two ways is one row`() {
        val r = Insights.compute(
            listOf(
                item(100, method = PaymentMethod.CARD, bank = "HDFC Bank", type = AccountType.CREDIT_CARD, last4 = "1234"),
                item(200, method = PaymentMethod.CARD, bank = "HDFC", type = AccountType.CREDIT_CARD, last4 = "1234"),
            ),
            null, Direction.OUT,
        )
        assertEquals(300L, r.byMethod.single().amountPaise)
    }
}
