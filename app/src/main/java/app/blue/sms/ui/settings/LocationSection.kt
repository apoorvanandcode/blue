package app.blue.sms.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.blue.sms.data.location.PaymentLocation
import app.blue.sms.ui.common.PlainRow
import app.blue.sms.ui.common.RowText
import app.blue.sms.ui.common.SectionLabel

/** Which guide is open: the first explanation, or the steps for Android's own settings page. */
private enum class Guide { NONE, ONE_MORE_STEP, VIA_SETTINGS }

/**
 * "Save where I pay": the switch, plus the Android permissions it needs, with a guide for the
 * "Allow all the time" step. Android lets no app link straight to that choice, so Blue explains what
 * the next screen will show, asks Android, and if Android won't show it, opens Blue's app settings
 * with numbered steps. Coming back re-checks and shows "On".
 */
@Composable
fun LocationSection(on: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    // Re-read permissions whenever Blue comes back from Android's settings.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val foreground = remember(tick) { PaymentLocation.hasForeground(context) && PaymentLocation.hasReceiveSms(context) }
    val background = remember(tick) { PaymentLocation.hasBackground(context) }
    var guide by remember { mutableStateOf(Guide.NONE) }

    fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    // Android shows "Allow all the time" only on its own page, and after one "no" it stops showing even
    // that when asked: the request just returns false. Then we guide the user through app settings.
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (!granted) guide = Guide.VIA_SETTINGS
    }
    val askForeground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        tick++
        onChange(true)
        // Location allowed: go straight on to the "all the time" step instead of leaving a red message.
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            if (!PaymentLocation.hasBackground(context)) guide = Guide.ONE_MORE_STEP
        }
    }
    // Once everything is allowed, close any guide still open.
    LaunchedEffect(background) { if (background) guide = Guide.NONE }

    SectionLabel("Location")
    PlainRow(onClick = {
        if (on) onChange(false)
        else if (!foreground) askForeground.launch(
            arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        else { onChange(true); if (!background) guide = Guide.ONE_MORE_STEP }
    }) {
        RowText("Save where I pay", "Notes your location when a payment SMS arrives", titleWeight = FontWeight.Normal)
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.onSurface),
        )
    }
    if (on) {
        val (text, fix) = when {
            !foreground -> "Blue needs location and SMS alerts. Turn the switch off and on to allow them." to null
            !background -> "One more step: location must be set to \"Allow all the time\"." to "Show me how"
            else -> "On. Kept only on this phone, and deleted if you mark an entry as not a payment or a duplicate." to null
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (fix != null || !foreground) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (fix != null) {
            TextButton(onClick = { guide = Guide.ONE_MORE_STEP }, contentPadding = PaddingValues(0.dp)) {
                Text(fix, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }

    when (guide) {
        Guide.ONE_MORE_STEP -> AlertDialog(
            onDismissRequest = { guide = Guide.NONE },
            title = { Text("One more step") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Payment SMS usually arrive while Blue is closed. To note where you were, Blue needs location " +
                            "set to \"Allow all the time\".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LocationPromise()
                    Text("On the next screen, choose:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    LocationChoicesPicture()
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    guide = Guide.NONE
                    runCatching { askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                        .onFailure { guide = Guide.VIA_SETTINGS }
                }) { Text("Continue", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { guide = Guide.NONE }) { Text("Not now") } },
        )
        Guide.VIA_SETTINGS -> AlertDialog(
            onDismissRequest = { guide = Guide.NONE },
            title = { Text("Allow it in Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Android needs you to choose this in Blue's settings. It takes three taps:", style = MaterialTheme.typography.bodyMedium)
                    Text("Blue only notes where you are when a payment SMS arrives. It never tracks you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GuideStep(1, "Tap Permissions")
                    GuideStep(2, "Tap Location")
                    GuideStep(3, "Choose \"Allow all the time\"")
                    Text("Then come back to Blue. This page will say \"On\".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { openAppSettings() }) { Text("Open Blue's settings", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { guide = Guide.NONE }) { Text("Not now") } },
        )
        Guide.NONE -> Unit
    }
}

/** What Blue does and doesn't do with location, said plainly. */
@Composable
private fun LocationPromise() {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Blue never tracks you", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "It notes your location only at the moment a payment SMS arrives, so later you can see where you spent " +
                "the money and remember what it was for. Nothing else, ever. It stays on this phone, and is deleted " +
                "if you mark the entry as not a payment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GuideStep(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.SemiBold) }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

/** A small drawing of Android's location choices, with the one to pick highlighted. */
@Composable
private fun LocationChoicesPicture() {
    val choices = listOf("Allow all the time", "Allow only while using the app", "Ask every time", "Don't allow")
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp),
    ) {
        choices.forEachIndexed { i, label ->
            val pick = i == 0
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .then(if (pick) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .alpha(if (pick) 1f else 0.55f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(16.dp).border(2.dp, if (pick) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (pick) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) }
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (pick) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (pick) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
                if (pick) Text("  ← this one", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
