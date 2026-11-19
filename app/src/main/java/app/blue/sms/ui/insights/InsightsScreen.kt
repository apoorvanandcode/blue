package app.blue.sms.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import app.blue.sms.ui.common.BlueIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.db.TransactionDao
import app.blue.sms.domain.insights.BreakdownRow
import app.blue.sms.domain.insights.Insights
import app.blue.sms.domain.insights.InsightsResult
import app.blue.sms.domain.insights.Period
import app.blue.sms.domain.insights.PeriodKind
import app.blue.sms.domain.model.Direction
import app.blue.sms.ui.common.BlueTopBar
import app.blue.sms.ui.common.Fmt
import app.blue.sms.ui.review.TextTab
import app.blue.sms.ui.common.SectionLabel
import app.blue.sms.ui.common.RowText
import app.blue.sms.ui.common.PlainRow
import app.blue.sms.ui.common.PeriodBar
import app.blue.sms.ui.common.PeriodHolder
import app.blue.sms.ui.review.InGreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

data class InsightsState(
    val period: Period? = null,
    val previous: Period? = null,
    val direction: Direction = Direction.OUT,
    val result: InsightsResult? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val txnDao: TransactionDao,
    savedState: SavedStateHandle,
) : ViewModel() {
    val periods = PeriodHolder(savedState, PeriodKind.MONTH)
    private val direction = MutableStateFlow(Direction.OUT)
    private val zone = ZoneId.systemDefault()

    val state: StateFlow<InsightsState> = periods.period.flatMapLatest { p ->
        val prev = p.previous(LocalDate.now())
        val (from, to) = p.millis(zone)
        combine(
            txnDao.observeRange(from, to),
            prev?.millis(zone)?.let { (pf, pt) -> txnDao.observeRange(pf, pt) } ?: flowOf(null),
            direction,
        ) { cur, before, dir -> InsightsState(p, prev, dir, Insights.compute(cur, before, dir)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState())

    fun setDirection(d: Direction) { direction.value = d }
}

@Composable
fun InsightsScreen(
    onOpenCategory: (categoryId: Long, name: String, direction: Direction, period: Period) -> Unit,
    vm: InsightsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val period by vm.periods.period.collectAsStateWithLifecycle()
    val r = state.result
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Insights", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
            PeriodBar(period, allowAll = false, onChoose = vm.periods::choose, onShift = vm.periods::shift, onCustom = vm.periods::custom)
            if (r == null) return@Column
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel {
                    Text("Spent", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Fmt.rupeesWhole(r.totals.outPaise), style = MaterialTheme.typography.displaySmall)
                    r.previous?.let { prev ->
                        Text(
                            compareLine(r.totals.outPaise, prev.outPaise, state.previous),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface, thickness = 2.dp, modifier = Modifier.padding(vertical = 14.dp))
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("Received", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Fmt.rupeesWhole(r.totals.inPaise), style = MaterialTheme.typography.headlineSmall, color = InGreen)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Net", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text((if (r.totals.net < 0) "−" else "+") + Fmt.rupeesWhole(abs(r.totals.net)), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    val notes = listOfNotNull(
                        r.unconfirmed.takeIf { it > 0 }?.let { "Includes " + Fmt.plural(it, "entry", "entries") + " not confirmed yet." },
                        r.missingAmount.takeIf { it > 0 }?.let { Fmt.plural(it, "entry has", "entries have") + " no amount yet." },
                    )
                    if (notes.isNotEmpty()) {
                        Text(notes.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                Row(Modifier.padding(start = 4.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TextTab("Spent", state.direction == Direction.OUT) { vm.setDirection(Direction.OUT) }
                    TextTab("Received", state.direction == Direction.IN) { vm.setDirection(Direction.IN) }
                }
                val compare = state.previous != null
                Breakdown("By category", r.byCategory, compare) { row ->
                    row.categoryId?.let { onOpenCategory(it, row.label, state.direction, period) }
                }
                Breakdown("Paid from", r.byMethod, compare)
                Breakdown("For whom", r.byForWhom, compare)
                Breakdown("Top merchants", r.topMerchants, compare)
            }
        }
    }
}

/** A soft grey block, so each part of Insights reads as its own section. */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        content = content,
    )
}

/** "12% less than 1–18 Oct (₹54,800)". */
private fun compareLine(now: Long, before: Long, previous: Period?): String {
    val label = previous?.shortLabel() ?: "before"
    if (before == 0L) return "Nothing spent in $label"
    val pct = ((now - before) * 100.0 / before).roundToInt()
    val word = when {
        pct > 0 -> "$pct% more than"
        pct < 0 -> "${-pct}% less than"
        else -> "Same as"
    }
    return "$word $label (${Fmt.rupeesWhole(before)})"
}

@Composable
private fun Breakdown(title: String, rows: List<BreakdownRow>, compare: Boolean, onClick: ((BreakdownRow) -> Unit)? = null) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
        if (rows.isEmpty()) {
            Text("Nothing in this period.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }
        val total = rows.sumOf { it.amountPaise }.coerceAtLeast(1)
        val topShare = (rows.maxOfOrNull { it.amountPaise } ?: 0L).toFloat() / total
        val shown = HeatFold.shownCount(rows.map { it.amountPaise })
        var open by rememberSaveable(title) { mutableStateOf(false) }
        val visible = if (open) rows else rows.take(shown)
        visible.forEach { row -> BreakdownLine(row, row.amountPaise.toFloat() / total, topShare, compare, onClick) }

        val rest = rows.drop(shown)
        if (rest.isNotEmpty()) {
            val restShare = rest.sumOf { it.amountPaise }.toFloat() / total
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (open) "Show less" else "${rest.size} more · ${sharePercent(restShare)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!open) Text(Fmt.rupeesWhole(rest.sumOf { it.amountPaise }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp))
                Icon(if (open) BlueIcons.ChevronUp else BlueIcons.ChevronDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** One row with its heat wash. The wash comes from the row's share only, so it never changes when the list folds or opens. */
@Composable
private fun BreakdownLine(row: BreakdownRow, share: Float, topShare: Float, compare: Boolean, onClick: ((BreakdownRow) -> Unit)?) {
    val heat = HeatFold.alpha(share, topShare)
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (heat > 0f) MaterialTheme.colorScheme.primary.copy(alpha = heat) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable { onClick(row) } else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    Fmt.plural(row.count, "entry", "entries"),
                    if (compare && row.previousPaise != null) changeText(row.amountPaise, row.previousPaise) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Amount over share, both right-aligned, so every row lines up.
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.rupeesWhole(row.amountPaise), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(sharePercent(share), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun sharePercent(share: Float): String = if (share > 0f && share < 0.005f) "<1%" else "${(share * 100).roundToInt()}%"

/** "▲ 12%" against the previous period; nothing when there was nothing to compare with. */
private fun changeText(now: Long, before: Long): String? = when {
    before == 0L -> null
    else -> ((now - before) * 100.0 / before).roundToInt().let { if (it >= 0) "▲ $it%" else "▼ ${-it}%" }
}
