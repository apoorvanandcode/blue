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

    /** Tuned on 20,171 real SMS in the 1.2.0 masking trial (see DECISIONS.md). Assumes the SMS was disguised by SmsMasker. */
    val instructions = """
        You read one SMS from an Indian phone and return JSON matching the schema. Never invent values. When a value is absent use null (never the text "none" or "unknown" in a text field); use "unknown"/"none" only where the schema lists them.

        PRIVACY MASKING. The SMS was pseudonymised on the phone before you see it:
        - Every number (amounts, balances, account and card digits, reference numbers, phone numbers, IFSC digits) was replaced by a random number of the same length. Dates and times are real.
        - Names of people and shops were replaced by made-up words of the same shape (e.g. "Ginoge GOTOB", "Rer*vukuto"). Treat a made-up word as a real name, never as a typo or a verb.
        - UPI ids were replaced (e.g. "desuzo19@ybl"); links became "https://x.example/...".
        - The bank's own masking (XXXX, xx, *) is left as it was.
        Copy masked values exactly as they appear, character for character: amount digits, merchant words, ref numbers, UPI ids, account digits.

        sms_class:
        - transaction: money actually moved to or from the user's own bank account, card or wallet: UPI or card spend, NEFT/IMPS/RTGS, ATM withdrawal, cash deposit, executed NACH/ECS/auto-debit/mandate debit, salary or other credit, refund or reversal actually credited, interest, dividend, bank charges. Also:
          - Every OneCard spend note ("That was a ... purchase of Rs X at Y", "You've fueled ...", "You have just paid a bill of Rs X on Y", "You've made a booking", "Your payment of Rs X at Y has been processed"): method card, account_type credit_card. OneCard sends no other alert.
          - A refund or reversal the SMS says was already credited ("has been credited to your account / original source"), even when a merchant sends it: direction in.
          - Wallet payments or credits reported by the wallet itself (Paytm wallet, Zomato Money).
          - A debit from the user's bank account to pay a credit card bill (direction out).
        - upcoming_finance: a future debit the user should expect: a mandate or autopay successfully created, registered, set up or active ("You have successfully created a mandate on <merchant> ... maximum amount", "UPI mandate has been successfully created"), SIP or auto-debit scheduled, "will be debited/deducted on <date>".
        - upcoming_delivery: a courier's or shop's notice about the user's parcel: picked up, shipped, arriving, out for delivery, or delivered (delivery codes/PINs too; "delivered" stays upcoming_delivery even if it mentions a payment). Not home-service visits (lab sample collection, technicians), cabs or drivers on the way, or personal messages.
        - spam: promotions and marketing: offers, loan/card/insurance offers, "cash/bonus/reward added" from games, apps or wallets to make you play or shop, "credited to your wallet, complete KYC/withdraw now" bait.
        - ignore: everything else, including:
          - OTPs and codes; KYC, account, PIN or app notices; statements; balance or limit alerts; personal messages.
          - Declined or failed payments; refunds "initiated", "processed" or "will be credited / shall reflect in N days" (not yet credited).
          - UPI collect or payment requests still waiting for approval ("has requested money", "on approving ... will be debited"); requests to approve or authorise a mandate; mandates revoked or cancelled.
          - Bill, premium or EMI reminders with a due date but no automatic debit.
          - Receipts from a merchant, biller or service: order placed or confirmed, recharge successful, booking or ticket confirmed, units allotted, "receipt of Rs X towards <biller> A/c", "received by Card for the Service", tax or GST payment confirmations (GSTN, CPIN, challan), broker or fund-house payouts "processed, funds will reach your bank", payment-gateway confirmations (PayU, Razorpay "transaction ... has succeeded"), card-terminal e-receipts (Card, Auth code, RRN fields). The bank, card or wallet alert records that money.
          - HDFC "AutoPay (E-mandate) Success!" messages, even when they show a Txn Amt: the separate HDFC "Spent" alert records the money.
          - Credit card side of a bill payment ("payment received/credited to your card").
          - Demat debits or credits of shares or bonds (NSDL/CDSL), EPF/PF contribution or passbook notices.

        Fields for transactions:
        - amount: the money that moved, a plain number without commas (1,06,082.50 -> 106082.5); never the balance or available limit. currency: ISO code, "INR" for Rs/INR/₹.
        - direction: out when money leaves the user, in when it arrives (a reversal or refund credited is in; HDFC "RTGS Money Deposited ~ To <name>" is out).
        - merchant: the other party exactly as written, keeping initials (payee for out, payer for in).
          - Only a UPI id named: use the UPI id. Only an account number: use it as written.
          - "NEFT-<ref>-<name>" or "by <name>": the name. Own-account transfer: the destination account as written.
          - Never the user's own bank, a greeting, a footer or an app name.
          - When the sender itself pays (FD interest, a company's dividend), the sender's name.
          - Only when no party is named, or the name slot is blank ("from from", "by ("): a short description such as "Savings interest", "Bank charges", "NACH debit", "NEFT credit", "UPI credit" or "ATM withdrawal".
          - SBI "has credit for <code>": merchant "Account credit", ref_no the code.
          - Never a wallet's own name for a wallet payment; the shop paid.
        - upi_id: a UPI id (text@handle) as written. A number after "UPI:" is a reference, not a UPI id.
        - account_last4: copy exactly the final 4 digits of the user's own account or card number as shown (XXXXX123456 -> "3456"; XX123 -> "123", never "0123"). Never pad, change or invent digits; never take them from a reference, folio number, phone number or the other party's account.
        - account_type: credit_card when a credit card is used; debit_card when a debit card is used (card spend or ATM); savings for bank accounts, including UPI, NEFT/IMPS, NACH and mandate debits and credits that don't name a card; unknown for wallets. For an HDFC "Bank Card" alert: "Avl bal"/"Bal" or "BLOCK DC" means debit_card; "Avl Lmt", "BLOCK CC" or a named credit card means credit_card.
        - method:
          - upi: UPI, VPA, a "SMS BLOCK UPI" footer, a number after "UPI:", HDFC "Money Transfer ... UPI" alerts, and the HDFC form "Sent Rs.X From HDFC Bank A/C *1234 To <name> On <date> Ref <12 digits>" (also "Amt Sent"), even when the payee is a bank.
          - card: card spend, and refunds or reversals credited back to a card.
          - atm: cash withdrawn at an ATM, including "Withdrawn Rs X From ... Card ... At <place>" (the place is the merchant).
          - netbanking: only when NEFT, IMPS, RTGS or net banking is written (this wins over "Money Transfer"; interest paid by NEFT is netbanking).
          - auto_debit: NACH, ECS, mandate or autopay debits (debits only).
          - other: interest, charges, NACH credits, wallet, cash deposit, cheque.
        - ref_no: the transaction reference (Ref, RRN, UTR, IMPS/NEFT ref, "UPI: <digits>", transaction number) as written. Copy it whole with any letter prefix ("CMS123456", "N123..."). In "NEFT Cr-<IFSC>-<payer>-...-<UTR>" it is the UTR (the last segment). Never an IFSC code (4 letters, "0", 6 characters), phone number or card number.
        - txn_datetime: when the money moved, as YYYY-MM-DDTHH:MM in India time. If the SMS gives a date without a time, use that date with the received time. If no date, null. A date without a year is in the year of the received date.
        - due_date: YYYY-MM-DD for upcoming items. title: a short name for an upcoming item.
    """.trimIndent()
}

object AiReplyParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** Validates Gemini's text and turns it into the same shape the rules produce. Throws [InvalidAiReply]. */
    fun parse(text: String, sms: SmsInput, zone: ZoneId, restore: (AiReply) -> AiReply = { it }): ParsedSms {
        val r = try {
            restore(json.decodeFromString(AiReply.serializer(), text.trim()))
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
