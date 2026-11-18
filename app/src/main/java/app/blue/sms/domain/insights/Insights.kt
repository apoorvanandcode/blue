package app.blue.sms.domain.insights

import app.blue.sms.data.db.ReviewItem
import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.TxnStatus

/** Totals include pending and skipped entries. Duplicates and "not a transaction" never count. */
fun TxnStatus.countsInTotals() = this != TxnStatus.DUPLICATE && this != TxnStatus.NOT_A_TRANSACTION

data class Totals(val inPaise: Long = 0, val outPaise: Long = 0, val inCount: Int = 0, val outCount: Int = 0) {
    val net get() = inPaise - outPaise
}

data class BreakdownRow(
    val key: String,
    val label: String,
    val amountPaise: Long,
    val count: Int,
    /** Same row in the previous period. Null when there's no comparison. */
    val previousPaise: Long?,
    /** For drilling into a category. -1 = uncategorised. */
    val categoryId: Long? = null,
)

data class InsightsResult(
    val totals: Totals,
    val previous: Totals?,
    /** Pending + skipped entries in the period: counted in totals, but not confirmed yet. */
    val unconfirmed: Int,
    /** Entries without an amount (e.g. USD charges not filled in yet). Not in any total. */
    val missingAmount: Int,
    val byCategory: List<BreakdownRow>,
    val byMethod: List<BreakdownRow>,
    val byForWhom: List<BreakdownRow>,
    val topMerchants: List<BreakdownRow>,
)

object Insights {
    const val UNCATEGORISED = -1L

    fun totals(items: List<ReviewItem>): Totals = items.counted().fold(Totals()) { t, i ->
        val amt = i.txn.amountPaise ?: return@fold t
        when (i.txn.direction) {
            Direction.IN -> t.copy(inPaise = t.inPaise + amt, inCount = t.inCount + 1)
            Direction.OUT -> t.copy(outPaise = t.outPaise + amt, outCount = t.outCount + 1)
            null -> t
        }
    }

    fun compute(current: List<ReviewItem>, previous: List<ReviewItem>?, direction: Direction, topN: Int = 10): InsightsResult {
        val cur = current.counted().filter { it.txn.direction == direction && it.txn.amountPaise != null }
        val prev = previous?.counted()?.filter { it.txn.direction == direction && it.txn.amountPaise != null }

        fun breakdown(key: (ReviewItem) -> Pair<String, String>, catId: ((ReviewItem) -> Long?)? = null): List<BreakdownRow> {
            val prevSums = prev?.groupBy { key(it).first }?.mapValues { (_, l) -> l.sumOf { it.txn.amountPaise!! } }
            return cur.groupBy { key(it).first }.map { (k, list) ->
                BreakdownRow(
                    key = k,
                    label = key(list.first()).second,
                    amountPaise = list.sumOf { it.txn.amountPaise!! },
                    count = list.size,
                    previousPaise = prevSums?.let { it[k] ?: 0L },
                    categoryId = catId?.invoke(list.first()),
                )
            }.sortedByDescending { it.amountPaise }
        }

        return InsightsResult(
            totals = totals(current),
            previous = previous?.let(::totals),
            unconfirmed = current.count { it.txn.status == TxnStatus.PENDING || it.txn.status == TxnStatus.SKIPPED },
            missingAmount = current.counted().count { it.txn.amountPaise == null },
            byCategory = breakdown({ (it.txn.categoryId?.toString() ?: "none") to (it.categoryName ?: "Uncategorised") }) {
                it.txn.categoryId ?: UNCATEGORISED
            },
            byMethod = withUniqueLabels(breakdown({ accountKey(it) to accountLabel(it) })),
            byForWhom = breakdown({ (it.txn.forWhomId?.toString() ?: "none") to (it.forWhomName ?: "Not set") }),
            topMerchants = breakdown({ merchant(it).let { m -> m.lowercase() to m } }).take(topN),
        )
    }

    private fun List<ReviewItem>.counted() = filter { it.txn.status.countsInTotals() }

    private fun merchant(i: ReviewItem) = i.txn.merchantRaw ?: i.txn.upiId ?: "Unknown"

    /** One row per account or card: the same card counts once even if SMS spell the bank differently. */
    private fun accountKey(i: ReviewItem) = when {
        i.last4 != null -> "${i.accountType}|${i.last4}"
        i.bank != null -> "${shortBank(i.bank)}|${i.accountType}"
        else -> "method|${i.txn.method}"
    }

    /** "HDFC credit card", "Federal Bank"; just the method when the SMS names no bank. */
    fun accountLabel(i: ReviewItem): String {
        val bank = i.bank ?: return when (i.txn.method) {
            PaymentMethod.UPI -> "UPI"
            PaymentMethod.CARD -> "Card"
            PaymentMethod.NETBANKING -> "Net banking"
            PaymentMethod.AUTO_DEBIT -> "Auto-debit"
            PaymentMethod.ATM -> "ATM"
            PaymentMethod.OTHER, null -> "Other"
        }
        return when (i.accountType) {
            AccountType.CREDIT_CARD -> "${shortBank(bank)} credit card"
            AccountType.DEBIT_CARD -> "${shortBank(bank)} debit card"
            else -> bank
        }
    }

    private fun shortBank(bank: String) = bank.removeSuffix(" Bank").trim()

    /** Two accounts at one bank would read the same; only then add the last 4 digits. */
    private fun withUniqueLabels(rows: List<BreakdownRow>): List<BreakdownRow> {
        val clash = rows.groupingBy { it.label }.eachCount().filterValues { it > 1 }.keys
        return rows.map { r ->
            val last4 = r.key.substringAfter('|', "").takeIf { r.label in clash && it.length == 4 && it.all(Char::isDigit) }
            if (last4 != null) r.copy(label = "${r.label} ••$last4") else r
        }
    }
}
