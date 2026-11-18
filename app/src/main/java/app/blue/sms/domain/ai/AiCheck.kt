package app.blue.sms.domain.ai

import app.blue.sms.data.db.SmsDao
import app.blue.sms.data.db.SpamSenderDao
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.parse.Money
import app.blue.sms.domain.parse.ParsedSms
import app.blue.sms.domain.parse.SmsClassifier
import app.blue.sms.domain.parse.SmsInput
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.abs

data class FieldDiff(val field: String, val rules: String, val ai: String)

data class CheckRow(
    val smsId: Long,
    val sender: String,
    val body: String,
    val ruleId: String,
    val diffs: List<FieldDiff>,
    /** Set when Gemini couldn't answer for this SMS. */
    val failure: String? = null,
)

data class CheckSummary(val checked: Int, val agreed: Int, val failed: Int, val byField: Map<String, Int>) {
    val agreePct: Int get() = if (checked - failed == 0) 0 else agreed * 100 / (checked - failed)
}

/** Compares what the rules and Gemini read from the same SMS. Pure, so it's unit-tested. */
object AiCompare {
    const val TYPE = "type"; const val AMOUNT = "amount"; const val DIRECTION = "in/out"
    const val MERCHANT = "merchant"; const val DATE = "date"; const val ACCOUNT = "account"
    val FIELDS = listOf(TYPE, AMOUNT, DIRECTION, MERCHANT, DATE, ACCOUNT)

    private val fmt = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")

    fun compare(rules: ParsedSms, ai: ParsedSms, zone: ZoneId): List<FieldDiff> = buildList {
        if (ai.smsClass != SmsClass.TRANSACTION) {
            add(FieldDiff(TYPE, "transaction", ai.smsClass?.name?.lowercase() ?: "undecided"))
            return@buildList // other fields aren't comparable
        }
        if (rules.amountPaise != ai.amountPaise) add(FieldDiff(AMOUNT, money(rules.amountPaise), money(ai.amountPaise)))
        if (rules.direction != null && ai.direction != null && rules.direction != ai.direction) {
            add(FieldDiff(DIRECTION, rules.direction.name.lowercase(), ai.direction.name.lowercase()))
        }
        if (!sameMerchant(rules.merchant, ai.merchant)) add(FieldDiff(MERCHANT, rules.merchant ?: "—", ai.merchant ?: "—"))
        val rt = rules.txnAt; val at = ai.txnAt
        if (rt != null && at != null && abs(rt - at) > 60_000) add(FieldDiff(DATE, time(rt, zone), time(at, zone)))
        if (rules.account != null && ai.account != null && rules.account != ai.account) add(FieldDiff(ACCOUNT, rules.account, ai.account))
    }

    /** Case, spacing and punctuation don't count, and one may be a shortened form of the other. */
    fun sameMerchant(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b || a == null // rules found none: nothing to disagree with
        val x = norm(a); val y = norm(b)
        return x == y || (x.length >= 4 && y.startsWith(x)) || (y.length >= 4 && x.startsWith(y))
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9@]+"), " ").trim()
    private fun money(p: Long?) = p?.let { "₹" + Money.format(it) } ?: "—"
    private fun time(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).format(fmt)

    fun summarise(rows: List<CheckRow>): CheckSummary = CheckSummary(
        checked = rows.size,
        agreed = rows.count { it.failure == null && it.diffs.isEmpty() },
        failed = rows.count { it.failure != null },
        byField = FIELDS.associateWith { f -> rows.count { r -> r.diffs.any { it.field == f } } },
    )
}

/**
 * Read-only check: re-reads recent transaction SMS with the rules (on the phone) and with Gemini,
 * one SMS per request, and reports where they disagree. Changes no data.
 */
class AiCheckRunner @Inject constructor(
    private val smsDao: SmsDao,
    private val classifier: SmsClassifier,
    private val spamSenderDao: SpamSenderDao,
    private val ai: AiExtractor,
) {
    private val zone = ZoneId.systemDefault()

    suspend fun run(count: Int, onRow: suspend (done: Int, total: Int, row: CheckRow) -> Unit) {
        val spam = spamSenderDao.allSenders().toSet()
        val picks = smsDao.recentTransactionSms(count * 3)
            .map { it to classifier.classify(SmsInput(it.sender, it.body, it.receivedAt), spam) }
            .filter { (_, p) -> p.smsClass == SmsClass.TRANSACTION }
            .take(count)
        picks.forEachIndexed { i, (sms, rules) ->
            val row = when (val o = ai.extract(SmsInput(sms.sender, sms.body, sms.receivedAt))) {
                is AiOutcome.Ok -> CheckRow(sms.id, sms.sender, sms.body, rules.ruleId, AiCompare.compare(rules, o.parsed, zone))
                is AiOutcome.Failed -> {
                    if (o.kind == FailKind.SETUP) throw IllegalStateException(o.reason)
                    CheckRow(sms.id, sms.sender, sms.body, rules.ruleId, emptyList(), o.reason)
                }
            }
            onRow(i + 1, picks.size, row)
        }
    }
}
