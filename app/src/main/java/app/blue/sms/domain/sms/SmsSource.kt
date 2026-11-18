package app.blue.sms.domain.sms

/** One SMS as read from the phone. Multipart SMS arrive already joined by the SMS provider. */
data class RawSms(
    val providerId: Long,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val subscriptionId: Int,
)

/** A SIM subscription seen in the inbox, so the user can pick one without READ_PHONE_STATE. */
/** Stored instead of SIM ids when the user chooses "All SMS on this phone". */
const val ALL_SIMS = Int.MIN_VALUE

data class SimSlotSummary(
    val subscriptionId: Int,
    /** 1-based physical slot, or null if that SIM isn't active right now (or id is -1). */
    val slotNumber: Int?,
    val messageCount: Int,
    val topSenders: List<String>,
    val latestAt: Long,
)

interface SmsSource {
    /** Inbox SMS for the given SIM ids between [fromMillis] and [toMillis], newest first. Read-only. */
    suspend fun readInbox(fromMillis: Long, toMillis: Long, subscriptionIds: Set<Int>): List<RawSms>

    suspend fun simSlotsSeen(): List<SimSlotSummary>
}
