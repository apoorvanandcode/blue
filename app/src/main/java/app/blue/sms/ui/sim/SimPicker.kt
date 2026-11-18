package app.blue.sms.ui.sim

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.prefs.SettingsRepository
import app.blue.sms.data.sms.hasSmsPermission
import app.blue.sms.domain.sms.ALL_SIMS
import app.blue.sms.domain.sms.SimSlotSummary
import app.blue.sms.domain.sms.SmsSource
import app.blue.sms.ui.common.BlueTopBar
import app.blue.sms.ui.common.Fmt
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SimPickerState {
    data object Loading : SimPickerState
    data object NoPermission : SimPickerState
    data class Loaded(val slots: List<SimSlotSummary>, val current: Set<Int>) : SimPickerState
    data class Failed(val message: String) : SimPickerState
}

@HiltViewModel
class SimPickerViewModel @Inject constructor(
    private val source: SmsSource,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow<SimPickerState>(SimPickerState.Loading)
    val state: StateFlow<SimPickerState> = _state

    init { load() }

    fun load() {
        if (!hasSmsPermission(context)) {
            _state.value = SimPickerState.NoPermission
            return
        }
        viewModelScope.launch {
            _state.value = SimPickerState.Loading
            _state.value = try {
                SimPickerState.Loaded(source.simSlotsSeen(), settings.settings.first().smsSubscriptionIds)
            } catch (e: Exception) {
                SimPickerState.Failed(e.message ?: e.toString())
            }
        }
    }

    suspend fun choose(ids: Set<Int>) = settings.setSmsSubscriptionIds(ids)
}

@Composable
fun SimPickerScreen(
    onBack: () -> Unit,
    onPermissionHelp: () -> Unit,
    onChosen: () -> Unit,
    vm: SimPickerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    /** ALL_SIMS, or the one SIM id picked. */
    var selected by remember { mutableStateOf<Int?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.load() }

    LaunchedEffect(state) {
        val s = state
        if (s is SimPickerState.Loaded && selected == null) {
            selected = s.current.singleOrNull()?.takeIf { id -> id == ALL_SIMS || s.slots.any { it.subscriptionId == id && it.slotNumber != null } } ?: ALL_SIMS
        }
    }

    Scaffold(
        topBar = { BlueTopBar("Which SMS to read", onBack = onBack) },
        bottomBar = {
            if (state is SimPickerState.Loaded) {
                app.blue.sms.ui.common.PrimaryButton(
                    "Continue",
                    onClick = { selected?.let { id -> scope.launch { vm.choose(setOf(id)); onChosen() } } },
                    enabled = selected != null,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                SimPickerState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                SimPickerState.NoPermission -> Column(
                    Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Blue needs SMS access to read your bank messages.")
                    Button(onClick = { launcher.launch(Manifest.permission.READ_SMS) }) { Text("Allow SMS access") }
                    TextButton(onClick = onPermissionHelp) { Text("It won't let me allow it") }
                }
                is SimPickerState.Failed -> Text(
                    "Couldn't read your SMS: ${s.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                is SimPickerState.Loaded -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    item {
                        Choice(
                            "All SMS on this phone",
                            "Recommended. Every SIM, plus older and restored messages.",
                            selected == ALL_SIMS,
                        ) { selected = ALL_SIMS }
                    }
                    // Only SIMs in the phone now; older ids are covered by "All SMS".
                    items(s.slots.filter { it.slotNumber != null }.sortedBy { it.slotNumber }, key = { it.subscriptionId }) { slot ->
                        Choice(
                            "Only SIM ${slot.slotNumber}",
                            listOf("${slot.messageCount} SMS", slot.topSenders.take(3).joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · "),
                            selected == slot.subscriptionId,
                        ) { selected = slot.subscriptionId }
                    }
                    item {
                        Text(
                            "Blue only reads your SMS. It never sends, changes or deletes any.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(title: String, sub: String, isSelected: Boolean, onSelect: () -> Unit) {
    Column(Modifier.fillMaxWidth().selectable(selected = isSelected, onClick = onSelect)) {
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
            RadioButton(
                selected = isSelected, onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
