package app.blue.sms.ui.insights

import kotlin.math.sqrt

/**
 * How an Insights list shows each row's share (decided 2026-11-19, replaces the share bars):
 * - Heat: the row gets a light accent wash, strongest for the biggest share and fading with size;
 *   rows under 2% stay plain. The wash depends only on the row's share, so it looks the same
 *   whether the list is folded or open.
 * - Fold: the list shows the top rows until they cover 90% of the total or 5 rows, whichever is fewer;
 *   the rest sit behind "N more".
 */
object HeatFold {
    const val MAX_SHOWN = 5
    const val COVER = 0.90
    private const val MIN_SHARE = 0.02
    private const val MIN_ALPHA = 0.05f
    private const val MAX_ALPHA = 0.20f

    /** How many rows to show before folding. Amounts must be sorted, biggest first. */
    fun shownCount(amounts: List<Long>): Int {
        val total = amounts.sum().takeIf { it > 0 } ?: return minOf(amounts.size, MAX_SHOWN)
        var running = 0L
        amounts.forEachIndexed { i, a ->
            running += a
            if (i + 1 >= MAX_SHOWN || running >= COVER * total) return i + 1
        }
        return amounts.size
    }

    /** Wash strength (0 = none) for a row with [share] of the total, when the biggest row has [topShare]. */
    fun alpha(share: Float, topShare: Float): Float {
        if (share < MIN_SHARE || topShare <= 0f) return 0f
        return MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * sqrt((share / topShare).coerceIn(0f, 1f))
    }
}
