package app.blue.sms.ui.review

import app.blue.sms.ui.common.BlueIcons

import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import app.blue.sms.ui.common.mapsUrl
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.blue.sms.data.db.ReviewItem
import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.review.Suggestion
import app.blue.sms.ui.common.Fmt
import app.blue.sms.ui.theme.Blue

/** A card plus the names behind its suggestions, ready to draw. */
data class ReviewUiItem(
    val item: ReviewItem,
    val suggestion: Suggestion,
    val categoryName: String?,
    val forWhomName: String?,
) {
    val id get() = item.txn.id
}

/** Money coming in is the only green in the app. */
val InGreen: Color = Blue.MoneyIn

fun amountText(item: ReviewItem): String? = item.txn.amountPaise?.let {
    (if (item.txn.direction == Direction.IN) "+" else "") + Fmt.rupees(it)
}

/** "Federal Bank ••4321", "HDFC Bank credit card ••1234". */
fun accountLabel(item: ReviewItem): String? = listOfNotNull(
    item.bank,
    when (item.accountType) {
        AccountType.CREDIT_CARD -> "credit card"
        AccountType.DEBIT_CARD -> "debit card"
        else -> null
    },
    item.last4?.let { "••$it" },
).joinToString(" ").ifEmpty { null }

/** The user's nickname if set, else the bank's name. */
fun merchantText(item: ReviewItem): String = item.nickname ?: item.txn.merchantRaw ?: item.txn.upiId ?: "Unknown merchant"

/** What nicknames are keyed by: the bank's name, or the UPI id when there's no name. Never changed by Blue. */
fun merchantKey(item: ReviewItem): String? = item.txn.merchantRaw?.takeIf { it.isNotBlank() } ?: item.txn.upiId?.takeIf { it.isNotBlank() }

@Composable
fun amountColor(item: ReviewItem): Color = when {
    item.txn.amountPaise == null -> MaterialTheme.colorScheme.onSurfaceVariant
    item.txn.direction == Direction.IN -> InGreen
    else -> MaterialTheme.colorScheme.onSurface
}

/** The big Review card: date, amount, merchant, account, then category and "for whom", then the SMS. */
@Composable
fun ReviewCardBody(ui: ReviewUiItem, full: Boolean, onStar: (() -> Unit)?) {
    val t = ui.item.txn
    Column(Modifier.padding(start = 20.dp, end = 8.dp, top = if (onStar == null) 22.dp else 12.dp, bottom = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(t.txnAt?.let(Fmt::dateTime), if (t.isRefund) "Refund" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onStar != null) {
                IconButton(onClick = onStar) {
                    Icon(
                        BlueIcons.Star,
                        if (ui.item.smsStarred == true) "Unstar SMS" else "Star SMS",
                        tint = if (ui.item.smsStarred == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        Column(Modifier.padding(end = 12.dp)) {
            Text(
                amountText(ui.item) ?: "Amount missing",
                style = if (full) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                color = amountColor(ui.item),
            )
            Text(
                merchantText(ui.item),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = if (full) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            val line = listOfNotNull(t.method?.let(Fmt::method), accountLabel(ui.item)).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 16.dp))
            SuggestionLine("Category", ui.categoryName, ui.suggestion.categoryFromMemory)
            SuggestionLine("For", ui.forWhomName, ui.suggestion.forWhomFromMemory)
            if (full && ui.item.smsBody != null) {
                Text(
                    "“${ui.item.smsBody}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

/** "Indiranagar, Bengaluru · View on map". The area name comes from Android's geocoder, the map is Google Maps. */
@Composable
fun PlaceLine(lat: Double, lng: Double, accuracy: Float?) {
    val context = LocalContext.current
    val place by produceState<String?>(null, lat, lng) { value = placeName(context, lat, lng) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Where", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(104.dp))
        Column(Modifier.weight(1f)) {
            Text(
                place ?: "%.4f, %.4f".format(java.util.Locale.ROOT, lat, lng),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "View on map" + (accuracy?.let { " · within ${it.roundToInt()} m" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl(lat, lng)))) }
                }.padding(vertical = 4.dp),
            )
        }
    }
}

/** No saved location: search the merchant's name on Google Maps instead. */
@Composable
fun FindOnMapLine(name: String) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Where", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(104.dp))
        Text(
            "Find on Google Maps",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.blue.sms.ui.common.mapsSearchUrl(name)))) }
            }.padding(vertical = 4.dp),
        )
    }
}

private suspend fun placeName(context: android.content.Context, lat: Double, lng: Double): String? =
    if (!Geocoder.isPresent()) null else withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                Geocoder(context).getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        val a = addresses.firstOrNull()
                        cont.resume(a?.let { listOfNotNull(it.featureName?.takeIf { f -> f != it.subThoroughfare && !f.all(Char::isDigit) }, it.subLocality, it.locality).distinct().take(2).joinToString(", ").ifEmpty { null } })
                    }
                    override fun onError(errorMessage: String?) { cont.resume(null) }
                })
            }.onFailure { cont.resume(null) }
        }
    }

@Composable
private fun SuggestionLine(label: String, value: String?, remembered: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.Start) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(104.dp))
        Text(
            value ?: "Choose",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (value == null) FontWeight.Medium else FontWeight.Normal,
            color = if (value == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (remembered) {
            Text(" · remembered", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}
