package app.blue.sms.ui.home

import app.blue.sms.ui.common.BlueIcons

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.blue.sms.data.sms.hasSmsPermission
import app.blue.sms.ui.common.Fmt
import app.blue.sms.ui.common.PlainRow
import app.blue.sms.ui.common.PrimaryButton
import app.blue.sms.ui.common.RowText
import app.blue.sms.ui.common.SectionLabel
import app.blue.sms.ui.upcoming.UpcomingHomeSection

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenStarred: () -> Unit,
    onOpenProposals: () -> Unit,
    onOpenReview: () -> Unit,
    onNeedSim: () -> Unit,
    onPermissionBlocked: () -> Unit,
    simJustChosen: Boolean,
    onSimChosenHandled: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current

    fun afterPermission() {
        if (state.subscriptionIds.isEmpty()) onNeedSim() else vm.scan()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> afterPermission()
            // Denied once: Android will still show the dialog next time.
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS) == true ->
                vm.showError("Blue needs SMS access to scan. Tap Scan again and choose Allow.")
            // Denied for good, or blocked as a restricted setting for a sideloaded app.
            else -> onPermissionBlocked()
        }
    }

    LaunchedEffect(simJustChosen) {
        if (simJustChosen) {
            onSimChosenHandled()
            vm.scan()
        }
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Blue", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(BlueIcons.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // This month
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp)) {
                Text("Spent in ${state.monthName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Fmt.rupeesWhole(state.monthSpendPaise), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 4.dp))
                if (state.lastMonthSoFarPaise > 0) {
                    Text(
                        "${Fmt.rupeesWhole(state.lastMonthSoFarPaise)} by this time in ${state.lastMonthName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // The main job: confirm what the scans found.
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (state.pendingCount > 0) {
                    PrimaryButton(
                        "Review " + Fmt.plural(state.pendingCount, "entry", "entries"),
                        onClick = onOpenReview,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Everything is reviewed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Scanning runs by itself; the manual scan is a small extra.
                SectionLabel("SMS scan")
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.lastScan?.let { "Last scan " + Fmt.dateShort(it.startedAt) + ", " + Fmt.time(it.startedAt) } ?: "Not scanned yet",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (state.dailyScan) "Scans by itself every day at ${Fmt.clock(state.dailyScanMinutes)}" else "Daily scan is off (Settings)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = !state.scanning,
                        onClick = {
                            if (hasSmsPermission(context)) afterPermission()
                            else permissionLauncher.launch(Manifest.permission.READ_SMS)
                        },
                    ) {
                        Text(if (state.scanning) "Scanning…" else "Scan now", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                state.lastScan?.error?.let { Text("Last scan failed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
                if (state.simLabel == null) {
                    Text("You'll choose which SMS to read on the first scan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(Modifier.padding(horizontal = 20.dp)) {
                UpcomingHomeSection(
                    active = state.upcoming,
                    proposals = state.proposals,
                    onOpenProposals = onOpenProposals,
                    onClose = vm::closeUpcoming,
                    onDismiss = vm::dismissUpcoming,
                )

                SectionLabel("More")
                PlainRow(onClick = onOpenStarred, chevron = true) {
                    RowText("Starred messages", titleWeight = FontWeight.Normal)
                    Text("${state.starredCount}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PlainRow(onClick = onOpenDebug, chevron = true) {
                    RowText("All scanned SMS", titleWeight = FontWeight.Normal)
                }
            }
        }
    }
}
