package app.blue.sms.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.db.CategoryDao
import app.blue.sms.data.db.ForWhomDao
import app.blue.sms.data.db.TransactionDao
import app.blue.sms.domain.review.ReviewActions
import app.blue.sms.ui.common.BlueTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SkippedViewModel @Inject constructor(
    txnDao: TransactionDao,
    categoryDao: CategoryDao,
    forWhomDao: ForWhomDao,
    private val actions: ReviewActions,
) : ViewModel() {
    val items: StateFlow<List<ReviewUiItem>?> = combine(
        txnDao.observeByStatus("SKIPPED"), categoryDao.observeAll(), forWhomDao.observeAll(), ::decorate,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun backToReview(ui: ReviewUiItem) = viewModelScope.launch { actions.backToReview(ui.id) }
}

@Composable
fun SkippedScreen(onBack: () -> Unit, onOpenEdit: (Long) -> Unit, vm: SkippedViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    Scaffold(topBar = { BlueTopBar("Skipped", onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (items?.isEmpty() == true) {
                item { Text("Nothing skipped.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(items ?: emptyList(), key = { it.id }) { ui ->
                ReviewListRow(ui, onClick = { onOpenEdit(ui.id) }) {
                    TextButton(onClick = { vm.backToReview(ui) }) { Text("To Review") }
                }
            }
        }
    }
}
