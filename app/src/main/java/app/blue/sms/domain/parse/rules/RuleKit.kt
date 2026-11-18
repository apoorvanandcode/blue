package app.blue.sms.domain.parse.rules

import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.model.UpcomingKind
import app.blue.sms.domain.parse.Accounts
import app.blue.sms.domain.parse.Merchants
import app.blue.sms.domain.parse.Money
import app.blue.sms.domain.parse.ParsedSms
import app.blue.sms.domain.parse.SmsDates
import app.blue.sms.domain.parse.SmsInput
import java.time.ZoneId

/** "Rs 1,060.82", "INR.8850.00", "₹ 50" -> named group `amt`. */
internal val AMT = """(?:\brs\.?|\binr\.?|₹)\s*(?<amt>\d[\d,]*(?:\.\d{1,2})?)"""
/** "X4321", "XXXXXXX1234", "*1234", "Xx1234" -> named group `acct`. */
internal val ACCT = """(?<acct>[x*]*\d{3,})"""

/**
 * One SMS format. The regex is matched case-insensitively and may span lines.
 * Named groups the helpers understand: amt, acct, to, upi, ref, date, time.
 */
class Rule(val id: String, pattern: String, val build: Hit.() -> ParsedSms) {
    val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
}

/** Shorthand for a format that is known and has no money impact. */
fun ignoreRule(id: String, pattern: String) = Rule(id, pattern) { ignore() }

/** All formats for one bank or card issuer, matched by sender header prefix. */
class BankRules(val id: String, val bank: String, val headers: List<String>, val rules: List<Rule>) {
    fun handles(header: String) = headers.any { header.startsWith(it) }
}

class Hit(
    private val m: MatchResult,
    val sms: SmsInput,
    val bank: String,
    val ruleId: String,
    private val zone: ZoneId,
) {
    fun g(name: String): String? = try {
        (m.groups as? MatchNamedGroupCollection)?.get(name)?.value?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: IllegalArgumentException) {
        null // the rule's regex has no such group
    }

    fun amount(): Long? = Money.toPaise(g("amt"))
    fun account(): String? = Accounts.normalize(g("acct"))
    fun at(): Long = SmsDates.parse(listOfNotNull(g("date"), g("time")).joinToString(" "), sms.receivedAt, zone)

    fun txn(
        direction: Direction,
        method: PaymentMethod,
        accountType: AccountType = AccountType.SAVINGS,
        merchant: String? = g("to"),
        isRefund: Boolean = false,
        amountPaise: Long? = amount(),
    ) = ParsedSms(
        smsClass = SmsClass.TRANSACTION,
        ruleId = ruleId,
        bank = bank,
        amountPaise = amountPaise,
        direction = direction,
        account = account(),
        accountType = accountType,
        method = method,
        merchant = Merchants.clean(merchant),
        upiId = g("upi")?.trimStart('*'),
        refNo = g("ref"),
        txnAt = at(),
        isRefund = isRefund,
    )

    fun upcoming(kind: UpcomingKind, amountPaise: Long? = amount(), title: String? = null): ParsedSms {
        val merchant = Merchants.clean(g("to"))
        return ParsedSms(
            smsClass = SmsClass.UPCOMING_FINANCE,
            ruleId = ruleId,
            bank = bank,
            amountPaise = amountPaise,
            direction = Direction.OUT,
            account = account(),
            merchant = merchant,
            upiId = g("upi")?.trimStart('*'),
            upcomingKind = kind,
            title = title ?: merchant,
            dueAt = if (g("date") != null) at() else null,
        )
    }

    fun ignore() = ParsedSms(SmsClass.IGNORE, ruleId, bank)
}
