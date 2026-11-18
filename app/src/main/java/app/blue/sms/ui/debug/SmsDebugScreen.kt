package app.blue.sms.ui.debug

import app.blue.sms.ui.common.BlueIcons

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.db.SmsDao
import app.blue.sms.data.db.SmsFilter
import app.blue.sms.data.db.SmsRow
import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.scan.RerunRules
import app.blue.sms.domain.sms.SenderIds
import app.blue.sms.ui.common.BlueTopBar
import app.blue.sms.ui.common.Fmt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val LIMIT = 500

data class SmsListState(
    val filter: String = SmsFilter.ALL,
    val rows: List<SmsRow> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val busy: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SmsListViewModel @Inject constructor(
    private val smsDao: SmsDao,
    private val rerunRules: RerunRules,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val filter = MutableStateFlow(savedState.get<String>("startFilter") ?: SmsFilter.ALL)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val counts = combine(smsDao.observeClassCounts(), smsDao.observeStarredCount()) { list, starred ->
        val m = list.associate { (it.cls?.name ?: SmsFilter.UNDECIDED) to it.n }.toMutableMap()
        m[SmsFilter.ALL] = list.sumOf { it.n }
        m[SmsFilter.STARRED] = starred
        m.toMap()
    }

    val state: StateFlow<SmsListState> = combine(
        filter, filter.flatMapLatest { smsDao.observeRows(it, LIMIT) }, counts, busy, message,
    ) { f, rows, c, b, msg -> SmsListState(f, rows, c, b, msg) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmsListState())

    fun setFilter(f: String) { filter.value = f }

    fun toggleStar(row: SmsRow) {
        viewModelScope.launch { smsDao.setStarred(row.sms.id, !row.sms.isStarred, System.currentTimeMillis()) }
    }

    fun rerun() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = try {
                val c = rerunRules()
                "Rules re-run: ${c.newCards} cards · ${c.proposals} upcoming proposals (${c.closed} closed) · ${c.spam} spam · ${c.ignored} ignored · ${c.undecided} undecided"
            } catch (e: Exception) {
                "Re-run failed: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }
}

private val FILTERS = listOf(
    SmsFilter.ALL to "All",
    SmsClass.TRANSACTION.name to "Transactions",
    SmsClass.UPCOMING_FINANCE.name to "Upcoming money",
    SmsClass.UPCOMING_DELIVERY.name to "Deliveries",
    SmsClass.SPAM.name to "Spam",
    SmsClass.IGNORE.name to "Ignored",
    SmsFilter.UNDECIDED to "Undecided",
    SmsFilter.STARRED to "★ Starred",
)

/** Debug list (with filters) and the Starred section share this screen. */
@Composable
fun SmsListScreen(
    title: String,
    onBack: () -> Unit,
    showFilters: Boolean,
    onCheckAi: () -> Unit = {},
    vm: SmsListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            BlueTopBar(title, onBack = onBack, actions = {
                if (showFilters) {
                    androidx.compose.material3.TextButton(onClick = onCheckAi) { Text("Check AI", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = vm::rerun, enabled = !state.busy) {
                        Icon(BlueIcons.Refresh, contentDescription = "Re-run rules")
                    }
                }
            })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            if (showFilters) {
                item {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { open = true }) {
                            Text(
                                (FILTERS.firstOrNull { it.first == state.filter }?.second ?: "All") + " · ${state.counts[state.filter] ?: 0} ▾",
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            FILTERS.forEach { (key, label) ->
                                DropdownMenuItem(text = { Text("$label · ${state.counts[key] ?: 0}") }, onClick = { vm.setFilter(key); open = false })
                            }
                        }
                    }
                }
                state.message?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
                if (state.busy) item { Text("Re-running rules…", style = MaterialTheme.typography.bodySmall) }
                item {
                    val n = state.counts[state.filter] ?: 0
                    Text(
                        "$n SMS" + (if (n > LIMIT) " · showing newest $LIMIT" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.rows.isEmpty()) {
                item {
                    Text(
                        if (state.filter == SmsFilter.STARRED) "Nothing starred yet. Tap the star on any SMS to keep it here."
                        else "Nothing here yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.rows, key = { it.sms.id }) { row -> SmsRowCard(row, showRule = showFilters) { vm.toggleStar(row) } }
        }
    }
}

@Composable
private fun SmsRowCard(row: SmsRow, showRule: Boolean, onStar: () -> Unit) {
    val sms = row.sms
    Column {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(sms.sender, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(
                        Fmt.dateTime(sms.receivedAt),
                        if (showRule) typeLabel(sms.classification?.name) + (sms.ruleId?.let { " ($it)" } ?: "") else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                row.amountPaise?.let { amt ->
                    Text(
                        (if (row.direction == Direction.IN) "+" else "") + Fmt.rupees(amt) + (row.merchant?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(sms.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
            }
            IconButton(onClick = onStar) {
                Icon(
                    if (sms.isStarred) BlueIcons.StarFilled else BlueIcons.Star,
                    if (sms.isStarred) "Unstar" else "Star",
                    tint = if (sms.isStarred) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun typeLabel(cls: String?) = when (cls) {
    "TRANSACTION" -> "Transaction"; "UPCOMING_FINANCE" -> "Upcoming"; "UPCOMING_DELIVERY" -> "Parcel"
    "SPAM" -> "Spam"; "IGNORE" -> "Ignored"; else -> "Undecided"
}
