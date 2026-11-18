package app.blue.sms.ui.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.db.CategoryDao
import app.blue.sms.data.db.ForWhomDao
import app.blue.sms.data.db.TransactionDao
import app.blue.sms.domain.insights.Insights
import app.blue.sms.domain.insights.PeriodKind
import app.blue.sms.domain.insights.Totals
import app.blue.sms.domain.insights.countsInTotals
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.TxnStatus
import app.blue.sms.ui.common.BlueTopBar
import app.blue.sms.ui.common.SectionLabel
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
import app.blue.sms.ui.common.Fmt
import app.blue.sms.ui.common.PeriodBar
import app.blue.sms.ui.common.PeriodHolder
import app.blue.sms.ui.review.InGreen
import app.blue.sms.ui.review.ReviewListRow
import app.blue.sms.ui.review.ReviewUiItem
import app.blue.sms.ui.review.decorate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import javax.inject.Inject

enum class StatusFilter(val label: String) {
    ALL("All"), COUNTED("Counted"), CONFIRMED("Confirmed"), PENDING("To review"), SKIPPED("Skipped"), EXCLUDED("Not counted");

    fun matches(s: TxnStatus) = when (this) {
        ALL -> true
        COUNTED -> s.countsInTotals()
        CONFIRMED -> s == TxnStatus.CONFIRMED
        PENDING -> s == TxnStatus.PENDING
        SKIPPED -> s == TxnStatus.SKIPPED
        EXCLUDED -> !s.countsInTotals()
    }
}

data class TxnListState(
    val items: List<ReviewUiItem> = emptyList(),
    val totals: Totals = Totals(),
    val filter: StatusFilter = StatusFilter.ALL,
    val loaded: Boolean = false,
)

/** "No filter" marker for optional Long nav args. */
const val NO_ARG = Long.MIN_VALUE

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    txnDao: TransactionDao,
    categoryDao: CategoryDao,
    forWhomDao: ForWhomDao,
    savedState: SavedStateHandle,
) : ViewModel() {
    /** Set when opened from an Insights category row. -1 = uncategorised. */
    private val categoryId = savedState.get<Long>("categoryId")?.takeIf { it != NO_ARG }
    private val direction = savedState.get<String>("direction")?.takeIf { it.isNotEmpty() }?.let(Direction::valueOf)

    val periods = PeriodHolder(savedState, if (categoryId != null) PeriodKind.MONTH else PeriodKind.ALL)
    /** Entries shows everything; each row says if it is not confirmed. */
    // An Insights drill-down lists only what Insights counted, so the numbers match.
    private val filter = MutableStateFlow(if (categoryId != null) StatusFilter.COUNTED else StatusFilter.ALL)

    val state: StateFlow<TxnListState> = combine(
        periods.period.flatMapLatest { p -> p.millis(ZoneId.systemDefault()).let { (from, to) -> txnDao.observeRange(from, to) } },
        categoryDao.observeAll(),
        forWhomDao.observeAll(),
        filter,
    ) { items, cats, people, f ->
        val shown = items.filter { i ->
            f.matches(i.txn.status) &&
                (categoryId == null || (i.txn.categoryId ?: Insights.UNCATEGORISED) == categoryId) &&
                (direction == null || i.txn.direction == direction)
        }
        TxnListState(decorate(shown, cats, people), Insights.totals(shown.filter { it.txn.status.countsInTotals() }), f, true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxnListState())

    fun setFilter(f: StatusFilter) { filter.value = f }
}

@Composable
fun TransactionsScreen(
    title: String,
    onBack: (() -> Unit)?,
    onOpenEdit: (Long) -> Unit,
    vm: TransactionsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val period by vm.periods.period.collectAsStateWithLifecycle()
    // Day headers: "Today", "Yesterday", "16 Nov".
    val today = java.time.LocalDate.now()
    val zone = java.time.ZoneId.systemDefault()
    val grouped = state.items.groupBy { ui ->
        (ui.item.txn.txnAt ?: ui.item.txn.createdAt).let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    }
    Scaffold(topBar = { if (onBack != null) BlueTopBar(title, onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (onBack == null) {
                item { Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)) }
            }
            if (onBack != null) {
                // Category drill-down: the category's total first, as in the design.
                item {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)) {
                        // Totals leave out duplicates and non-transactions, as Insights does.
                        val total = if (state.totals.inPaise > state.totals.outPaise) state.totals.inPaise else state.totals.outPaise
                        Text(Fmt.rupeesWhole(total), style = MaterialTheme.typography.headlineLarge)
                        Text(Fmt.plural(state.items.size, "entry", "entries"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { PeriodBar(period, allowAll = true, onChoose = vm.periods::choose, onShift = vm.periods::shift, onCustom = vm.periods::custom) }
            if (state.loaded && state.items.isEmpty()) {
                item { Text("Nothing here for this period.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            grouped.forEach { (day, rows) ->
                item(key = "day-$day") {
                    SectionLabel(
                        when (day) {
                            today -> "Today"
                            today.minusDays(1) -> "Yesterday"
                            else -> day.format(java.time.format.DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMM" else "d MMM yyyy", java.util.Locale.ENGLISH))
                        },
                        Modifier.padding(horizontal = 20.dp),
                    )
                }
                items(rows, key = { it.id }) { ui ->
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        ReviewListRow(ui, onClick = { onOpenEdit(ui.id) }) {
                            if (ui.item.txn.status != TxnStatus.CONFIRMED) {
                                Text(statusLabel(ui.item.txn.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(s: TxnStatus) = when (s) {
    TxnStatus.CONFIRMED -> ""
    TxnStatus.PENDING -> "to review"
    TxnStatus.SKIPPED -> "skipped"
    TxnStatus.DUPLICATE -> "duplicate"
    TxnStatus.NOT_A_TRANSACTION -> "not a payment"
    TxnStatus.INTERNAL_TRANSFER -> "own transfer"
}
