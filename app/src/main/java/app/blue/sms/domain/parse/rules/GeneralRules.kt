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
    // The user's grey-area decisions (2026-11-21), matching "YOUR DECISIONS" in the Gemini prompt.
    /** Shop refunds sent in stages: only the final "successfully processed ... by now" message is money in. */
    private val refundFinal = rx("""refund of $AMT .{0,120}?has been successfully processed and shall reflect in your account by now""")
    /** Money moved into an app wallet: the bank's debit SMS records it. */
    private val walletTopUp = rx("""wallet recharge of $AMT is successful|wallet has been top-?up with""")
    /** Paying from an app wallet or gift card: already counted when the wallet was loaded. */
    private val walletSpend = rx("""payment of $AMT (?:using .{1,40}?gift card|from .{1,40}?money balance) is successful""")
    private val paytmBalance = rx("""paid (?:rs\.?|inr|₹)\s*[\d,.]+ to .{1,60}? from paytm balance""")
    /** Cashback, rewards or points added to an app: not money in the bank. */
    private val cashback = rx("""added to \S+(?: \S+)? money\b|you got (?:rs\.?|₹)\s*[\d,.]+ \S+ cashback|cashback points""")
    /** Paytm Deals receipts: the bank or UPI SMS records the payment. */
    private val paytmDeals = rx("""paid successfully to .{1,60}? via paytm deals|(?:rs\.?|inr|₹)\s*[\d,.]+ paid to paytm deals|paid (?:rs\.?|inr|₹)\s*[\d,.]+ to paytm deals""")
    /** "Transfer Received / Credited ... bit.ly" from senders that aren't banks: bait. */
    private val baitLink = rx("""\b(?:bit\.ly|tinyurl\.com|cutt\.ly|rb\.gy|is\.gd|t\.ly)/""")
    private val baitWords = rx("""transfer received|amount available in your ?wallet|credited:\s*(?:rs|inr|₹)""")

    /** "... - Ownly" or "-Team Eshopbox" at the end of the SMS. */
    private val signature = Regex("""(?:-|–)\s*(?:team\s+)?([A-Za-z][\w .&]{1,30}?)\s*\.?\s*\z""", RegexOption.IGNORE_CASE)

    fun classify(sms: SmsInput, sender: SenderInfo): ParsedSms? {
        val body = sms.body
        if (bill.containsMatchIn(body)) return ParsedSms(SmsClass.IGNORE, "general.bill")
        if (baitLink.containsMatchIn(body) && baitWords.containsMatchIn(body)) return ParsedSms(SmsClass.SPAM, "general.bait_credit")
        if (walletTopUp.containsMatchIn(body)) return ParsedSms(SmsClass.IGNORE, "general.wallet_topup")
        if (walletSpend.containsMatchIn(body) || paytmBalance.containsMatchIn(body)) return ParsedSms(SmsClass.IGNORE, "general.wallet_spend")
        if (paytmDeals.containsMatchIn(body)) return ParsedSms(SmsClass.IGNORE, "general.paytm_deals")
        if (cashback.containsMatchIn(body)) return ParsedSms(SmsClass.IGNORE, "general.cashback")
        refundFinal.find(body)?.let { m -> return refund(sms, sender, m, "general.refund_final") }
        if (refundInitiated.containsMatchIn(body)) return ParsedSms(SmsClass.IGNORE, "general.refund_initiated")
        refundCredited.find(body)?.let { m -> return refund(sms, sender, m, "general.refund_credited") }
        return null
    }

    private fun refund(sms: SmsInput, sender: SenderInfo, m: MatchResult, ruleId: String): ParsedSms {
        val brand = signature.find(sms.body.trim())?.groupValues?.get(1) ?: sender.header
        return ParsedSms(
            smsClass = SmsClass.TRANSACTION,
            ruleId = ruleId,
            amountPaise = Money.toPaise((m.groups as? MatchNamedGroupCollection)?.get("amt")?.value),
            direction = Direction.IN,
            method = PaymentMethod.OTHER,
            merchant = Merchants.clean(brand),
            txnAt = sms.receivedAt,
            isRefund = true,
        )
    }
}
