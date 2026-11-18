package app.blue.sms.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlueTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(BlueIcons.Back, contentDescription = "Back")
                }
            }
        },
        actions = actions,
    )
}

/** Stand-in for tabs that are built in later phases. */
@Composable
fun PlaceholderScreen(title: String, message: String) {
    Scaffold(topBar = { BlueTopBar(title) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

object Fmt {
    private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
    private val inr = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))

    fun dateTime(millis: Long): String = dateTime.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    fun rupees(paise: Long): String = inr.format(paise / 100.0)
    private val inrWhole = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply { maximumFractionDigits = 0 }
    /** "₹48,210": for big headline numbers. */
    fun rupeesWhole(paise: Long): String = inrWhole.format(paise / 100.0)
    /** "17 Nov" for list rows. */
    fun dateShort(millis: Long): String = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    fun dateShortYear(millis: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    /** 1200 -> "8:00 PM". */
    fun clock(minutes: Int): String = java.time.LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    fun time(millis: Long): String = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun range(days: Int): String = when {
        days >= ALL_TIME_DAYS -> "All time"
        days == 1 -> "Last 24 hours"
        else -> "Last $days days"
    }

    const val ALL_TIME_DAYS = 36500

    fun method(m: app.blue.sms.domain.model.PaymentMethod): String = when (m) {
        app.blue.sms.domain.model.PaymentMethod.UPI -> "UPI"
        app.blue.sms.domain.model.PaymentMethod.CARD -> "Card"
        app.blue.sms.domain.model.PaymentMethod.NETBANKING -> "Net banking"
        app.blue.sms.domain.model.PaymentMethod.AUTO_DEBIT -> "Auto-debit"
        app.blue.sms.domain.model.PaymentMethod.ATM -> "ATM"
        app.blue.sms.domain.model.PaymentMethod.OTHER -> "Other"
    }

    fun plural(n: Int, one: String, many: String) = "$n " + if (n == 1) one else many
}

/** A link that opens Google Maps (app or web) at a point. */
fun mapsUrl(lat: Double, lng: Double): String = "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f".format(java.util.Locale.ROOT, lat, lng)

/** Google Maps search for a name, e.g. a shop. Needs no location permission. */
fun mapsSearchUrl(query: String): String = "https://www.google.com/maps/search/?api=1&query=" + java.net.URLEncoder.encode(query, "UTF-8")
