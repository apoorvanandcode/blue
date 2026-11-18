package app.blue.sms.domain.upcoming

import app.blue.sms.data.db.UpcomingItemEntity

/**
 * Decides which SMS closes which Upcoming item.
 * Money: the first word of the merchant ("APPLE MEDIA SER" -> APPLE). One may be a prefix of the
 * other ("GRAMMARLY" closes "GRAMMARLYINC"). The debit can't exceed the item's amount
 * (for mandates that's the maximum) and can't be more than a day older than the item's SMS.
 * Parcels: the tracking number, else the sender (oldest open parcel from that sender closes first).
 */
object UpcomingMatcher {
    private const val DAY = 24L * 60 * 60 * 1000

    fun moneyKey(merchant: String?): String? =
        merchant?.uppercase()?.split(Regex("[^A-Z0-9]+"))?.firstOrNull { it.length >= 3 }?.let { "money:$it" }

    fun parcelKey(tracking: String?, header: String): String = "parcel:" + (tracking ?: header)

    fun moneyKeysMatch(a: String, b: String): Boolean {
        val x = a.removePrefix("money:")
        val y = b.removePrefix("money:")
        val short = if (x.length <= y.length) x else y
        val long = if (short === x) y else x
        return short.length >= 4 && long.startsWith(short) || x == y
    }

    /** The oldest open money item this debit fulfils, or null. */
    fun findMoneyMatch(
        open: List<UpcomingItemEntity>,
        merchant: String?,
        amountPaise: Long?,
        txnAt: Long,
        itemSmsTime: (UpcomingItemEntity) -> Long?,
    ): UpcomingItemEntity? {
        val key = moneyKey(merchant) ?: return null
        return open.firstOrNull { item ->
            val itemKey = item.matchKey ?: return@firstOrNull false
            moneyKeysMatch(itemKey, key) &&
                (item.amountPaise == null || amountPaise == null || amountPaise <= item.amountPaise) &&
                (itemSmsTime(item)?.let { txnAt >= it - DAY } ?: true)
        }
    }
}
