package app.blue.sms.ui.common

import app.blue.sms.ui.common.BlueIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import app.blue.sms.domain.insights.Period
import app.blue.sms.domain.insights.PeriodKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Keeps the chosen period in SavedStateHandle so it survives rotation and navigation. */
class PeriodHolder(private val handle: SavedStateHandle, default: PeriodKind) {
    private val initial: Period = run {
        val kind = handle.get<String>("kind")?.let(PeriodKind::valueOf)
        val start = handle.get<Long>("start")?.takeIf { it != Long.MIN_VALUE }
        val end = handle.get<Long>("end")?.takeIf { it != Long.MIN_VALUE }
        if (kind != null && start != null && end != null) Period(kind, LocalDate.ofEpochDay(start), LocalDate.ofEpochDay(end))
        else Period.of(kind ?: default, LocalDate.now())
    }
    private val _period = MutableStateFlow(initial)
    val period: StateFlow<Period> = _period

    fun set(p: Period) {
        _period.value = p
        handle["kind"] = p.kind.name
        handle["start"] = p.start.toEpochDay()
        handle["end"] = p.end.toEpochDay()
    }

    fun choose(kind: PeriodKind) = set(Period.of(kind, LocalDate.now()))
    fun shift(step: Int) = set(_period.value.shift(step))
    fun custom(start: LocalDate, end: LocalDate) = set(Period(PeriodKind.CUSTOM, start, end))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodBar(
    period: Period,
    allowAll: Boolean,
    onChoose: (PeriodKind) -> Unit,
    onShift: (Int) -> Unit,
    onCustom: (LocalDate, LocalDate) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Column(Modifier.fillMaxWidth()) {
        // A soft grey switch with the chosen period in white, as in the design.
        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                .padding(4.dp),
        ) {
            val kinds = listOf(PeriodKind.TODAY, PeriodKind.WEEK, PeriodKind.MONTH, PeriodKind.CUSTOM) +
                if (allowAll) listOf(PeriodKind.ALL) else emptyList()
            kinds.forEach { k ->
                val on = period.kind == k
                Text(
                    when (k) {
                        PeriodKind.TODAY -> "Today"; PeriodKind.WEEK -> "Week"; PeriodKind.MONTH -> "Month"
                        PeriodKind.CUSTOM -> "Custom"; PeriodKind.ALL -> "All"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                        .background(if (on) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .clickable { if (k == PeriodKind.CUSTOM) picking = true else onChoose(k) }
                        .padding(vertical = 9.dp),
                )
            }
        }
        if (period.kind != PeriodKind.ALL) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onShift(-1) }) { Icon(BlueIcons.ChevronLeft, "Previous") }
                Text(
                    period.label(today),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onShift(1) }, enabled = period.end < today) {
                    Icon(BlueIcons.ChevronRight, "Next")
                }
            }
        }
    }

    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = period.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                .takeIf { period.kind != PeriodKind.ALL },
            initialSelectedEndDateMillis = period.end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                .takeIf { period.kind != PeriodKind.ALL },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val s = state.selectedStartDateMillis
                        val e = state.selectedEndDateMillis ?: s
                        if (s != null && e != null) {
                            onCustom(
                                Instant.ofEpochMilli(s).atZone(ZoneOffset.UTC).toLocalDate(),
                                Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                        picking = false
                    },
                    enabled = state.selectedStartDateMillis != null,
                ) { Text("Use") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DateRangePicker(state, modifier = Modifier.weight(1f)) }
    }
}
