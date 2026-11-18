package app.blue.sms.domain.ai

import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.parse.SmsInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AiReplyParserTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val received = LocalDateTime.of(2026, 11, 17, 2, 44).atZone(zone).toInstant().toEpochMilli()
    private val sms = SmsInput("AD-FEDBNK-S", "Debited Rs 1060.82 ...", received)

    private val good = """
        {"sms_class":"transaction","amount":106082.5,"currency":"INR","direction":"out","merchant":"ATRIA CONVER",
         "upi_id":null,"account_last4":"4321","account_type":"savings","bank":"Federal Bank","method":"upi",
         "ref_no":"632144056022","txn_datetime":"2026-11-17T02:44","is_refund":false,"upcoming_kind":"none",
         "due_date":null,"delivery_event":"none","title":null,"tracking_ref":null}
    """

    @Test fun `valid reply maps to the same shape the rules produce`() {
        val p = AiReplyParser.parse(good, sms, zone)
        assertEquals(SmsClass.TRANSACTION, p.smsClass)
        assertEquals(10608250L, p.amountPaise)
        assertEquals(Direction.OUT, p.direction)
        assertEquals("X4321", p.account)
        assertEquals(AccountType.SAVINGS, p.accountType)
        assertEquals(PaymentMethod.UPI, p.method)
        assertEquals("ATRIA CONVER", p.merchant)
        assertEquals(received, p.txnAt)
        assertEquals("ai", p.ruleId)
    }

    @Test fun `foreign currency leaves the amount empty`() {
        val p = AiReplyParser.parse(good.replace("\"INR\"", "\"USD\""), sms, zone)
        assertNull(p.amountPaise)
    }

    @Test fun `missing date falls back to when the SMS arrived`() {
        val p = AiReplyParser.parse(good.replace("\"2026-11-17T02:44\"", "null"), sms, zone)
        assertEquals(received, p.txnAt)
    }

    @Test fun `invalid replies are rejected`() {
        for (bad in listOf("not json", "{}", good.replace("\"transaction\"", "\"payment\""), good.replace("106082.5", "-5"))) {
            try {
                AiReplyParser.parse(bad, sms, zone)
                fail("accepted: $bad")
            } catch (e: InvalidAiReply) { /* expected */ }
        }
    }

    @Test fun `schema requires every field`() {
        val required = AiSchema.schema["required"].toString()
        listOf("sms_class", "amount", "merchant", "txn_datetime", "upcoming_kind").forEach { assertTrue(it, required.contains("\"$it\"")) }
    }
}
