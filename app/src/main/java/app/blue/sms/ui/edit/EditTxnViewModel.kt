package app.blue.sms.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.db.AccountDao
import app.blue.sms.data.db.AccountEntity
import app.blue.sms.data.db.CategoryDao
import app.blue.sms.data.db.CategoryEntity
import app.blue.sms.data.db.ForWhomDao
import app.blue.sms.data.db.ForWhomEntity
import app.blue.sms.data.db.ReviewItem
import app.blue.sms.data.db.SmsDao
import app.blue.sms.data.db.TransactionDao
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.ForWhomType
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.TxnStatus
import app.blue.sms.domain.parse.Money
import app.blue.sms.domain.review.ActionResult
import app.blue.sms.domain.review.Missing
import app.blue.sms.domain.review.ReviewActions
import app.blue.sms.domain.review.Suggestions
import app.blue.sms.data.secure.SecretStore
import app.blue.sms.domain.ai.AiOutcome
import app.blue.sms.domain.ai.AiProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditForm(
    val amountText: String = "",
    val direction: Direction? = null,
    val merchant: String = "",
    val upiId: String = "",
    val accountId: Long? = null,
    val method: PaymentMethod? = null,
    val refNo: String = "",
    val txnAt: Long? = null,
    val categoryId: Long? = null,
    val forWhomId: Long? = null,
    val notes: String = "",
    val isRefund: Boolean = false,
)

data class EditState(
    val loaded: Boolean = false,
    val item: ReviewItem? = null,
    val form: EditForm = EditForm(),
    val missing: Set<Missing> = emptySet(),
    val categories: List<CategoryEntity> = emptyList(),
    val people: List<ForWhomEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
)

@HiltViewModel
class EditTxnViewModel @Inject constructor(
    private val txnDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val forWhomDao: ForWhomDao,
    private val smsDao: SmsDao,
    accountDao: AccountDao,
    private val actions: ReviewActions,
    private val aiProcessor: AiProcessor,
    secrets: SecretStore,
    savedState: SavedStateHandle,
) : ViewModel() {
    val hasAiKey: StateFlow<Boolean> = secrets.hasGeminiKey
    val aiBusy = MutableStateFlow(false)
    val aiMessage = MutableStateFlow<String?>(null)

    /** Runs Gemini on this card now, then reloads the form with its values. */
    fun processWithAi() = viewModelScope.launch {
        aiBusy.value = true
        saveForm()
        val o = aiProcessor.processCard(id)
        aiMessage.value = when (o) { is AiOutcome.Ok -> "Gemini refilled the fields"; is AiOutcome.Failed -> o.reason }
        loadForm()
        aiBusy.value = false
    }

    private val id: Long = checkNotNull(savedState.get<Long>("id"))
    private val form = MutableStateFlow<EditForm?>(null)
    private val missing = MutableStateFlow<Set<Missing>>(emptySet())
    private val item = txnDao.observeItem(id)

    val state: StateFlow<EditState> = combine(
        item, form, missing,
        combine(categoryDao.observeAll(), forWhomDao.observeAll(), accountDao.observeAll(), ::Triple),
    ) { it, f, miss, (cats, people, accounts) ->
        EditState(
            loaded = f != null,
            item = it,
            form = f ?: EditForm(),
            missing = miss,
            // Hidden categories and tags only show if this card already uses them.
            categories = cats.filter { c -> c.isActive || c.id == f?.categoryId },
            people = people.filter { p -> p.isActive || p.id == f?.forWhomId },
            accounts = accounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditState())

    private val _done = Channel<Unit>(Channel.BUFFERED)
    val done: Flow<Unit> = _done.receiveAsFlow()

    init { viewModelScope.launch { loadForm() } }

    private suspend fun loadForm() {
        run {
            val it = item.filterNotNull().first()
            val cats = categoryDao.observeAll().first()
            val people = forWhomDao.observeAll().first()
            val s = Suggestions.suggest(
                it,
                people.firstOrNull { p -> p.type == ForWhomType.ME }?.id,
                cats.firstOrNull { c -> c.name.equals("Refund", true) && c.isActive }?.id,
            )
            val t = it.txn
            form.value = EditForm(
                amountText = t.amountPaise?.let(Money::format) ?: "",
                direction = t.direction,
                merchant = t.merchantRaw ?: "",
                upiId = t.upiId ?: "",
                accountId = t.accountId,
                method = t.method,
                refNo = t.refNo ?: "",
                txnAt = t.txnAt,
                categoryId = s.categoryId,
                forWhomId = s.forWhomId,
                notes = t.notes ?: "",
                isRefund = t.isRefund,
            )
        }
    }

    fun edit(change: EditForm.() -> EditForm) {
        form.update { it?.change() }
        missing.value = emptySet()
    }

    private suspend fun saveForm(): Boolean {
        val f = form.value ?: return false
        val t = txnDao.get(id) ?: return false
        val edited =
            t.copy(
                amountPaise = Money.toPaise(f.amountText.trim().ifEmpty { null }),
                direction = f.direction,
                merchantRaw = f.merchant.trim().ifEmpty { null },
                upiId = f.upiId.trim().ifEmpty { null },
                accountId = f.accountId,
                method = f.method,
                refNo = f.refNo.trim().ifEmpty { null },
                txnAt = f.txnAt,
                categoryId = f.categoryId,
                forWhomId = f.forWhomId,
                notes = f.notes.trim().ifEmpty { null },
                isRefund = f.isRefund,
            )
        // A hand-edited card is no longer refilled by Gemini. Only the suggested category/for whom don't count as edits.
        val changed = edited.copy(categoryId = t.categoryId, forWhomId = t.forWhomId) != t
        actions.save(if (changed) edited.copy(aiPending = false) else edited)
        return true
    }

    fun save() = finish { saveForm() }

    fun confirm() = viewModelScope.launch {
        if (!saveForm()) return@launch
        val f = form.value ?: return@launch
        when (val r = actions.confirm(id, f.categoryId, f.forWhomId)) {
            is ActionResult.NeedsEdit -> missing.value = r.missing
            ActionResult.Done -> _done.send(Unit)
        }
    }

    fun skip() = finish { saveForm(); actions.skip(id) }
    fun duplicate() = finish { saveForm(); actions.markDuplicate(id) }
    fun notTransaction() = finish { saveForm(); actions.notATransaction(id) }
    fun backToReview() = finish { saveForm(); actions.backToReview(id) }

    val status: TxnStatus? get() = state.value.item?.txn?.status

    fun toggleStar() = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        it.txn.smsId?.let { smsId -> smsDao.setStarred(smsId, it.smsStarred != true, System.currentTimeMillis()) }
    }

    fun addCategory(name: String) = viewModelScope.launch {
        val clean = name.trim().ifEmpty { return@launch }
        val existing = categoryDao.findByName(clean)
        val newId = if (existing != null) {
            if (!existing.isActive) categoryDao.setActive(existing.id, true)
            existing.id
        } else {
            categoryDao.insert(CategoryEntity(name = clean, sortOrder = categoryDao.nextSortOrder()))
        }
        edit { copy(categoryId = newId) }
    }

    fun addTag(name: String) = viewModelScope.launch {
        val clean = name.trim().ifEmpty { return@launch }
        val existing = forWhomDao.findTag(clean)
        val newId = existing?.also { if (!it.isActive) forWhomDao.setActive(it.id, true) }?.id
            ?: forWhomDao.insert(ForWhomEntity(type = ForWhomType.TAG, name = clean))
        edit { copy(forWhomId = newId) }
    }

    /** Stores the contact's name, so a later-deleted phone contact still shows on old entries. */
    fun addContact(name: String, lookupKey: String) = viewModelScope.launch {
        val existing = forWhomDao.findContact(lookupKey)
        val newId = existing?.also {
            if (!it.isActive) forWhomDao.setActive(it.id, true)
            if (it.name != name) forWhomDao.rename(it.id, name)
        }?.id ?: forWhomDao.insert(ForWhomEntity(type = ForWhomType.CONTACT, name = name, contactLookupKey = lookupKey))
        edit { copy(forWhomId = newId) }
    }

    private fun finish(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        _done.send(Unit)
    }
}
