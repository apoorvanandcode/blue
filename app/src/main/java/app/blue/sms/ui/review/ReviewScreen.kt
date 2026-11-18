package app.blue.sms.ui.review

import app.blue.sms.ui.common.BlueIcons

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.blue.sms.domain.model.ProcessedBy
import app.blue.sms.ui.common.Fmt
import app.blue.sms.ui.common.PrimaryButton
import app.blue.sms.ui.common.SecondaryButton
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReviewScreen(
    onOpenEdit: (Long) -> Unit,
    onOpenSkipped: () -> Unit,
    vm: ReviewViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val modeName by vm.mode.collectAsStateWithLifecycle()
    val mode = ReviewMode.valueOf(modeName)
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ReviewEvent.OpenEdit -> onOpenEdit(e.id)
                is ReviewEvent.Message -> launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val r = snackbar.showSnackbar(e.text, actionLabel = if (e.undoable) "Undo" else null, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.undo()
                }
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Review", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::undo, enabled = state.undoSteps > 0) { Text("Undo") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextTab("Cards", mode == ReviewMode.CARDS) { vm.setMode(ReviewMode.CARDS) }
                TextTab("List", mode == ReviewMode.LIST) { vm.setMode(ReviewMode.LIST) }
                Box(Modifier.weight(1f))
                TextButton(onClick = onOpenSkipped) {
                    Text(
                        (if (state.items.isNotEmpty()) "${state.items.size} left · " else "") + "${state.skippedCount} skipped",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when {
                state.loading -> {}
                state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "All caught up.\nScan from Home to find new transactions.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                mode == ReviewMode.CARDS -> CardsView(state, vm, onOpenEdit)
                else -> ListView(state, vm, onOpenEdit)
            }
        }
    }
}

/** Underlined text tab, as in the design. */
@Composable
fun TextTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Box(Modifier.size(width = 36.dp, height = 2.dp).background(if (selected) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent))
    }
}

@Composable
private fun CardsView(state: ReviewState, vm: ReviewViewModel, onOpenEdit: (Long) -> Unit) {
    val ui = state.items.first()
    val hasKey by vm.hasAiKey.collectAsStateWithLifecycle()
    val busy by vm.aiBusy.collectAsStateWithLifecycle()
    // Details stay open or closed from card to card until changed.
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var nicknameFor by remember { mutableStateOf<ReviewUiItem?>(null) }
    // A soft grey page so the white card stands up off it.
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SwipeCard(
            ui, next = state.items.getOrNull(1),
            onConfirm = { vm.confirm(ui) }, onSkip = { vm.skip(ui) }, onOpen = { onOpenEdit(ui.id) },
            detailsOpen = detailsOpen, onToggleDetails = { detailsOpen = !detailsOpen },
            onNickname = { nicknameFor = ui },
        )
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Skip", onClick = { vm.skip(ui) }, modifier = Modifier.weight(1f))
            PrimaryButton("Confirm", onClick = { vm.confirm(ui) }, modifier = Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                QuietLink("Own transfer") { vm.ownTransfer(ui) }
                QuietLink("Duplicate") { vm.duplicate(ui) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                QuietLink("Not a transaction") { vm.notTransaction(ui) }
                QuietLink("Ask Gemini", enabled = hasKey && ui.id !in busy) { vm.processWithAi(ui) }
            }
        }
        AiStatusLine(ui, busy = ui.id in busy)
    }
    nicknameFor?.let { target ->
        NicknameDialog(
            bankName = merchantKey(target.item) ?: "",
            current = target.item.nickname,
            onDismiss = { nicknameFor = null },
            onSave = { vm.setNickname(target, it); nicknameFor = null },
        )
    }
}

/** One nickname per bank merchant name. Blank removes it. The bank's name is kept as it is. */
@Composable
fun NicknameDialog(bankName: String, current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nickname") },
        text = {
            Column {
                Text("The bank calls it \"$bankName\". Your nickname shows instead everywhere in Blue.", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it.take(60) },
                    singleLine = true, placeholder = { Text("e.g. Corner chai shop") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text(if (text.isBlank() && current != null) "Remove" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun QuietLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val ConfirmGreen = Color(0xFF157347)
private val SkipOrange = Color(0xFFE8590C)

/** White card with a very faint wash of the accent, top-left to bottom-right. */
@Composable
private fun Modifier.cardFace(shape: RoundedCornerShape, elevation: androidx.compose.ui.unit.Dp): Modifier {
    val accent = MaterialTheme.colorScheme.primary
    return this
        .shadow(elevation, shape, ambientColor = Color(0x330E1726), spotColor = Color(0x330E1726))
        .background(MaterialTheme.colorScheme.surface, shape)
        .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.10f), accent.copy(alpha = 0.015f))), shape)
        .clip(shape)
}

@Composable
private fun SwipeCard(
    ui: ReviewUiItem, next: ReviewUiItem?, onConfirm: () -> Unit, onSkip: () -> Unit, onOpen: () -> Unit,
    detailsOpen: Boolean, onToggleDetails: () -> Unit, onNickname: () -> Unit,
) {
    val offset = remember(ui.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 110.dp.toPx() }
    val flyTo = with(density) { 600.dp.toPx() }
    val progress = (abs(offset.value) / threshold).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(22.dp)

    fun release(confirm: Boolean) = scope.launch {
        // Fly off, act, and come back only if the card is still the same one (e.g. Confirm opened the editor).
        offset.animateTo(if (confirm) flyTo else -flyTo, tween(220))
        if (confirm) onConfirm() else onSkip()
        delay(450)
        offset.snapTo(0f)
    }

    Box {
        if (next != null) {
            // The next card waits underneath and rises into place as this one moves away.
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer {
                        val s = 0.94f + 0.06f * progress
                        scaleX = s; scaleY = s
                        translationY = (1f - progress) * 14.dp.toPx()
                    }
                    .cardFace(shape, 3.dp)
            ) { ReviewCardBody(next, full = true, onStar = null) }
        }
        Box(
            Modifier.fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .graphicsLayer { rotationZ = offset.value / 60f }
                .cardFace(shape, 10.dp)
                .pointerInput(ui.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                offset.value > threshold -> release(confirm = true)
                                offset.value < -threshold -> release(confirm = false)
                                else -> scope.launch { offset.animateTo(0f) }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + drag) }
                    }
                }
                .clickable(onClick = onOpen),
        ) {
            ReviewCardBody(ui, full = true, onStar = null, expanded = detailsOpen, onToggleDetails = onToggleDetails, onNickname = onNickname)
            val right = offset.value > 0
            val tint = if (right) ConfirmGreen else SkipOrange
            // Green for confirm, orange for skip, growing as the card moves.
            if (progress > 0f) Box(Modifier.matchParentSize().background(tint.copy(alpha = 0.18f * progress)))
        }
    }
}

@Composable
private fun ListView(state: ReviewState, vm: ReviewViewModel, onOpenEdit: (Long) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        items(state.items, key = { it.id }) { ui ->
            ReviewListRow(ui, onClick = { onOpenEdit(ui.id) }) {
                IconButton(
                    onClick = { vm.confirm(ui) },
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                ) { Icon(BlueIcons.Check, "Confirm", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** A plain row for one entry: merchant and details on the left, amount on the right. Used by Review, Skipped and Entries. */
@Composable
fun ReviewListRow(ui: ReviewUiItem, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(merchantText(ui.item), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        listOfNotNull(
                            ui.item.txn.txnAt?.let(Fmt::dateShort),
                            when {
                                ui.item.txn.amountPaise == null -> "add the rupee amount"
                                ui.categoryName == null -> "choose a category"
                                ui.item.txn.categoryId == null -> "${ui.categoryName} (suggested)"
                                else -> ui.categoryName
                            },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.categoryName == null || ui.item.txn.amountPaise == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    amountText(ui.item) ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = amountColor(ui.item),
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
            trailing()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** "Ask Gemini" as a quiet link, with what Gemini is doing for this card. */
@Composable
fun AiStatus(ui: ReviewUiItem, hasKey: Boolean, busy: Boolean, onProcess: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val status = when {
            busy -> "Asking Gemini…"
            ui.item.aiError != null -> ui.item.aiError
            ui.item.txn.aiPending -> "Waiting for Gemini (runs when online)"
            ui.item.txn.extractedBy == ProcessedBy.AI -> "Details filled by Gemini"
            else -> null
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (ui.item.aiError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        QuietLink(
            when {
                !hasKey -> "Ask Gemini (add a key in Settings)"
                ui.item.aiError != null -> "Ask Gemini again"
                else -> "Ask Gemini"
            },
            enabled = hasKey && !busy,
            onClick = onProcess,
        )
    }
}

/** One small grey line about Gemini, only when there is something to say. */
@Composable
fun AiStatusLine(ui: ReviewUiItem, busy: Boolean) {
    val status = when {
        busy -> "Asking Gemini…"
        ui.item.aiError != null -> ui.item.aiError
        ui.item.txn.aiPending -> "Waiting for Gemini"
        else -> null
    } ?: return
    Text(
        status,
        style = MaterialTheme.typography.bodySmall,
        color = if (ui.item.aiError != null && !busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
