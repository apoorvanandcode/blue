package app.blue.sms.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.blue.sms.data.location.PaymentLocation
import app.blue.sms.ui.common.PlainRow
import app.blue.sms.ui.common.RowText
import app.blue.sms.ui.common.SectionLabel

/** "Save where I pay": the switch, plus the Android permissions it needs, one step at a time. */
@Composable
fun LocationSection(on: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    // Re-read permissions whenever Blue comes back from Android's settings.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val foreground = remember(tick) { PaymentLocation.hasForeground(context) && PaymentLocation.hasReceiveSms(context) }
    val background = remember(tick) { PaymentLocation.hasBackground(context) }

    val askForeground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        onChange(true)
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    SectionLabel("Location")
    PlainRow(onClick = {
        if (on) onChange(false)
        else if (!foreground) askForeground.launch(
            arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        else onChange(true)
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
            !background -> "Set location to \"Allow all the time\", so Blue can note it while closed." to "Allow"
            else -> "On. Kept only on this phone, and deleted if you mark an entry as not a payment or a duplicate." to null
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (fix != null || !foreground) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (fix != null) {
            TextButton(onClick = {
                runCatching { askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }.onFailure {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }
            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("$fix all the time", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
