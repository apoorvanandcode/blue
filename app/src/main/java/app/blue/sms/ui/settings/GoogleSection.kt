package app.blue.sms.ui.settings

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blue.sms.BuildConfig
import app.blue.sms.data.backup.BackupCodec
import app.blue.sms.data.backup.BackupFile
import app.blue.sms.data.backup.BackupRepository
import app.blue.sms.data.google.AuthResult
import app.blue.sms.data.google.DriveSync
import app.blue.sms.data.google.GoogleAuth
import app.blue.sms.ui.common.Fmt
import app.blue.sms.ui.common.RowText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

enum class DriveAction { EXPORT, BACKUP, RESTORE }

sealed interface DriveUi {
    data object Idle : DriveUi
    data class Working(val text: String) : DriveUi
    data class Consent(val intent: PendingIntent) : DriveUi
    data class ConfirmRestore(val backup: BackupFile, val savedAt: String) : DriveUi
    data class Done(val text: String, val link: String? = null) : DriveUi
    data class Failed(val text: String) : DriveUi
}

@HiltViewModel
class DriveViewModel @Inject constructor(
    private val auth: GoogleAuth,
    private val sync: DriveSync,
    private val backups: BackupRepository,
) : ViewModel() {
    val state = MutableStateFlow<DriveUi>(DriveUi.Idle)
    private var pending: DriveAction? = null

    fun start(action: DriveAction) {
        pending = action
        state.value = DriveUi.Working("Connecting to Google…")
        viewModelScope.launch { handle(auth.authorize()) }
    }

    fun onConsent(data: Intent?) = viewModelScope.launch { handle(auth.fromConsent(data)) }

    private suspend fun handle(r: AuthResult) {
        when (r) {
            is AuthResult.NeedsConsent -> state.value = DriveUi.Consent(r.intent)
            is AuthResult.Failed -> state.value = DriveUi.Failed(r.message)
            is AuthResult.Token -> perform(pending ?: return, r.accessToken)
        }
    }

    private suspend fun perform(action: DriveAction, token: String) {
        try {
            when (action) {
                DriveAction.EXPORT -> {
                    state.value = DriveUi.Working("Creating the Google Sheet…")
                    val (url, n) = sync.exportSheet(token)
                    state.value = DriveUi.Done("Exported ${Fmt.plural(n, "entry", "entries")} to a new Google Sheet.", url)
                }
                DriveAction.BACKUP -> {
                    state.value = DriveUi.Working("Backing up to Google Drive…")
                    val b = sync.backup(token)
                    state.value = DriveUi.Done("Backed up to \"${DriveSync.BACKUP_NAME}\" in your Drive: ${b.summary()}.")
                }
                DriveAction.RESTORE -> {
                    state.value = DriveUi.Working("Looking for a backup in Drive…")
                    val file = sync.findBackup(token) ?: run {
                        state.value = DriveUi.Failed("No \"${DriveSync.BACKUP_NAME}\" made by Blue in your Drive.")
                        return
                    }
                    state.value = DriveUi.Working("Downloading the backup…")
                    val b = sync.fetch(token, file)
                    state.value = DriveUi.ConfirmRestore(b, Fmt.dateTime(b.createdAt))
                }
            }
        } catch (e: Exception) {
            state.value = DriveUi.Failed(e.message ?: e.toString())
        }
    }

    fun confirmRestore(b: BackupFile) = viewModelScope.launch {
        state.value = DriveUi.Working("Restoring…")
        state.value = try {
            sync.restore(b)
            DriveUi.Done("Restored: ${b.summary()}.")
        } catch (e: Exception) {
            DriveUi.Failed("Restore failed, nothing was changed: ${e.message}")
        }
    }

    /** Backs up to memory, restores it, and checks nothing changed. No Drive involved. */
    fun checkOnPhone() = viewModelScope.launch {
        state.value = DriveUi.Working("Checking backup and restore on this phone…")
        state.value = try {
            val before = backups.snapshot(BuildConfig.VERSION_NAME)
            val copy = BackupCodec.decode(BackupCodec.encode(before))
            backups.restore(copy)
            val after = backups.snapshot(BuildConfig.VERSION_NAME).copy(createdAt = before.createdAt)
            if (after == before) DriveUi.Done("Backup check passed ✓ Everything came back identical: ${before.summary()}.")
            else DriveUi.Failed("Backup check found a difference. Please tell Claude.")
        } catch (e: Exception) {
            DriveUi.Failed("Backup check failed: ${e.message}")
        }
    }

    fun dismiss() { state.value = DriveUi.Idle }
}

@Composable
fun GoogleSection(vm: DriveViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.onConsent(it.data) }
    LaunchedEffect(state) {
        (state as? DriveUi.Consent)?.let { consent.launch(IntentSenderRequest.Builder(it.intent).build()) }
    }
    val busy = state is DriveUi.Working || state is DriveUi.Consent

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        app.blue.sms.ui.common.SectionLabel("Google Drive")
        Text(
            "Blue can only see the files it creates in your Drive. Nothing runs on its own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        app.blue.sms.ui.common.PlainRow(onClick = { if (!busy) vm.start(DriveAction.EXPORT) }, chevron = true) { RowText("Export to Google Sheets", titleWeight = FontWeight.Normal) }
        app.blue.sms.ui.common.PlainRow(onClick = { if (!busy) vm.start(DriveAction.BACKUP) }, chevron = true) { RowText("Back up now", titleWeight = FontWeight.Normal) }
        app.blue.sms.ui.common.PlainRow(onClick = { if (!busy) vm.start(DriveAction.RESTORE) }, chevron = true) { RowText("Restore from backup", titleWeight = FontWeight.Normal) }
        app.blue.sms.ui.common.PlainRow(onClick = { if (!busy) vm.checkOnPhone() }, chevron = true) { RowText("Check backup on this phone", "No Drive needed", titleWeight = FontWeight.Normal) }

        when (val s = state) {
            is DriveUi.Working -> Text(s.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is DriveUi.Consent -> Text("Waiting for Google…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is DriveUi.Done -> {
                Text(s.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.link?.let { url ->
                    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Open the sheet") }
                }
            }
            is DriveUi.Failed -> Text(s.text, color = MaterialTheme.colorScheme.error)
            else -> {}
        }
    }

    (state as? DriveUi.ConfirmRestore)?.let { c ->
        AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Replace all data on this phone?") },
            text = {
                Text(
                    "The backup from ${c.savedAt} (Blue ${c.backup.appVersion}) has ${c.backup.summary()}.\n\n" +
                        "Everything Blue has on this phone now will be replaced by it. This can't be undone. Your SMS in Google Messages are not touched."
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmRestore(c.backup) }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Cancel") } },
        )
    }
}
