package app.blue.sms.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.db.CategoryDao
import app.blue.sms.data.db.CategoryEntity
import app.blue.sms.data.db.ForWhomDao
import app.blue.sms.data.db.ForWhomEntity
import app.blue.sms.data.db.SpamSenderDao
import app.blue.sms.domain.model.ForWhomType
import app.blue.sms.ui.common.BlueTopBar
import app.blue.sms.ui.edit.NameDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val MANAGE_CATEGORIES = "categories"
const val MANAGE_FOR_WHOM = "for_whom"
const val MANAGE_SPAM = "spam"

data class ManagedItem(val id: Long, val name: String, val note: String?, val editable: Boolean)

@HiltViewModel
class ManageListViewModel @Inject constructor(
    private val categoryDao: CategoryDao,
    private val forWhomDao: ForWhomDao,
    private val spamSenderDao: SpamSenderDao,
    savedState: SavedStateHandle,
) : ViewModel() {
    val kind: String = savedState.get<String>("kind") ?: MANAGE_CATEGORIES

    /** Spam senders have no numeric id; their list index is used, and [spamNames] maps it back. */
    private var spamNames: List<String> = emptyList()

    private val source: Flow<List<ManagedItem>> = if (kind == MANAGE_SPAM) {
        spamSenderDao.observeAll().map { list ->
            spamNames = list.map { it.sender }
            list.mapIndexed { i, s -> ManagedItem(i.toLong(), s.sender, "added " + app.blue.sms.ui.common.Fmt.dateTime(s.addedAt), true) }
        }
    } else if (kind == MANAGE_CATEGORIES) {
        categoryDao.observeActive().map { list -> list.map { ManagedItem(it.id, it.name, null, true) } }
    } else {
        forWhomDao.observeActive().map { list ->
            list.map {
                ManagedItem(
                    it.id, it.name,
                    when (it.type) { ForWhomType.ME -> "default"; ForWhomType.CONTACT -> "phone contact"; ForWhomType.TAG -> "tag" },
                    editable = it.type != ForWhomType.ME,
                )
            }
        }
    }
    val items: StateFlow<List<ManagedItem>> = source.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String) = viewModelScope.launch {
        val clean = name.trim().ifEmpty { return@launch }
        if (kind == MANAGE_CATEGORIES) {
            val existing = categoryDao.findByName(clean)
            if (existing != null) categoryDao.setActive(existing.id, true)
            else categoryDao.insert(CategoryEntity(name = clean, sortOrder = categoryDao.nextSortOrder()))
        } else {
            val existing = forWhomDao.findTag(clean)
            if (existing != null) forWhomDao.setActive(existing.id, true)
            else forWhomDao.insert(ForWhomEntity(type = ForWhomType.TAG, name = clean))
        }
    }

    fun rename(id: Long, name: String) = viewModelScope.launch {
        val clean = name.trim().ifEmpty { return@launch }
        if (kind == MANAGE_CATEGORIES) categoryDao.rename(id, clean) else forWhomDao.rename(id, clean)
    }

    /** Hides it for new entries. Entries that already use it keep it. */
    fun delete(id: Long) = viewModelScope.launch {
        if (kind == MANAGE_SPAM) { spamNames.getOrNull(id.toInt())?.let { spamSenderDao.delete(it) }; return@launch }
        if (kind == MANAGE_CATEGORIES) categoryDao.setActive(id, false) else forWhomDao.setActive(id, false)
    }
}

@Composable
fun ManageListScreen(onBack: () -> Unit, vm: ManageListViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val isCategories = vm.kind == MANAGE_CATEGORIES
    val isSpam = vm.kind == MANAGE_SPAM
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ManagedItem?>(null) }
    var deleting by remember { mutableStateOf<ManagedItem?>(null) }

    Scaffold(
        topBar = { BlueTopBar(if (isSpam) "Spam senders" else if (isCategories) "Categories" else "For whom", onBack = onBack) },
        floatingActionButton = {
            if (!isSpam) ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(if (isCategories) "Add category" else "Add tag") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
        ) {
            item {
                Text(
                    if (isSpam) "SMS from these senders are flagged as spam on every scan. Removing one stops that. Nothing is deleted from your phone."
                    else if (isCategories) "Deleting a category hides it for new entries. Entries that already use it keep it."
                    else "Tags like Household, and phone contacts you've picked on a card. Removing one hides it for new entries.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(items, key = { it.id }) { item ->
                app.blue.sms.ui.common.PlainRow {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                        item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (item.editable) {
                        if (!isSpam) TextButton(onClick = { renaming = item }) { Text("Rename", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { deleting = item }) { Text(if (isSpam) "Remove" else "Delete", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }

    if (adding) NameDialog(if (isCategories) "New category" else "New tag", onDismiss = { adding = false }) { vm.add(it); adding = false }
    renaming?.let { r -> NameDialog("Rename", initial = r.name, onDismiss = { renaming = null }) { vm.rename(r.id, it); renaming = null } }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (isSpam) "Remove ${d.name}?" else "Delete ${d.name}?") },
            text = { Text(if (isSpam) "Its SMS go back to \"To confirm\" in the Spam tab." else "It won't be offered for new entries. Entries that already use it keep it.") },
            confirmButton = { TextButton(onClick = { vm.delete(d.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
