package app.blue.sms.ui.upcoming

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.db.SmsDao
import app.blue.sms.data.db.UpcomingDao
import app.blue.sms.data.db.UpcomingItemEntity
import app.blue.sms.domain.model.UpcomingKind
import app.blue.sms.domain.upcoming.UpcomingActions
import app.blue.sms.ui.common.BlueTopBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import app.blue.sms.ui.common.SectionLabel
import app.blue.sms.ui.common.RowText
import app.blue.sms.ui.common.PlainRow
import app.blue.sms.ui.common.Fmt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

fun kindLabel(k: UpcomingKind) = when (k) {
    UpcomingKind.SIP -> "SIP"
    UpcomingKind.MANDATE -> "Mandate"
    UpcomingKind.AUTO_DEBIT -> "Auto-debit"
    UpcomingKind.DELIVERY -> "Parcel"
}

fun dueText(item: UpcomingItemEntity): String? = item.dueAt?.let {
    (if (item.kind == UpcomingKind.MANDATE) "from " else "due ") + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
}

/** Home's short line: "Due 3 Dec", "From 13 Sep", "Out for delivery". */
fun shortLine(item: UpcomingItemEntity): String {
    val d = item.dueAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) }
    return when {
        item.kind == UpcomingKind.DELIVERY -> "Parcel on the way"
        d == null -> kindLabel(item.kind)
        item.kind == UpcomingKind.MANDATE -> "From $d"
        else -> "Due $d"
    }
}

/** One line: "Mandate · ₹389.00 max · due 3 Nov 2026". */
fun upcomingSubtitle(item: UpcomingItemEntity): String = listOfNotNull(
    kindLabel(item.kind),
    item.amountPaise?.let { Fmt.rupees(it) + if (item.kind == UpcomingKind.MANDATE) " max" else "" },
    dueText(item),
).joinToString(" · ")

@HiltViewModel
class ProposalsViewModel @Inject constructor(
    dao: UpcomingDao,
    private val smsDao: SmsDao,
    private val actions: UpcomingActions,
) : ViewModel() {
    val items: StateFlow<List<UpcomingItemEntity>?> = dao.observeByStatus("PROPOSED")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun accept(id: Long) = viewModelScope.launch { actions.accept(id) }
    fun reject(id: Long) = viewModelScope.launch { actions.reject(id) }
    fun rejectAll() = viewModelScope.launch { actions.rejectAllProposals() }
}

@Composable
fun ProposalsScreen(onBack: () -> Unit, vm: ProposalsViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        BlueTopBar("Upcoming to review", onBack = onBack, actions = {
            if (!items.isNullOrEmpty()) TextButton(onClick = vm::rejectAll) { Text("Reject all", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text(
                    "Found in your SMS. Accept the ones you want on Home. They close on their own when the payment or delivery SMS arrives.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items?.isEmpty() == true) item { Text("Nothing to review.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp)) }
            items(items ?: emptyList(), key = { it.id }) { item ->
                Column(Modifier.padding(top = 16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(upcomingSubtitle(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.padding(top = 10.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { vm.reject(item.id) }, shape = MaterialTheme.shapes.extraLarge,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Reject") }
                        Button(onClick = { vm.accept(item.id) }, shape = MaterialTheme.shapes.extraLarge,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) { Text("Accept") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** The Upcoming list on Home: accepted items, always visible, as plain rows. */
@Composable
fun UpcomingHomeSection(
    active: List<UpcomingItemEntity>,
    proposals: Int,
    onOpenProposals: () -> Unit,
    onClose: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Upcoming", Modifier.weight(1f))
        if (proposals > 0) TextButton(onClick = onOpenProposals, modifier = Modifier.padding(top = 16.dp)) {
            Text("$proposals new to review", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    if (active.isEmpty()) {
        Text(
            if (proposals > 0) "Nothing accepted yet." else "Nothing upcoming.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    active.forEach { item ->
        var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            PlainRow(onClick = { menu = true }) {
                RowText(item.title, shortLine(item), titleWeight = FontWeight.Normal)
                item.amountPaise?.let {
                    Text(
                        (if (item.kind == UpcomingKind.MANDATE) "up to " else "") + Fmt.rupees(it),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("Mark done") }, onClick = { menu = false; onClose(item.id) })
                androidx.compose.material3.DropdownMenuItem(text = { Text("Dismiss") }, onClick = { menu = false; onDismiss(item.id) })
            }
        }
    }
}
