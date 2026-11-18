package app.blue.sms.ui.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.db.ActionLogDao
import app.blue.sms.data.db.CategoryDao
import app.blue.sms.data.db.CategoryEntity
import app.blue.sms.data.db.ForWhomDao
import app.blue.sms.data.db.ForWhomEntity
import app.blue.sms.data.db.ReviewItem
import app.blue.sms.data.db.SmsDao
import app.blue.sms.data.db.TransactionDao
import app.blue.sms.domain.model.ForWhomType
import app.blue.sms.domain.review.ActionResult
import app.blue.sms.domain.review.ReviewActions
import app.blue.sms.domain.review.Suggestions
import app.blue.sms.data.secure.SecretStore
import app.blue.sms.domain.ai.AiOutcome
import app.blue.sms.domain.ai.AiProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val REVIEW_MODE_KEY = "mode"
enum class ReviewMode { CARDS, LIST }

data class ReviewState(
    val loading: Boolean = true,
    val items: List<ReviewUiItem> = emptyList(),
    val skippedCount: Int = 0,
    val undoSteps: Int = 0,
)

sealed interface ReviewEvent {
    data class OpenEdit(val id: Long) : ReviewEvent
    data class Message(val text: String, val undoable: Boolean = true) : ReviewEvent
}

/** Turns DB rows into cards with suggestion names. Shared by Review and Skipped. */
fun decorate(items: List<ReviewItem>, categories: List<CategoryEntity>, people: List<ForWhomEntity>): List<ReviewUiItem> {
    val catNames = categories.associate { it.id to it.name }
    val peopleNames = people.associate { it.id to it.name }
    val meId = people.firstOrNull { it.type == ForWhomType.ME }?.id
    val refundId = categories.firstOrNull { it.name.equals("Refund", true) && it.isActive }?.id
    return items.map { item ->
        val s = Suggestions.suggest(item, meId, refundId)
        ReviewUiItem(item, s, s.categoryId?.let(catNames::get), s.forWhomId?.let(peopleNames::get))
    }
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    txnDao: TransactionDao,
    categoryDao: CategoryDao,
    forWhomDao: ForWhomDao,
    logDao: ActionLogDao,
    private val smsDao: SmsDao,
    private val actions: ReviewActions,
    private val aiProcessor: AiProcessor,
    secrets: SecretStore,
    savedState: SavedStateHandle,
) : ViewModel() {
    val hasAiKey: StateFlow<Boolean> = secrets.hasGeminiKey
    /** Card ids with a Gemini request in flight. */
    val aiBusy = MutableStateFlow<Set<Long>>(emptySet())

    fun processWithAi(ui: ReviewUiItem) = viewModelScope.launch {
        aiBusy.value = aiBusy.value + ui.id
        val o = aiProcessor.processCard(ui.id)
        aiBusy.value = aiBusy.value - ui.id
        _events.send(
            ReviewEvent.Message(
                when (o) { is AiOutcome.Ok -> "Gemini refilled this card"; is AiOutcome.Failed -> o.reason },
                undoable = false,
            )
        )
    }


    val mode: StateFlow<String> = savedState.getStateFlow(REVIEW_MODE_KEY, ReviewMode.CARDS.name)
    private val handle = savedState

    val state: StateFlow<ReviewState> = combine(
        txnDao.observeByStatus("PENDING"),
        categoryDao.observeAll(),
        forWhomDao.observeAll(),
        txnDao.observeCountWithStatus("SKIPPED"),
        logDao.observeCount(),
    ) { items, cats, people, skipped, undo ->
        ReviewState(false, decorate(items, cats, people), skipped, undo)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewState())

    private val _events = Channel<ReviewEvent>(Channel.BUFFERED)
    val events: Flow<ReviewEvent> = _events.receiveAsFlow()

    fun setMode(m: ReviewMode) { handle[REVIEW_MODE_KEY] = m.name }

    fun confirm(ui: ReviewUiItem) = viewModelScope.launch {
        when (actions.confirm(ui.id, ui.suggestion.categoryId, ui.suggestion.forWhomId)) {
            is ActionResult.NeedsEdit -> _events.send(ReviewEvent.OpenEdit(ui.id))
            ActionResult.Done -> _events.send(ReviewEvent.Message("Confirmed ${merchantText(ui.item)}"))
        }
    }

    fun skip(ui: ReviewUiItem) = act("Skipped") { actions.skip(ui.id) }
    fun duplicate(ui: ReviewUiItem) = act("Marked as duplicate") { actions.markDuplicate(ui.id) }
    fun notTransaction(ui: ReviewUiItem) = act("Marked as not a transaction") { actions.notATransaction(ui.id) }
    fun ownTransfer(ui: ReviewUiItem) = act("Marked as own transfer") { actions.ownTransfer(ui.id) }

    /** Saves (or with a blank name, removes) the nickname for this card's merchant. */
    fun setNickname(ui: ReviewUiItem, nickname: String) = viewModelScope.launch {
        val key = merchantKey(ui.item) ?: return@launch
        actions.setNickname(key, nickname)
        _events.send(ReviewEvent.Message(if (nickname.isBlank()) "Nickname removed" else "Now shown as $nickname", undoable = false))
    }

    fun undo() = viewModelScope.launch {
        val id = actions.undo()
        _events.send(ReviewEvent.Message(if (id != null) "Undone" else "Nothing to undo", undoable = false))
    }

    fun toggleStar(ui: ReviewUiItem) = viewModelScope.launch {
        val smsId = ui.item.txn.smsId ?: return@launch
        smsDao.setStarred(smsId, ui.item.smsStarred != true, System.currentTimeMillis())
    }

    private fun act(message: String, block: suspend () -> Unit) = viewModelScope.launch {
        block()
        _events.send(ReviewEvent.Message(message))
    }
}
