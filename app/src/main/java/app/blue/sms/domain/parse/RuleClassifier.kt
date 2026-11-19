package app.blue.sms.domain.parse

import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.parse.rules.ALL_BANKS
import app.blue.sms.domain.parse.rules.BankRules
import app.blue.sms.domain.parse.rules.DeliveryRules
import app.blue.sms.domain.parse.rules.GeneralRules
import app.blue.sms.domain.parse.rules.Hit
import app.blue.sms.domain.sms.SenderIds
import app.blue.sms.domain.sms.SenderSuffix
import java.time.ZoneId
import javax.inject.Inject

/**
 * Rules, in order:
 * 1. OTPs and codes -> ignore (never sent anywhere).
 * 2. Sender on the spam list, or a -P (promotional) sender -> spam.
 * 3. Personal phone numbers -> ignore.
 * 4. Known bank or card sender -> that bank's formats. Unknown format: money SMS stay undecided, others ignore.
 * 5. Shop and telecom formats (bills, merchant refunds), then parcel updates from anyone.
 * 6. Anything else: undecided if it looks like money, otherwise ignore.
 */
class RuleClassifier(
    private val zone: ZoneId,
    private val banks: List<BankRules>,
) : SmsClassifier {

    @Inject constructor() : this(ZoneId.systemDefault(), ALL_BANKS)

    override fun classify(sms: SmsInput, spamSenders: Set<String>): ParsedSms {
        val sender = SenderIds.parse(sms.sender)
        val bank = banks.firstOrNull { it.handles(sender.header) }
        if (Otp.looksLikeOtp(sms.body)) {
            // "Rs.500 without OTP/PIN HDFC Bank Card ..." is a spend, not a code. Only a bank's own transaction format can override.
            bankHit(bank, sms)?.takeIf { it.smsClass == SmsClass.TRANSACTION }?.let { return it }
            return ParsedSms(SmsClass.IGNORE, "otp")
        }
        if (sender.header in spamSenders) return ParsedSms(SmsClass.SPAM, "spam_list")
        if (sender.suffix == SenderSuffix.P) return ParsedSms(SmsClass.SPAM, "promo_suffix")
        if (sender.isPhoneNumber) return ParsedSms(SmsClass.IGNORE, "personal")

        bank?.let { bank ->
            bankHit(bank, sms)?.let { return it }
            return if (Money.looksLikeMoney(sms.body)) ParsedSms(null, "${bank.id}.undecided", bank.bank)
            else ParsedSms(SmsClass.IGNORE, "${bank.id}.info", bank.bank)
        }

        GeneralRules.classify(sms, sender)?.let { return it }
        DeliveryRules.classify(sms, sender)?.let { return it }

        return if (Money.looksLikeMoney(sms.body)) ParsedSms(null, "undecided")
        else ParsedSms(SmsClass.IGNORE, "no_money")
    }

    private fun bankHit(bank: BankRules?, sms: SmsInput): ParsedSms? {
        bank ?: return null
        for (rule in bank.rules) {
            val m = rule.regex.find(sms.body) ?: continue
            return rule.build(Hit(m, sms, bank.bank, rule.id, zone))
        }
        return null
    }
}
