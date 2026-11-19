package app.blue.sms.ui.edit

import app.blue.sms.ui.common.BlueIcons

import android.provider.ContactsContract
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import app.blue.sms.domain.insights.countsInTotals
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.ForWhomType
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.TxnStatus
import app.blue.sms.domain.review.Missing
import app.blue.sms.ui.common.BlueTopBar
import app.blue.sms.ui.common.PrimaryButton
import app.blue.sms.ui.common.SecondaryButton
import androidx.compose.ui.unit.sp
import app.blue.sms.ui.common.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditTxnScreen(onDone: () -> Unit, vm: EditTxnViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val f = state.form
    val context = LocalContext.current
    var detailsOpen by remember { mutableStateOf(EditDetails.open) }
    var dialog by remember { mutableStateOf<String?>(null) } // "category", "tag", "date", "time"

    LaunchedEffect(Unit) { vm.done.collect { onDone() } }
    // Leaving with the system back gesture keeps edits too.
    BackHandler { vm.save() }

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        // The system picker grants read access to just this one contact.
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.query(
            uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.LOOKUP_KEY), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                val key = c.getString(1)
                if (!name.isNullOrBlank() && !key.isNullOrBlank()) vm.addContact(name, key)
            }
        }
    }

    val status = state.item?.txn?.status
    Scaffold(
        topBar = {
            BlueTopBar(if (status == TxnStatus.PENDING || status == null) "Review card" else "Edit entry", onBack = { vm.save() }, actions = {
                state.item?.let { item ->
                    IconButton(onClick = vm::toggleStar) {
                        Icon(
                            BlueIcons.Star, "Star SMS",
                            tint = if (item.smsStarred == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            })
        },
        bottomBar = {
            Surface {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton("Save", onClick = vm::save, modifier = Modifier.weight(1f))
                        // On a self transfer, duplicate or non-payment, confirming makes it count again: say so.
                        val counts = status == null || status.countsInTotals()
                        PrimaryButton(if (counts) "Confirm" else "Count it", onClick = vm::confirm, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        var allCats by remember { mutableStateOf(false) }
        var accountMenu by remember { mutableStateOf(false) }
        var methodMenu by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            NotCountedBanner(status)
            if (state.missing.isNotEmpty()) {
                Text(
                    "To confirm, add: " + state.missing.joinToString(", ") {
                        when (it) {
                            Missing.AMOUNT -> "amount"
                            Missing.DIRECTION -> "money in or out"
                            Missing.CATEGORY -> "category"
                            Missing.FOR_WHOM -> "for whom"
                        }
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // Amount: big and underlined, with an Out/In switch beside it.
            Section("Amount", error = Missing.AMOUNT in state.missing || Missing.DIRECTION in state.missing)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("₹", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.text.BasicTextField(
                            value = f.amountText,
                            onValueChange = { v -> vm.edit { copy(amountText = v.filter { it.isDigit() || it == '.' || it == ',' }) } },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onSurface),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.padding(start = 4.dp).fillMaxWidth(),
                            decorationBox = { inner ->
                                if (f.amountText.isEmpty()) Text("0.00", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.outline)
                                inner()
                            },
                        )
                    }
                    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.width(12.dp))
                Row(
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)).padding(3.dp),
                ) {
                    listOf(Direction.OUT to "Spent", Direction.IN to "Received").forEach { (d, label) ->
                        val on = f.direction == d
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(if (on) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(17.dp))
                                .clickable { vm.edit { copy(direction = d) } }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Text(
                listOfNotNull(state.item?.nickname ?: f.merchant.ifBlank { null }, f.txnAt?.let(Fmt::dateTime)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )

            Section("Category", error = Missing.CATEGORY in state.missing)
            val shownCats = if (allCats) state.categories else {
                val first = state.categories.take(8)
                first + state.categories.filter { it.id == f.categoryId && it !in first }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shownCats.forEach { c ->
                    FilterChip(selected = f.categoryId == c.id, onClick = { vm.edit { copy(categoryId = c.id) } }, label = { Text(c.name) })
                }
                if (!allCats && state.categories.size > shownCats.size) {
                    AssistChip(onClick = { allCats = true }, label = { Text("All ${state.categories.size}…") })
                }
                AssistChip(onClick = { dialog = "category" }, label = { Text("+ New") })
            }

            Section("For whom", error = Missing.FOR_WHOM in state.missing)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.people.forEach { p ->
                    FilterChip(selected = f.forWhomId == p.id, onClick = { vm.edit { copy(forWhomId = p.id) } }, label = { Text(p.name) })
                }
                AssistChip(onClick = { dialog = "tag" }, label = { Text("+ Tag") })
                AssistChip(onClick = { contactPicker.launch(null) }, label = { Text("Pick contact") })
            }

            Section("Notes")
            OutlinedTextField(
                value = f.notes, onValueChange = { v -> vm.edit { copy(notes = v) } },
                placeholder = { Text("Optional, e.g. Diwali") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // Details: plain rows, tap a line to change it. Folds away to one summary line.
            DetailsHeader(
                open = detailsOpen,
                summary = listOfNotNull(
                    f.method?.let(Fmt::method),
                    state.accounts.firstOrNull { it.id == f.accountId }?.let(::accountName),
                    f.txnAt?.let(Fmt::dateShortYear),
                ).joinToString(" · "),
                onToggle = { detailsOpen = !detailsOpen; EditDetails.open = detailsOpen },
            )
            AnimatedVisibility(detailsOpen) {
                Column {
                DetailRow("Merchant", f.merchant.ifBlank { "—" }) { dialog = "merchant" }
                state.item?.let { app.blue.sms.ui.review.merchantKey(it) }?.let {
                    DetailRow("Nickname", state.item?.nickname ?: "Add") { dialog = "nickname" }
                }
                Box {
                    DetailRow("Paid from", state.accounts.firstOrNull { it.id == f.accountId }?.let(::accountName) ?: "—") { accountMenu = true }
                    DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { vm.edit { copy(accountId = null) }; accountMenu = false })
                        state.accounts.forEach { a -> DropdownMenuItem(text = { Text(accountName(a)) }, onClick = { vm.edit { copy(accountId = a.id) }; accountMenu = false }) }
                    }
                }
                Box {
                    DetailRow("Method", f.method?.let(Fmt::method) ?: "—") { methodMenu = true }
                    DropdownMenu(expanded = methodMenu, onDismissRequest = { methodMenu = false }) {
                        PaymentMethod.entries.forEach { m -> DropdownMenuItem(text = { Text(Fmt.method(m)) }, onClick = { vm.edit { copy(method = m) }; methodMenu = false }) }
                    }
                }
                DetailRow("Date", f.txnAt?.let(Fmt::dateShortYear) ?: "—") { dialog = "date" }
                DetailRow("Time", f.txnAt?.let(Fmt::time) ?: "—") { dialog = "time" }
                DetailRow("UPI ID", f.upiId.ifBlank { "—" }) { dialog = "upi" }
                DetailRow("Reference", f.refNo.ifBlank { "—" }) { dialog = "ref" }
                DetailRow("Refund", if (f.isRefund) "Yes" else "No") { vm.edit { copy(isRefund = !isRefund) } }
                state.item?.let { item ->
                    val t = item.txn
                    if (t.locLat != null && t.locLng != null) app.blue.sms.ui.review.PlaceLine(t.locLat, t.locLng, t.locAccuracy)
                    else if (app.blue.sms.ui.review.merchantKey(item) != null) app.blue.sms.ui.review.FindOnMapLine(app.blue.sms.ui.review.merchantText(item))
                }
                Text("Tap any line to change it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }

            state.item?.smsBody?.let { body ->
                Section("Original SMS")
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(14.dp),
                )
                Text(
                    "From ${state.item?.smsSender ?: "?"} · " + when (state.item?.txn?.extractedBy) {
                        app.blue.sms.domain.model.ProcessedBy.AI -> "read by Gemini"
                        app.blue.sms.domain.model.ProcessedBy.USER -> "edited by you"
                        else -> "read by rules"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            val hasKey by vm.hasAiKey.collectAsStateWithLifecycle()
            val busy by vm.aiBusy.collectAsStateWithLifecycle()
            val aiMsg by vm.aiMessage.collectAsStateWithLifecycle()
            Column(Modifier.padding(top = 16.dp)) {
                if (status != TxnStatus.PENDING) {
                    LinkRow(if (status == TxnStatus.CONFIRMED) "Un-confirm (back to Review)" else "Back to Review", onClick = vm::backToReview)
                }
                if (status != TxnStatus.SKIPPED) LinkRow("Skip for now", onClick = vm::skip)
                if (status != TxnStatus.INTERNAL_TRANSFER) LinkRow("Self transfer (between my accounts)", onClick = vm::ownTransfer)
                LinkRow("Mark as duplicate", onClick = vm::duplicate)
                LinkRow("Not a transaction", onClick = vm::notTransaction)
                LinkRow(if (hasKey) "Ask Gemini to re-read" else "Ask Gemini (add a key in Settings)", enabled = hasKey && !busy, onClick = vm::processWithAi)
                val note = when {
                    busy -> "Asking Gemini…"
                    aiMsg != null -> aiMsg
                    state.item?.aiError != null -> state.item?.aiError
                    state.item?.txn?.aiPending == true -> "Waiting for Gemini"
                    else -> null
                }
                note?.let { Text(it!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    when (dialog) {
        "nickname" -> app.blue.sms.ui.review.NicknameDialog(
            bankName = state.item?.let { app.blue.sms.ui.review.merchantKey(it) } ?: "",
            current = state.item?.nickname,
            onDismiss = { dialog = null },
            onSave = { vm.setNickname(it); dialog = null },
        )
        "merchant" -> NameDialog("Merchant", initial = f.merchant, onDismiss = { dialog = null }) { vm.edit { copy(merchant = it) }; dialog = null }
        "upi" -> NameDialog("UPI ID", initial = f.upiId, onDismiss = { dialog = null }) { vm.edit { copy(upiId = it) }; dialog = null }
        "ref" -> NameDialog("Reference no.", initial = f.refNo, onDismiss = { dialog = null }) { vm.edit { copy(refNo = it) }; dialog = null }
        "category" -> NameDialog("New category", onDismiss = { dialog = null }) { vm.addCategory(it); dialog = null }
        "tag" -> NameDialog("New tag (e.g. Household)", onDismiss = { dialog = null }) { vm.addTag(it); dialog = null }
        "date" -> {
            val zone = ZoneId.systemDefault()
            val current = f.txnAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
            val ds = rememberDatePickerState(
                initialSelectedDateMillis = current?.toLocalDate()?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { dialog = null },
                confirmButton = {
                    TextButton(onClick = {
                        ds.selectedDateMillis?.let { ms ->
                            val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                            val time = current?.toLocalTime() ?: LocalTime.NOON
                            vm.edit { copy(txnAt = date.atTime(time).atZone(zone).toInstant().toEpochMilli()) }
                        }
                        dialog = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            ) { DatePicker(ds) }
        }
        "time" -> {
            val zone = ZoneId.systemDefault()
            val current = f.txnAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
            val ts = rememberTimePickerState(current?.hour ?: 12, current?.minute ?: 0, is24Hour = true)
            AlertDialog(
                onDismissRequest = { dialog = null },
                confirmButton = {
                    TextButton(onClick = {
                        val date = current?.toLocalDate() ?: LocalDate.now()
                        vm.edit { copy(txnAt = date.atTime(ts.hour, ts.minute).atZone(zone).toInstant().toEpochMilli()) }
                        dialog = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
                text = { TimePicker(ts) },
            )
        }
    }
}

private fun accountName(a: app.blue.sms.data.db.AccountEntity) = listOfNotNull(
    a.bank,
    when (a.type) { AccountType.CREDIT_CARD -> "credit card"; AccountType.DEBIT_CARD -> "debit card"; else -> null },
    "X" + a.last4,
).joinToString(" ")

/** Whether the edit screen's Details section is open. Kept while Blue runs, so it stays as you left it. */
private object EditDetails { var open = false }

/** "DETAILS ⌄" plus, when closed, a one-line summary. Tapping toggles. */
@Composable
private fun DetailsHeader(open: Boolean, summary: String, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Section("Details") }
            Icon(
                if (open) BlueIcons.ChevronUp else BlueIcons.ChevronDown,
                if (open) "Hide details" else "Show details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp).size(20.dp),
            )
        }
        if (!open && summary.isNotEmpty()) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Section(title: String, error: Boolean = false) {
    Text(
        (title + if (error) " · required" else "").uppercase(),
        modifier = Modifier.padding(top = 14.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun <T> PickerField(label: String, value: String, options: List<Pair<T, String>>, onPick: (T) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, maxLines = 1)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onPick(v); open = false }) }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Merchant ........ ATRIA CONVER": label left, value right, tap to change. */
@Composable
private fun DetailRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1.4f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun LinkRow(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
    )
}

/** Top-of-screen note for entries that are kept but left out of every total. */
@Composable
private fun NotCountedBanner(status: TxnStatus?) {
    val (title, body) = when (status) {
        TxnStatus.INTERNAL_TRANSFER -> "Self transfer" to "Between your own accounts. Not counted as spent or received."
        TxnStatus.DUPLICATE -> "Duplicate" to "Already recorded by another entry. Not counted."
        TxnStatus.NOT_A_TRANSACTION -> "Not a payment" to "Kept for reference. Not counted."
        else -> return
    }
    Column(
        Modifier.fillMaxWidth()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
