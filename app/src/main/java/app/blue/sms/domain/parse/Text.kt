package app.blue.sms.domain.parse

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

object Money {
    /** "1,06,082.50" -> 10608250. Handles Indian and western commas, with or without decimals. */
    fun toPaise(text: String?): Long? {
        val clean = text?.replace(",", "")?.replace(" ", "")?.trimEnd('.') ?: return null
        return clean.toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.toLong()
    }

    fun format(paise: Long): String = BigDecimal(paise).movePointLeft(2).setScale(2).toPlainString()

    private val amount = Regex("""(?i)(?:\brs\.?|\binr\.?|₹)\s*\d""")
    private val direction = Regex(
        """(?i)\b(debited|credited|spent|received|refund(?:ed)?|reversed|withdrawn|sent|paid|deducted|deposited|transferred|debit(?:ed)? by|credit(?:ed)? to)\b"""
    )

    /** Has an amount and a money-movement word: worth a closer look (or Gemini). */
    fun looksLikeMoney(body: String): Boolean = amount.containsMatchIn(body) && direction.containsMatchIn(body)
}

object Otp {
    private const val KEY = """otp|one.?time.?pass\w*|verification code|security code|\bcode\b|\bpin\b|password"""
    private val nearNumber = Regex("""(?i)(?:$KEY)\D{0,40}?\b\d{4,8}\b|\b\d{4,8}\b\D{0,40}?(?:$KEY)""")
    private val key = Regex("(?i)$KEY")
    private val share = Regex("""(?i)\bshare\b""")
    /** Voucher and gift codes like "Code: U3JG0EY2" or "PIN: 0" — mixed letters and digits. */
    private val labelledCode = Regex("""(?i)\b(?:code|pin|voucher code|coupon code)\s*:\s*[A-Z0-9-]*\d[A-Z0-9-]*""")

    /** OTPs, PINs, voucher and delivery codes. These are always `ignore` and never leave the phone. */
    fun looksLikeOtp(body: String): Boolean =
        nearNumber.containsMatchIn(body) || labelledCode.containsMatchIn(body) ||
            (key.containsMatchIn(body) && share.containsMatchIn(body) && !Money.looksLikeMoney(body))
}

object Accounts {
    /** "XXXXXXX1234", "*1234", "Xx1234", "XX123" -> "X1234" / "X123". */
    fun normalize(raw: String?): String? {
        val digits = raw?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() } ?: return null
        return "X" + digits.takeLast(4)
    }
}

object Merchants {
    fun clean(raw: String?): String? =
        raw?.replace(Regex("""\s+"""), " ")?.trim(' ', '.', ',', '_', '-', '+', ':')?.takeIf { it.isNotEmpty() }
}

object SmsDates {
    private val withTime = listOf(
        "ddMMMyy HH:mm:ss", "ddMMMyy HH:mm", "yyyy-MM-dd:HH:mm:ss", "yyyy-MM-dd HH:mm:ss",
        "d-M-yyyy HH:mm:ss", "d-M-yyyy HH:mm", "d/M/yyyy HH:mm:ss",
    ).map(::formatter)
    private val dateOnly = listOf(
        "ddMMMyy", "yyyy-MM-dd", "d-M-yyyy", "d-M-yy", "d/M/yyyy", "d/M/yy", "d.M.yyyy", "d.M.yy",
        "d-MMM-yyyy", "d-MMM-yy", "d/MMM/yyyy", "d/MMM/yy", "d MMM yyyy",
    ).map(::formatter)

    private fun formatter(p: String): DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH)

    /**
     * Parses the date formats seen in Indian bank SMS. A date without a time gets the SMS's
     * received time of day. Anything unreadable falls back to [receivedAt].
     */
    fun parse(text: String?, receivedAt: Long, zone: ZoneId): Long {
        if (text.isNullOrBlank()) return receivedAt
        val s = text.trim().removePrefix(".")
            .replace(Regex("""(?i)\s+at\s+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trimEnd('.', ',')
        for (f in withTime) {
            try { return LocalDateTime.parse(s, f).atZone(zone).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {}
        }
        val receivedTime = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalTime()
        for (f in dateOnly) {
            try { return LocalDate.parse(s, f).atTime(receivedTime).atZone(zone).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {}
        }
        return receivedAt
    }
}
