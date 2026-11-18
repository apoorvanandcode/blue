package app.blue.sms.ui.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.prefs.SettingsRepository
import app.blue.sms.data.secure.SecretStore
import app.blue.sms.domain.ai.AiCheckRunner
import app.blue.sms.domain.ai.AiCompare
import app.blue.sms.domain.ai.CheckRow
import app.blue.sms.ui.common.BlueTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiCheckState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val rows: List<CheckRow> = emptyList(),
    val error: String? = null,
    val finished: Boolean = false,
)

@HiltViewModel
class AiCheckViewModel @Inject constructor(
    private val runner: AiCheckRunner,
    secrets: SecretStore,
    settings: SettingsRepository,
) : ViewModel() {
    val hasKey: StateFlow<Boolean> = secrets.hasGeminiKey
    val model: StateFlow<String> = settings.settings.map { it.geminiModel }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val state = MutableStateFlow(AiCheckState())
    private var job: Job? = null

    fun start(count: Int) {
        job?.cancel()
        state.value = AiCheckState(running = true)
        job = viewModelScope.launch {
            try {
                runner.run(count) { done, total, row ->
                    state.value = state.value.copy(done = done, total = total, rows = state.value.rows + row)
                }
                state.value = state.value.copy(running = false, finished = true)
            } catch (e: IllegalStateException) {
                state.value = state.value.copy(running = false, error = e.message)
            }
        }
    }

    fun stop() { job?.cancel(); state.value = state.value.copy(running = false, finished = true) }
}

@Composable
fun AiCheckScreen(onBack: () -> Unit, vm: AiCheckViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    var count by remember { mutableStateOf(50) }
    var onlyDiffs by remember { mutableStateOf(true) }
    val summary = AiCompare.summarise(state.rows)

    Scaffold(topBar = { BlueTopBar("Check Gemini vs rules", onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Takes your latest transaction SMS, reads each with the rules (on the phone) and with $model, and lists where they disagree. Nothing in your data changes. One SMS per request; 50 SMS cost well under ₹1. Keep this screen open while it runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(20, 50, 100).forEach { n ->
                        FilterChip(selected = count == n, onClick = { count = n }, enabled = !state.running, label = { Text("$n SMS") })
                    }
                }
            }
            item {
                if (state.running) app.blue.sms.ui.common.SecondaryButton("Stop", onClick = vm::stop, modifier = Modifier.fillMaxWidth())
                else app.blue.sms.ui.common.PrimaryButton(if (hasKey) "Run check" else "Add a Gemini key in Settings first", onClick = { vm.start(count) }, enabled = hasKey, modifier = Modifier.fillMaxWidth())
            }
            if (state.running && state.total > 0) {
                item {
                    Column {
                        LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                        Text("${state.done} of ${state.total}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            state.error?.let { item { Text("Stopped: $it", color = MaterialTheme.colorScheme.error) } }
            if (state.rows.isNotEmpty()) {
                item {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        run {
                            Text("${summary.agreePct}%", style = MaterialTheme.typography.displaySmall)
                            Text("agree fully", style = MaterialTheme.typography.bodyMedium)
                            Text("${summary.checked} checked · ${summary.agreed} agree · ${summary.checked - summary.agreed - summary.failed} differ · ${summary.failed} failed")
                            Text(
                                AiCompare.FIELDS.joinToString(" · ") { f -> "$f ${summary.byField[f] ?: 0}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.finished) {
                                Text(verdict(summary.agreePct, summary.byField), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = onlyDiffs, onClick = { onlyDiffs = true }, label = { Text("Differences") })
                        FilterChip(selected = !onlyDiffs, onClick = { onlyDiffs = false }, label = { Text("All") })
                    }
                }
            }
            val shown = if (onlyDiffs) state.rows.filter { it.diffs.isNotEmpty() || it.failure != null } else state.rows
            items(shown, key = { it.smsId }) { CheckRowCard(it) }
        }
    }
}

private fun verdict(pct: Int, byField: Map<String, Int>): String {
    val serious = (byField[AiCompare.AMOUNT] ?: 0) + (byField[AiCompare.TYPE] ?: 0)
    return when {
        pct >= 90 && serious == 0 -> "Looks good enough: no amount or type mistakes. Keep the current model."
        serious <= 2 -> "Mostly fine. Check the differences below: if Gemini is the one that's wrong on amounts, try gemini-3.5-flash-lite."
        else -> "Several amount or type differences. Check who's right below; if it's mostly Gemini, try gemini-3.5-flash-lite in Settings."
    }
}

@Composable
private fun CheckRowCard(row: CheckRow) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.sender, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            when {
                row.failure != null -> Text("Gemini failed: ${row.failure}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                row.diffs.isEmpty() -> Text("Agree", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> row.diffs.forEach { d -> Text("${d.field.replaceFirstChar { it.uppercase() }}: rules “${d.rules}” · Gemini “${d.ai}”", style = MaterialTheme.typography.bodySmall) }
            }
            Text(row.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (open) 20 else 1, overflow = TextOverflow.Ellipsis)
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
