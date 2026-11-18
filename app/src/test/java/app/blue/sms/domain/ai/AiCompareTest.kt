package app.blue.sms.domain.ai

import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.parse.ParsedSms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class AiCompareTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun p(cls: SmsClass? = SmsClass.TRANSACTION, amt: Long? = 106082, dir: Direction? = Direction.OUT, m: String? = "ATRIA CONVER", at: Long? = 1_000_000, acct: String? = "X4321") =
        ParsedSms(cls, "r", amountPaise = amt, direction = dir, merchant = m, txnAt = at, account = acct)

    @Test fun `identical readings agree`() = assertTrue(AiCompare.compare(p(), p(), zone).isEmpty())

    @Test fun `merchant case, punctuation and shortening don't count`() {
        assertTrue(AiCompare.sameMerchant("APPLE MEDIA SER", "Apple Media Services"))
        assertTrue(AiCompare.sameMerchant("..SALT AND PEPPER _", "Salt and Pepper"))
        assertFalse(AiCompare.sameMerchant("ATRIA CONVER", "ACT Fibernet"))
    }

    @Test fun `each field difference is reported`() {
        val d = AiCompare.compare(p(), p(amt = 106000, m = "Zomato", at = 1_000_000 + 3_600_000, acct = "X1234"), zone)
        assertEquals(listOf("amount", "merchant", "date", "account"), d.map { it.field })
    }

    @Test fun `dates within a minute agree`() = assertTrue(AiCompare.compare(p(), p(at = 1_030_000), zone).isEmpty())

    @Test fun `Gemini calling it something else is one type difference`() {
        val d = AiCompare.compare(p(), p(cls = SmsClass.IGNORE), zone)
        assertEquals(listOf("type"), d.map { it.field })
    }

    @Test fun `summary counts agreement`() {
        val rows = listOf(
            CheckRow(1, "", "", "r", emptyList()),
            CheckRow(2, "", "", "r", listOf(FieldDiff("amount", "a", "b"))),
            CheckRow(3, "", "", "r", emptyList(), failure = "timeout"),
        )
        val s = AiCompare.summarise(rows)
        assertEquals(1, s.agreed); assertEquals(1, s.failed); assertEquals(50, s.agreePct); assertEquals(1, s.byField["amount"])
    }
}
