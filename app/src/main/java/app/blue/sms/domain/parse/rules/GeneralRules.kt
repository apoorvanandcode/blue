package app.blue.sms.domain.parse.rules

import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.parse.Merchants
import app.blue.sms.domain.parse.Money
import app.blue.sms.domain.parse.ParsedSms
import app.blue.sms.domain.parse.SmsInput
import app.blue.sms.domain.sms.SenderInfo

/** Formats from non-bank senders (shops, telecoms). Checked after bank rules, before deliveries. */
object GeneralRules {
    private fun rx(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Bills and statements: out of scope for v1. */
    private val bill = rx("""total due|amount due|bill (?:dated|generated)|statement (?:dated|generated)""")
    private val refundInitiated = rx("""refund .{0,60}?\b(?:initiated|being processed|will be (?:credited|processed))""")
    private val refundCredited = rx("""refund of $AMT.*?\b(?:was |has been |is )?(?:credited|processed|completed) (?:to|in)""")
    /** "... - Ownly" or "-Team Eshopbox" at the end of the SMS. */
    private val signature = Regex("""(?:-|–)\s*(?:team\s+)?([A-Za-z][\w .&]{1,30}?)\s*\.?\s*\z""", RegexOption.IGNORE_CASE)

    fun classify(sms: SmsInput, sender: SenderInfo): ParsedSms? {
        val body = sms.body
        if (bill.containsMatchIn(body)) return ParsedSms(SmsClass.IGNORE, "general.bill")
        if (refundInitiated.containsMatchIn(body)) return ParsedSms(SmsClass.IGNORE, "general.refund_initiated")
        refundCredited.find(body)?.let { m ->
            val brand = signature.find(body.trim())?.groupValues?.get(1) ?: sender.header
            return ParsedSms(
                smsClass = SmsClass.TRANSACTION,
                ruleId = "general.refund_credited",
                amountPaise = Money.toPaise((m.groups as? MatchNamedGroupCollection)?.get("amt")?.value),
                direction = Direction.IN,
                method = PaymentMethod.OTHER,
                merchant = Merchants.clean(brand),
                txnAt = sms.receivedAt,
                isRefund = true,
            )
        }
        return null
    }
}
