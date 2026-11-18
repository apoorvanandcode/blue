@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.blue.sms.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blue.sms.BuildConfig
import app.blue.sms.data.prefs.AppSettings
import app.blue.sms.data.prefs.SettingsRepository
import app.blue.sms.data.sms.simLabels
import app.blue.sms.data.secure.SecretStore
import app.blue.sms.data.db.TransactionDao
import app.blue.sms.domain.ai.AiExtractor
import app.blue.sms.domain.ai.AiOutcome
import app.blue.sms.domain.parse.Money
import app.blue.sms.domain.parse.SmsInput
import app.blue.sms.work.AiScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.blue.sms.ui.common.BlueTopBar
import androidx.compose.material3.AlertDialog
import app.blue.sms.ui.common.SectionLabel
import app.blue.sms.ui.common.RowText
import app.blue.sms.ui.common.PlainRow
import app.blue.sms.ui.common.Fmt
import androidx.compose.material3.TextButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val secrets: SecretStore,
    private val aiScheduler: AiScheduler,
    private val txnDao: TransactionDao,
    private val ai: AiExtractor,
    private val smsDao: app.blue.sms.data.db.SmsDao,
    private val aiStatus: app.blue.sms.work.AiStatus,
    private val dailyScan: app.blue.sms.work.DailyScanScheduler,
) : ViewModel() {
    fun setDailyScan(on: Boolean) = viewModelScope.launch {
        repo.setDailyScan(on)
        if (on) dailyScan.scheduleNext(repo.settings.first().dailyScanMinutes) else dailyScan.cancel()
    }

    fun setDailyScanTime(minutes: Int) = viewModelScope.launch {
        repo.setDailyScanMinutes(minutes)
        repo.setDailyScan(true)
        dailyScan.scheduleNext(minutes)
    }
    val problem: StateFlow<String?> = aiStatus.problem
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasKey: StateFlow<Boolean> = secrets.hasGeminiKey
    val queue: StateFlow<Int> = txnDao.observeAiQueueSize().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val aiMessage = MutableStateFlow<String?>(null)

    fun setRangeDays(days: Int) { viewModelScope.launch { repo.setScanRangeDays(days) } }

    fun saveKey(key: String) = viewModelScope.launch {
        if (key.isBlank()) return@launch
        secrets.saveGeminiKey(key)
        aiStatus.setProblem(null)
        smsDao.aiResetAll()
        txnDao.markRuleCardsForAi()
        aiScheduler.enqueue()
        aiMessage.value = "Key saved. Tap Test key to check it."
    }

    fun removeKey() { secrets.clearGeminiKey(); aiMessage.value = "Key removed. Blue uses rules only." }

    fun setModel(model: String) = viewModelScope.launch {
        if (model.isNotBlank()) {
            repo.setGeminiModel(model)
            aiStatus.setProblem(null)
            smsDao.aiResetAll()
            aiScheduler.enqueue()
            aiMessage.value = "Model saved."
        }
    }

    fun processNow() { aiStatus.setProblem(null); aiScheduler.enqueue(); aiMessage.value = "Gemini queue started. It runs in the background." }

    /** Sends one made-up SMS (no personal data) to check the key and model. */
    fun testKey() = viewModelScope.launch {
        aiMessage.value = "Testing…"
        val sample = SmsInput(
            "AD-FEDBNK-S",
            "Debited Rs 10.00 from a/c X0000 on 18Nov26 10:00 via UPI to TEST SHOP. Ref 123456789012. -Federal Bank",
            System.currentTimeMillis(),
        )
        when (val o = ai.extract(sample)) {
            is AiOutcome.Ok -> {
                aiStatus.setProblem(null)
                aiScheduler.enqueue()
                val amount = o.parsed.amountPaise?.let { "₹" + Money.format(it) } ?: "no amount"
                aiMessage.value = "Key works ✓ Gemini read: ${o.parsed.smsClass?.name?.lowercase()}, $amount, to ${o.parsed.merchant ?: "?"}"
            }
            is AiOutcome.Failed -> aiMessage.value = "Test failed: ${o.reason}"
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChooseSim: () -> Unit,
    onManageCategories: () -> Unit,
    onManageForWhom: () -> Unit,
    onManageSpam: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var daysText by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(settings?.scanRangeDays) { settings?.let { daysText = it.scanRangeDays.toString() } }

    var rangeOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    Scaffold(topBar = { BlueTopBar("Settings", onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Section("Scanning")
            PlainRow(onClick = onChooseSim, chevron = true) {
                RowText("Messages to read", titleWeight = FontWeight.Normal)
                Text(
                    settings?.smsSubscriptionIds?.let { ids ->
                        when {
                            ids.isEmpty() -> "Not chosen"
                            app.blue.sms.domain.sms.ALL_SIMS in ids -> "All SMS"
                            else -> ids.mapNotNull { app.blue.sms.data.sms.slotNumberFor(it) }.singleOrNull()?.let { "SIM $it only" } ?: "Some SIMs"
                        }
                    } ?: "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PlainRow(onClick = { vm.setDailyScan(settings?.dailyScan != true) }) {
                RowText("Scan automatically every day", "Reads all SMS since the last scan", titleWeight = FontWeight.Normal)
                androidx.compose.material3.Switch(
                    checked = settings?.dailyScan == true,
                    onCheckedChange = { vm.setDailyScan(it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.onSurface),
                )
            }
            if (settings?.dailyScan == true) {
                PlainRow(onClick = { timeOpen = true }, chevron = true) {
                    RowText("Daily scan time", titleWeight = FontWeight.Normal)
                    Text(Fmt.clock(settings?.dailyScanMinutes ?: 1200), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PlainRow(onClick = { rangeOpen = true }, chevron = true) {
                RowText("Manual scan range", titleWeight = FontWeight.Normal)
                Text(Fmt.plural(settings?.scanRangeDays ?: 7, "day", "days"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("Review")
            PlainRow(onClick = onManageCategories, chevron = true) { RowText("Categories", titleWeight = FontWeight.Normal) }
            PlainRow(onClick = onManageForWhom, chevron = true) { RowText("For whom: tags and contacts", titleWeight = FontWeight.Normal) }
            PlainRow(onClick = onManageSpam, chevron = true) { RowText("Spam senders", titleWeight = FontWeight.Normal) }

            GeminiSection(vm)
            GoogleSection()

            Section("About")
            PlainRow { RowText("Version", titleWeight = FontWeight.Normal); Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    if (timeOpen) {
        val m = settings?.dailyScanMinutes ?: 1200
        val ts = androidx.compose.material3.rememberTimePickerState(m / 60, m % 60, is24Hour = false)
        AlertDialog(
            onDismissRequest = { timeOpen = false },
            title = { Text("Daily scan time") },
            text = { androidx.compose.material3.TimePicker(ts) },
            confirmButton = { TextButton(onClick = { vm.setDailyScanTime(ts.hour * 60 + ts.minute); timeOpen = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { timeOpen = false }) { Text("Cancel") } },
        )
    }

    if (rangeOpen) {
        AlertDialog(
            onDismissRequest = { rangeOpen = false },
            title = { Text("Default scan range") },
            text = {
                OutlinedTextField(
                    value = daysText,
                    onValueChange = { v -> daysText = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Days") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { daysText.toIntOrNull()?.takeIf { it > 0 }?.let { vm.setRangeDays(it) }; rangeOpen = false },
                    enabled = daysText.toIntOrNull()?.let { it > 0 } == true,
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { rangeOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) = SectionLabel(title)

@Composable
private fun GeminiSection(vm: SettingsViewModel) {
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val message by vm.aiMessage.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var keyDialog by remember { mutableStateOf(false) }
    var modelDialog by remember { mutableStateOf(false) }

    Section("Gemini")
    PlainRow(onClick = { keyDialog = true }, chevron = true) {
        RowText("API key", titleWeight = FontWeight.Normal)
        Text(if (hasKey) "Saved, encrypted" else "Not set", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    PlainRow(onClick = { modelDialog = true }, chevron = true) {
        RowText("Model", titleWeight = FontWeight.Normal)
        Text(settings?.geminiModel ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    PlainRow(onClick = if (hasKey && queue > 0) vm::processNow else null) {
        RowText("Waiting for Gemini", titleWeight = FontWeight.Normal)
        Text(
            if (queue == 0) "None" else Fmt.plural(queue, "SMS", "SMS") + if (hasKey) " · run now" else "",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        "Gemini sees one SMS at a time, only when the rules need help. OTPs are never sent.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
    )
    if (hasKey) problem?.let { Text("Paused: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }

    if (keyDialog) {
        var keyText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { keyDialog = false },
            title = { Text("Gemini API key") },
            text = {
                Column {
                    if (hasKey) Text("A key is saved, encrypted on this phone.", style = MaterialTheme.typography.bodyMedium)
                    else OutlinedTextField(
                        value = keyText, onValueChange = { keyText = it.trim() },
                        label = { Text("Paste API key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                if (hasKey) TextButton(onClick = { vm.testKey(); keyDialog = false }) { Text("Test key") }
                else TextButton(onClick = { vm.saveKey(keyText); keyDialog = false }, enabled = keyText.length > 10) { Text("Save") }
            },
            dismissButton = {
                if (hasKey) TextButton(onClick = { vm.removeKey(); keyDialog = false }) { Text("Remove key") }
                else TextButton(onClick = { keyDialog = false }) { Text("Cancel") }
            },
        )
    }
    if (modelDialog) {
        var modelText by remember { mutableStateOf(settings?.geminiModel ?: "") }
        AlertDialog(
            onDismissRequest = { modelDialog = false },
            title = { Text("Gemini model") },
            text = { OutlinedTextField(value = modelText, onValueChange = { modelText = it.trim() }, label = { Text("Model") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.setModel(modelText); modelDialog = false }, enabled = modelText.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { modelDialog = false }) { Text("Cancel") } },
        )
    }
}
