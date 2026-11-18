package app.blue.sms.ui.spam

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.db.SmsDao
import app.blue.sms.data.db.SmsMessageEntity
import app.blue.sms.data.db.SpamSenderDao
import app.blue.sms.data.db.SpamSenderEntity
import app.blue.sms.domain.sms.SenderIds
import app.blue.sms.ui.common.BlueTopBar
import app.blue.sms.ui.common.Fmt
import androidx.compose.material3.HorizontalDivider
import app.blue.sms.ui.review.TextTab
import app.blue.sms.ui.review.QuietLink
import app.blue.sms.ui.common.PrimaryButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Flagged SMS from one sender header, e.g. all "VM-HDFCBN-P" style senders as HDFCBN. */
data class SpamGroup(val header: String, val messages: List<SmsMessageEntity>, val onSpamList: Boolean)

data class SpamState(
    val loaded: Boolean = false,
    val toConfirm: List<SpamGroup> = emptyList(),
    val confirmed: List<SpamGroup> = emptyList(),
    val unticked: Set<String> = emptySet(),
)

@HiltViewModel
class SpamViewModel @Inject constructor(
    private val smsDao: SmsDao,
    private val spamSenderDao: SpamSenderDao,
) : ViewModel() {
    private val unticked = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<SpamState> = combine(smsDao.observeSpam(), spamSenderDao.observeAll(), unticked) { sms, senders, off ->
        val list = senders.map { it.sender }.toSet()
        val groups = sms.groupBy { SenderIds.parse(it.sender).header }
            .map { (h, m) -> SpamGroup(h, m, h in list) }
            .sortedByDescending { it.messages.size }
        SpamState(true, groups.filter { !it.onSpamList }, groups.filter { it.onSpamList }, off)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpamState())

    fun toggle(header: String) = unticked.update { if (header in it) it - header else it + header }
    fun selectAll(all: Boolean) {
        unticked.value = if (all) emptySet() else state.value.toConfirm.map { it.header }.toSet()
    }

    /** Ticked senders go on the spam list. Their SMS move to the Spam list. Nothing changes in Google Messages. */
    fun confirmTicked() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val headers = state.value.toConfirm.map { it.header }.filter { it !in unticked.value }
        spamSenderDao.insertAll(headers.map { SpamSenderEntity(it, now) })
    }

    fun notSpam(group: SpamGroup) = viewModelScope.launch {
        smsDao.markNotSpam(group.messages.map { it.id })
        spamSenderDao.delete(group.header)
    }

    fun removeFromList(header: String) = viewModelScope.launch { spamSenderDao.delete(header) }

    private inline fun <T> MutableStateFlow<T>.update(f: (T) -> T) { value = f(value) }
}

@Composable
fun SpamScreen(vm: SpamViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showConfirmed by remember { mutableStateOf(false) }
    val ticked = state.toConfirm.count { it.header !in state.unticked }
    val tickedSms = state.toConfirm.filter { it.header !in state.unticked }.sumOf { it.messages.size }

    Scaffold(
        bottomBar = {
            if (!showConfirmed && state.toConfirm.isNotEmpty()) {
                Surface {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PrimaryButton(
                            "Mark ${Fmt.plural(ticked, "sender", "senders")} as spam ($tickedSms SMS)",
                            onClick = vm::confirmTicked,
                            enabled = ticked > 0,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp)) {
            item { Text("Spam", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TextTab("To confirm · ${state.toConfirm.size}", !showConfirmed) { showConfirmed = false }
                    TextTab("Spam list · ${state.confirmed.size}", showConfirmed) { showConfirmed = true }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (showConfirmed) "New SMS from these senders are flagged on every scan." else "Nothing is deleted from your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    )
                    if (!showConfirmed && state.toConfirm.isNotEmpty()) {
                        val allTicked = state.unticked.isEmpty()
                        QuietLink(if (allTicked) "Untick all" else "Select all") { vm.selectAll(!allTicked) }
                    }
                }
            }
            val groups = if (showConfirmed) state.confirmed else state.toConfirm
            if (state.loaded && groups.isEmpty()) {
                item { Text(if (showConfirmed) "No spam senders yet." else "Nothing flagged.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
            }
            items(groups, key = { it.header }) { g ->
                SpamGroupRow(
                    g,
                    ticked = if (showConfirmed) null else g.header !in state.unticked,
                    onToggle = { vm.toggle(g.header) },
                    actionLabel = if (showConfirmed) "Remove" else "Not spam",
                    onAction = { if (showConfirmed) vm.removeFromList(g.header) else vm.notSpam(g) },
                )
            }
        }
    }
}

@Composable
private fun SpamGroupRow(g: SpamGroup, ticked: Boolean?, onToggle: () -> Unit, actionLabel: String, onAction: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.clickable { expanded = !expanded }) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
            if (ticked != null) Checkbox(checked = ticked, onCheckedChange = { onToggle() }, colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.onSurface))
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Text(g.header, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    Fmt.plural(g.messages.size, "SMS", "SMS") + " · latest " + Fmt.dateShort(g.messages.first().receivedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (if (expanded) g.messages.take(5) else g.messages.take(1)).forEach { m ->
                    Text(
                        m.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 6 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            QuietLink(actionLabel, onClick = onAction)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
