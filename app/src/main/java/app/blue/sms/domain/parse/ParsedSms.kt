package app.blue.sms.domain.parse

import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.model.UpcomingKind

data class SmsInput(val sender: String, val body: String, val receivedAt: Long)

enum class DeliveryEvent { SHIPPED, OUT_FOR_DELIVERY, DELIVERED }

/** What the rules (later also Gemini) found in one SMS. */
data class ParsedSms(
    /** Null = rules couldn't decide. The SMS waits for Gemini. */
    val smsClass: SmsClass?,
    val ruleId: String,
    val bank: String? = null,
    val amountPaise: Long? = null,
    val direction: Direction? = null,
    /** Normalised as "X" + last digits, e.g. "X4321". */
    val account: String? = null,
    val accountType: AccountType? = null,
    val method: PaymentMethod? = null,
    val merchant: String? = null,
    val upiId: String? = null,
    val refNo: String? = null,
    val txnAt: Long? = null,
    val isRefund: Boolean = false,
    val upcomingKind: UpcomingKind? = null,
    val title: String? = null,
    val dueAt: Long? = null,
    val deliveryEvent: DeliveryEvent? = null,
    val trackingRef: String? = null,
)

/** Classifies and extracts one SMS. Rules today; Gemini plugs in behind the same interface in Phase 6. */
interface SmsClassifier {
    /** [spamSenders] are sender headers (e.g. "ZOMATO") the user confirmed as spam. */
    fun classify(sms: SmsInput, spamSenders: Set<String> = emptySet()): ParsedSms
}
