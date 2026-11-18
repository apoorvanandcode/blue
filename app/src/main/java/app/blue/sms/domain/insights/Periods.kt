package app.blue.sms.domain.insights

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class PeriodKind { TODAY, WEEK, MONTH, CUSTOM, ALL }

/** A date range, both ends inclusive. Weeks start on Monday. */
data class Period(val kind: PeriodKind, val start: LocalDate, val end: LocalDate) {

    val days: Long get() = ChronoUnit.DAYS.between(start, end) + 1

    /** [from, to) in epoch millis. */
    fun millis(zone: ZoneId): Pair<Long, Long> =
        if (kind == PeriodKind.ALL) 0L to Long.MAX_VALUE
        else start.atStartOfDay(zone).toInstant().toEpochMilli() to end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The part that has happened: a period running past [today] is cut at today. */
    fun soFar(today: LocalDate): Period = if (end > today && start <= today) copy(end = today) else this

    /**
     * The previous period of the same length. A period still in progress is compared
     * like for like: 1–17 Nov is compared with 1–17 Oct, not all of October.
     */
    fun previous(today: LocalDate): Period? {
        val cur = soFar(today)
        return when (kind) {
            PeriodKind.ALL -> null
            PeriodKind.TODAY -> Period(kind, start.minusDays(1), start.minusDays(1))
            PeriodKind.WEEK -> Period(kind, start.minusWeeks(1), cur.end.minusWeeks(1))
            PeriodKind.MONTH -> {
                val pStart = start.minusMonths(1)
                val elapsed = ChronoUnit.DAYS.between(start, cur.end)
                val pEnd = minOf(pStart.plusDays(elapsed), pStart.with(TemporalAdjusters.lastDayOfMonth()))
                Period(kind, pStart, pEnd)
            }
            PeriodKind.CUSTOM -> Period(kind, start.minusDays(days), start.minusDays(1))
        }
    }

    /** The arrows: step one whole period back (-1) or forward (+1). */
    fun shift(step: Int): Period = when (kind) {
        PeriodKind.ALL -> this
        PeriodKind.TODAY -> of(kind, start.plusDays(step.toLong()))
        PeriodKind.WEEK -> of(kind, start.plusWeeks(step.toLong()))
        PeriodKind.MONTH -> of(kind, start.plusMonths(step.toLong()))
        PeriodKind.CUSTOM -> Period(kind, start.plusDays(days * step), end.plusDays(days * step))
    }

    fun label(today: LocalDate): String {
        val d = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        val dy = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        return when (kind) {
            PeriodKind.ALL -> "All time"
            PeriodKind.TODAY -> if (start == today) "Today" else if (start == today.minusDays(1)) "Yesterday" else start.format(dy)
            PeriodKind.MONTH -> start.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
            PeriodKind.WEEK, PeriodKind.CUSTOM ->
                if (start.year == end.year) "${start.format(d)} – ${end.format(dy)}" else "${start.format(dy)} – ${end.format(dy)}"
        }
    }

    /** Short label for comparisons, e.g. "1–17 Oct". */
    fun shortLabel(): String {
        val d = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        return when {
            start == end -> start.format(d)
            start.month == end.month && start.year == end.year -> "${start.dayOfMonth}–${end.format(d)}"
            else -> "${start.format(d)} – ${end.format(d)}"
        }
    }

    companion object {
        fun of(kind: PeriodKind, day: LocalDate): Period = when (kind) {
            PeriodKind.TODAY -> Period(kind, day, day)
            PeriodKind.WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { Period(kind, it, it.plusDays(6)) }
            PeriodKind.MONTH -> Period(kind, day.withDayOfMonth(1), day.with(TemporalAdjusters.lastDayOfMonth()))
            PeriodKind.CUSTOM -> Period(kind, day, day)
            PeriodKind.ALL -> Period(kind, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1))
        }
    }
}
