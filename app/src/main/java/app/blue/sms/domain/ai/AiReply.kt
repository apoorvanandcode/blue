package app.blue.sms.domain.ai

import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.model.UpcomingKind
import app.blue.sms.domain.parse.Accounts
import app.blue.sms.domain.parse.DeliveryEvent
import app.blue.sms.domain.parse.Merchants
import app.blue.sms.domain.parse.ParsedSms
import app.blue.sms.domain.parse.SmsInput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The fixed JSON Gemini must return for one SMS. Unknown values are null, "unknown" or "none". */
@Serializable
data class AiReply(
    @SerialName("sms_class") val smsClass: String,
    val amount: Double? = null,
    val currency: String? = null,
    val direction: String = "unknown",
    val merchant: String? = null,
    @SerialName("upi_id") val upiId: String? = null,
    @SerialName("account_last4") val accountLast4: String? = null,
    @SerialName("account_type") val accountType: String = "unknown",
    val bank: String? = null,
    val method: String = "unknown",
    @SerialName("ref_no") val refNo: String? = null,
    @SerialName("txn_datetime") val txnDatetime: String? = null,
    @SerialName("is_refund") val isRefund: Boolean = false,
    @SerialName("upcoming_kind") val upcomingKind: String = "none",
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("delivery_event") val deliveryEvent: String = "none",
    val title: String? = null,
    @SerialName("tracking_ref") val trackingRef: String? = null,
)

class InvalidAiReply(message: String) : Exception(message)

object AiSchema {
    private fun nullable(type: String) = buildJsonObject { putJsonArray("type") { add(JsonPrimitive(type)); add(JsonPrimitive("null")) } }
    private fun choice(vararg v: String) = buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { v.forEach { add(JsonPrimitive(it)) } })
    }

    val CLASSES = listOf("transaction", "upcoming_finance", "upcoming_delivery", "spam", "ignore")

    /** JSON Schema sent with every request. */
    val schema: JsonObject = buildJsonObject {
        put("type", "object")
        val props = linkedMapOf(
            "sms_class" to choice(*CLASSES.toTypedArray()),
            "amount" to nullable("number"),
            "currency" to nullable("string"),
            "direction" to choice("in", "out", "unknown"),
            "merchant" to nullable("string"),
            "upi_id" to nullable("string"),
            "account_last4" to nullable("string"),
            "account_type" to choice("savings", "credit_card", "debit_card", "unknown"),
            "bank" to nullable("string"),
            "method" to choice("upi", "card", "netbanking", "auto_debit", "atm", "other", "unknown"),
            "ref_no" to nullable("string"),
            "txn_datetime" to nullable("string"),
            "is_refund" to buildJsonObject { put("type", "boolean") },
            "upcoming_kind" to choice("sip", "mandate", "auto_debit", "delivery", "none"),
            "due_date" to nullable("string"),
            "delivery_event" to choice("shipped", "out_for_delivery", "delivered", "none"),
            "title" to nullable("string"),
            "tracking_ref" to nullable("string"),
        )
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        put("required", JsonArray(props.keys.map(::JsonPrimitive)))
    }

    val instructions = """
        You read one SMS from an Indian phone and return JSON matching the schema. Never invent values: use null, "unknown" or "none".
        sms_class:
        - transaction: money actually moved (UPI, card spend, NEFT/IMPS/RTGS, ATM, auto-debit done, salary or other credit, refund credited, interest, bank charges).
        - upcoming_finance: a future debit (mandate set up, SIP or auto-debit scheduled, "will be debited").
        - upcoming_delivery: a parcel shipped, out for delivery or delivered.
        - spam: promotions and marketing.
        - ignore: everything else, including OTPs, declined or failed payments, collect/payment requests not yet paid, credit card bill payments, reward points, balance or limit notices, bills and statements.
        amount: a plain number in the SMS currency without commas (1,06,082.50 -> 106082.5). currency: ISO code, "INR" for Rs/INR/₹.
        direction: out when money leaves the user, in when it arrives.
        merchant: the payee or payer exactly as written in the SMS. account_last4: the last digits of the user's own account or card.
        txn_datetime: when the money moved, as YYYY-MM-DDTHH:MM in India time; null if the SMS has no date.
        due_date: YYYY-MM-DD for upcoming items. title: a short name for an upcoming item.
    """.trimIndent()
}

object AiReplyParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** Validates Gemini's text and turns it into the same shape the rules produce. Throws [InvalidAiReply]. */
    fun parse(text: String, sms: SmsInput, zone: ZoneId): ParsedSms {
        val r = try {
            json.decodeFromString(AiReply.serializer(), text.trim())
        } catch (e: Exception) {
            throw InvalidAiReply("not valid JSON for the schema")
        }
        val cls = when (r.smsClass) {
            "transaction" -> SmsClass.TRANSACTION
            "upcoming_finance" -> SmsClass.UPCOMING_FINANCE
            "upcoming_delivery" -> SmsClass.UPCOMING_DELIVERY
            "spam" -> SmsClass.SPAM
            "ignore" -> SmsClass.IGNORE
            else -> throw InvalidAiReply("unknown sms_class '${r.smsClass}'")
        }
        if (r.amount != null && (r.amount < 0 || r.amount.isNaN() || r.amount > 1e10)) throw InvalidAiReply("bad amount")
        val inr = r.currency == null || r.currency.equals("INR", true)
        // Decided 2026-11-17: foreign-currency amounts are left empty for the user to fill in.
        val paise = if (inr) r.amount?.let { BigDecimal.valueOf(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong() } else null
        val receivedTime = Instant.ofEpochMilli(sms.receivedAt).atZone(zone).toLocalTime()
        val txnAt = r.txnDatetime?.let {
            try { LocalDateTime.parse(it.take(16)).atZone(zone).toInstant().toEpochMilli() } catch (_: Exception) { null }
        }
        val due = r.dueDate?.let {
            try { LocalDate.parse(it.take(10)).atTime(receivedTime).atZone(zone).toInstant().toEpochMilli() } catch (_: Exception) { null }
        }
        return ParsedSms(
            smsClass = cls,
            ruleId = "ai",
            bank = r.bank?.trim()?.takeIf { it.isNotEmpty() },
            amountPaise = paise,
            direction = when (r.direction) { "in" -> Direction.IN; "out" -> Direction.OUT; else -> null },
            account = Accounts.normalize(r.accountLast4),
            accountType = when (r.accountType) {
                "savings" -> AccountType.SAVINGS; "credit_card" -> AccountType.CREDIT_CARD; "debit_card" -> AccountType.DEBIT_CARD
                else -> null
            },
            method = when (r.method) {
                "upi" -> PaymentMethod.UPI; "card" -> PaymentMethod.CARD; "netbanking" -> PaymentMethod.NETBANKING
                "auto_debit" -> PaymentMethod.AUTO_DEBIT; "atm" -> PaymentMethod.ATM; "other" -> PaymentMethod.OTHER
                else -> null
            },
            merchant = Merchants.clean(r.merchant),
            upiId = r.upiId?.trim()?.takeIf { '@' in it },
            refNo = r.refNo?.trim()?.takeIf { it.isNotEmpty() },
            txnAt = txnAt ?: sms.receivedAt,
            isRefund = r.isRefund,
            upcomingKind = when (r.upcomingKind) {
                "sip" -> UpcomingKind.SIP; "mandate" -> UpcomingKind.MANDATE; "auto_debit" -> UpcomingKind.AUTO_DEBIT
                "delivery" -> UpcomingKind.DELIVERY; else -> null
            },
            title = r.title?.trim()?.takeIf { it.isNotEmpty() },
            dueAt = due,
            deliveryEvent = when (r.deliveryEvent) {
                "shipped" -> DeliveryEvent.SHIPPED; "out_for_delivery" -> DeliveryEvent.OUT_FOR_DELIVERY
                "delivered" -> DeliveryEvent.DELIVERED; else -> null
            },
            trackingRef = r.trackingRef?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

/** One SMS in, one result out. */
interface AiExtractor {
    suspend fun extract(sms: SmsInput): AiOutcome
}

enum class FailKind {
    /** Bad key, model name, billing or permissions: nothing will work until Settings change. */
    SETUP,
    /** Offline, rate-limited or Google having trouble: try again later. */
    TEMPORARY,
    /** This SMS: e.g. the reply didn't match the schema. Counts toward its 3 tries. */
    THIS_SMS,
}

sealed interface AiOutcome {
    data class Ok(val parsed: ParsedSms) : AiOutcome
    data class Failed(val reason: String, val kind: FailKind) : AiOutcome
}
