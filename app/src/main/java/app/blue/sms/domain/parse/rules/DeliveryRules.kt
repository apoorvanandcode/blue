package app.blue.sms.domain.parse.rules

import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.model.UpcomingKind
import app.blue.sms.domain.parse.DeliveryEvent
import app.blue.sms.domain.parse.ParsedSms
import app.blue.sms.domain.parse.SmsInput
import app.blue.sms.domain.sms.SenderInfo

/** Parcel updates from any brand or courier. */
object DeliveryRules {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val placed = rx("""order placed|order (?:is|has been) placed|placed successfully""")
    private val outFor = rx("""out for delivery|arriving today|will be delivered today|delivery today|^\s*arriving\b|will deliver your (?:shipment|order|package|parcel)""")
    private val shipped = rx("""\b(?:shipped|dispatched)\b|in transit""")
    private val delivered = rx("""\b(?:is|has been|was|been)\s+(?:successfully\s+)?delivered\b|delivered successfully|^delivered\b|\bwe(?:'ve| have) delivered your\b""")

    private val trackingPatterns = listOf(
        Regex("""\b([A-Z]{2}\d{9}[A-Z]{2})\b"""), // India Post
        Regex("""(?i)\b(?:order|awb|shipment|tracking)\s*(?:no\.?|id|number|#)?\s*:?\s*([A-Z]{0,4}\d[A-Z0-9-]{6,})\b"""),
    )
    private val item = rx("""(?:order for|package with|your order|your)\s+(.{3,40}?)\s+(?:is|has|will|and \d+ more|from)\b""")
    /** "Shipped: ROSALA .. and 4 more items is shipped" -> "ROSALA .." */
    private val labelledItem = rx("""^\s*(?:shipped|delivered|dispatched|arriving today|out for delivery)\s*:\s*(?:your (?:package|order) with\s+)?(.{3,40}?)\s+(?:is|has|will|and \d+ more)\b""")

    fun classify(sms: SmsInput, sender: SenderInfo): ParsedSms? {
        val body = sms.body
        if (placed.containsMatchIn(body)) return ParsedSms(SmsClass.IGNORE, "delivery.placed")
        val (event, id) = when {
            outFor.containsMatchIn(body) -> DeliveryEvent.OUT_FOR_DELIVERY to "delivery.out_for_delivery"
            shipped.containsMatchIn(body) -> DeliveryEvent.SHIPPED to "delivery.shipped"
            delivered.containsMatchIn(body) -> DeliveryEvent.DELIVERED to "delivery.delivered"
            else -> return null
        }
        val what = (labelledItem.find(body) ?: item.find(body))?.groupValues?.get(1)?.trim()
        return ParsedSms(
            smsClass = SmsClass.UPCOMING_DELIVERY,
            ruleId = id,
            upcomingKind = UpcomingKind.DELIVERY,
            title = sender.header + ": " + (what ?: body.take(40).trim()),
            deliveryEvent = event,
            trackingRef = trackingPatterns.firstNotNullOfOrNull { it.find(body)?.groupValues?.get(1) },
        )
    }
}
