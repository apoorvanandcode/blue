package app.blue.sms.ui.permission

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.blue.sms.data.sms.hasSmsPermission
import app.blue.sms.ui.common.BlueTopBar

@Composable
fun PermissionHelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasSmsPermission(context)) }
    LifecycleResumeEffect(Unit) {
        granted = hasSmsPermission(context)
        onPauseOrDispose { }
    }

    Scaffold(topBar = { BlueTopBar("Allow SMS access", onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (granted) {
                Text("SMS access is on.", style = MaterialTheme.typography.titleLarge)
                Text("Go back and tap Scan.")
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to Blue") }
                return@Column
            }
            Text("Android is blocking SMS access for Blue", style = MaterialTheme.typography.titleLarge)
            Text(
                "Blue was installed from your Mac, not the Play Store, so Android may hide the SMS permission " +
                    "until you allow restricted settings. Blue only reads SMS. It never sends or deletes them."
            )
            Text("1. Tap Open app settings below.")
            Text("2. Tap ⋮ (three dots, top right) and then Allow restricted settings. Confirm with your PIN or fingerprint. If you don't see the three dots, skip this step.")
            Text("3. Tap Permissions, then SMS, then Allow.")
            Text("4. Come back to Blue. This screen updates on its own.")
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open app settings") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        }
    }
}
