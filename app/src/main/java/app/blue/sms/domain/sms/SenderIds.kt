package app.blue.sms.domain.sms

/** Indian DLT sender suffixes. */
enum class SenderSuffix(val meaning: String) {
    P("promotional"),
    S("service"),
    T("transactional"),
    G("government"),
}

data class SenderInfo(
    val raw: String,
    /** The brand or bank part, e.g. "FEDBNK" from "JM-FEDBNK-S". */
    val header: String,
    val suffix: SenderSuffix?,
    val isPhoneNumber: Boolean,
)

object SenderIds {
    fun parse(raw: String): SenderInfo {
        val trimmed = raw.trim()
        val digits = trimmed.removePrefix("+")
        if (digits.length >= 6 && digits.all { it.isDigit() }) {
            return SenderInfo(raw, trimmed, suffix = null, isPhoneNumber = true)
        }
        var parts = trimmed.split('-').filter { it.isNotBlank() }
        if (parts.isEmpty()) return SenderInfo(raw, trimmed.uppercase(), null, false)

        val last = parts.last().uppercase()
        val suffix = if (parts.size >= 2 && last.length == 1) {
            SenderSuffix.entries.firstOrNull { it.name == last }
        } else null
        if (suffix != null) parts = parts.dropLast(1)
        // Drop the 2-letter operator/circle prefix ("JM-", "AX-", "VM-").
        if (parts.size >= 2 && parts.first().length == 2) parts = parts.drop(1)

        return SenderInfo(raw, parts.joinToString("-").uppercase(), suffix, isPhoneNumber = false)
    }
}
