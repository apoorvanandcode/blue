package app.blue.sms.domain.review

import app.blue.sms.data.db.ReviewItem

/** What a card shows (and Confirm uses) when the user hasn't picked anything. */
data class Suggestion(val categoryId: Long?, val forWhomId: Long?, val categoryFromMemory: Boolean, val forWhomFromMemory: Boolean)

object Suggestions {
    /**
     * Category: the card's own value, else the exact-merchant memory (if that category isn't hidden),
     * else "Refund" for refunds. For whom: the card's own value, else memory, else Me.
     */
    fun suggest(item: ReviewItem, meId: Long?, refundCategoryId: Long?): Suggestion {
        val t = item.txn
        val memCat = item.memoryCategoryId?.takeIf { item.memoryCategoryActive != false }
        val category = t.categoryId ?: memCat ?: if (t.isRefund) refundCategoryId else null
        val forWhom = t.forWhomId ?: item.memoryForWhomId ?: meId
        return Suggestion(
            categoryId = category,
            forWhomId = forWhom,
            categoryFromMemory = t.categoryId == null && memCat != null,
            forWhomFromMemory = t.forWhomId == null && item.memoryForWhomId != null,
        )
    }
}
