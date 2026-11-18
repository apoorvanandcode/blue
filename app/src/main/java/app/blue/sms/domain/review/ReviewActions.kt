package app.blue.sms.domain.review

import androidx.room.withTransaction
import app.blue.sms.data.db.ActionLogDao
import app.blue.sms.data.db.ActionLogEntity
import app.blue.sms.data.db.BlueDatabase
import app.blue.sms.data.db.MerchantMemoryDao
import app.blue.sms.data.db.MerchantMemoryEntity
import app.blue.sms.data.db.TransactionDao
import app.blue.sms.data.db.TransactionEntity
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.TxnStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Why a card can't be confirmed yet. The UI opens the edit view when this is non-empty. */
enum class Missing { AMOUNT, DIRECTION, CATEGORY, FOR_WHOM }

sealed interface ActionResult {
    data object Done : ActionResult
    data class NeedsEdit(val missing: Set<Missing>) : ActionResult
}

/** Everything undo needs to put one card (and its merchant memory) back. */
@Serializable
private data class Snapshot(
    val amountPaise: Long?, val direction: String?, val merchantRaw: String?, val upiId: String?,
    val accountId: Long?, val method: String?, val refNo: String?, val txnAt: Long?,
    val categoryId: Long?, val forWhomId: Long?, val notes: String?, val status: String,
    val isRefund: Boolean, val confirmedAt: Long?,
    val locLat: Double? = null, val locLng: Double? = null, val locAccuracy: Float? = null, val locAt: Long? = null,
    /** Set when this action changed merchant memory. */
    val memoryMerchant: String? = null,
    val memoryBefore: MemorySnapshot? = null,
)

@Serializable
private data class MemorySnapshot(val categoryId: Long, val forWhomId: Long, val lastUsedAt: Long, val useCount: Int)

class ReviewActions @Inject constructor(
    private val db: BlueDatabase,
    private val txnDao: TransactionDao,
    private val memoryDao: MerchantMemoryDao,
    private val logDao: ActionLogDao,
    private val aliasDao: app.blue.sms.data.db.MerchantAliasDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun missing(t: TransactionEntity, categoryId: Long?, forWhomId: Long?): Set<Missing> = buildSet {
        if (t.amountPaise == null || t.amountPaise <= 0) add(Missing.AMOUNT)
        if (t.direction == null) add(Missing.DIRECTION)
        if (categoryId == null) add(Missing.CATEGORY)
        if (forWhomId == null) add(Missing.FOR_WHOM)
    }

    /** Saves edits without changing status. Not an undo step. */
    suspend fun save(t: TransactionEntity) = txnDao.update(t)

    /** Confirm with a category and "for whom", and remember them for this exact merchant. */
    suspend fun confirm(id: Long, categoryId: Long?, forWhomId: Long?): ActionResult {
        val t = txnDao.get(id) ?: return ActionResult.Done
        val missing = missing(t, categoryId, forWhomId)
        if (missing.isNotEmpty()) return ActionResult.NeedsEdit(missing)
        val now = System.currentTimeMillis()
        db.withTransaction {
            val merchant = t.merchantRaw?.takeIf { it.isNotBlank() }
            val before = merchant?.let { memoryDao.get(it) }
            log(t, "confirm", merchant, before)
            txnDao.update(t.copy(categoryId = categoryId, forWhomId = forWhomId, status = TxnStatus.CONFIRMED, confirmedAt = now))
            if (merchant != null) {
                memoryDao.upsert(
                    MerchantMemoryEntity(merchant, categoryId!!, forWhomId!!, now, (before?.useCount ?: 0) + 1)
                )
            }
        }
        return ActionResult.Done
    }

    suspend fun skip(id: Long) = setStatus(id, TxnStatus.SKIPPED, "skip")
    suspend fun markDuplicate(id: Long) = setStatus(id, TxnStatus.DUPLICATE, "duplicate")
    suspend fun notATransaction(id: Long) = setStatus(id, TxnStatus.NOT_A_TRANSACTION, "not_a_transaction")
    suspend fun ownTransfer(id: Long) = setStatus(id, TxnStatus.INTERNAL_TRANSFER, "own_transfer")
    suspend fun backToReview(id: Long) = setStatus(id, TxnStatus.PENDING, "back_to_review")

    private suspend fun setStatus(id: Long, status: TxnStatus, action: String) {
        val t = txnDao.get(id) ?: return
        db.withTransaction {
            log(t, action, null, null)
            // Not a real payment (or a copy of one): its location isn't kept. Undo brings it back.
            val dropLocation = status == TxnStatus.NOT_A_TRANSACTION || status == TxnStatus.DUPLICATE
            txnDao.update(
                t.copy(status = status, confirmedAt = null).let {
                    if (dropLocation) it.copy(locLat = null, locLng = null, locAccuracy = null, locAt = null) else it
                }
            )
        }
    }

    /** Nickname for a merchant (keyed by the bank's name). Blank removes it. */
    suspend fun setNickname(merchantKey: String, nickname: String) {
        val name = nickname.trim()
        if (name.isEmpty() || name == merchantKey) aliasDao.delete(merchantKey)
        else aliasDao.upsert(app.blue.sms.data.db.MerchantAliasEntity(merchantKey, name, System.currentTimeMillis()))
    }

    /** Restores the last actioned card (and memory). Returns its id, or null if there's nothing to undo. */
    suspend fun undo(): Long? = db.withTransaction {
        val entry = logDao.latest() ?: return@withTransaction null
        val snap = json.decodeFromString<Snapshot>(entry.previousState)
        txnDao.get(entry.cardId)?.let { t ->
            txnDao.update(
                t.copy(
                    amountPaise = snap.amountPaise,
                    direction = snap.direction?.let(Direction::valueOf),
                    merchantRaw = snap.merchantRaw,
                    upiId = snap.upiId,
                    accountId = snap.accountId,
                    method = snap.method?.let(PaymentMethod::valueOf),
                    refNo = snap.refNo,
                    txnAt = snap.txnAt,
                    categoryId = snap.categoryId,
                    forWhomId = snap.forWhomId,
                    notes = snap.notes,
                    status = TxnStatus.valueOf(snap.status),
                    isRefund = snap.isRefund,
                    confirmedAt = snap.confirmedAt,
                    locLat = snap.locLat, locLng = snap.locLng, locAccuracy = snap.locAccuracy, locAt = snap.locAt,
                )
            )
        }
        snap.memoryMerchant?.let { merchant ->
            val m = snap.memoryBefore
            if (m == null) memoryDao.delete(merchant)
            else memoryDao.upsert(MerchantMemoryEntity(merchant, m.categoryId, m.forWhomId, m.lastUsedAt, m.useCount))
        }
        logDao.delete(entry.id)
        entry.cardId
    }

    private suspend fun log(t: TransactionEntity, action: String, merchant: String?, before: MerchantMemoryEntity?) {
        val snap = Snapshot(
            t.amountPaise, t.direction?.name, t.merchantRaw, t.upiId, t.accountId, t.method?.name, t.refNo, t.txnAt,
            t.categoryId, t.forWhomId, t.notes, t.status.name, t.isRefund, t.confirmedAt,
            t.locLat, t.locLng, t.locAccuracy, t.locAt,
            memoryMerchant = merchant,
            memoryBefore = before?.let { MemorySnapshot(it.categoryId, it.forWhomId, it.lastUsedAt, it.useCount) },
        )
        logDao.insert(ActionLogEntity(cardId = t.id, action = action, previousState = json.encodeToString(Snapshot.serializer(), snap), at = System.currentTimeMillis()))
        logDao.trim(UNDO_STEPS)
    }

    companion object { const val UNDO_STEPS = 2 }
}
