package app.blue.sms.domain.scan

import androidx.room.withTransaction
import app.blue.sms.data.db.AccountDao
import app.blue.sms.data.db.AccountEntity
import app.blue.sms.data.db.BlueDatabase
import app.blue.sms.data.db.SmsDao
import app.blue.sms.data.db.SpamSenderDao
import app.blue.sms.data.db.TransactionDao
import app.blue.sms.data.db.TransactionEntity
import app.blue.sms.data.db.CategoryDao
import app.blue.sms.data.db.SmsMessageEntity
import app.blue.sms.data.db.UpcomingDao
import app.blue.sms.data.db.UpcomingItemEntity
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.UpcomingKind
import app.blue.sms.domain.model.UpcomingStatus
import app.blue.sms.domain.parse.DeliveryEvent
import app.blue.sms.domain.sms.SenderIds
import app.blue.sms.domain.upcoming.UpcomingMatcher
import app.blue.sms.data.secure.SecretStore
import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.ProcessedBy
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.model.TxnStatus
import app.blue.sms.domain.parse.ParsedSms
import app.blue.sms.domain.parse.SmsClassifier
import app.blue.sms.domain.parse.SmsInput
import javax.inject.Inject

operator fun ProcessCounts.plus(o: ProcessCounts) = ProcessCounts(
    newCards + o.newCards, upcoming + o.upcoming, spam + o.spam, ignored + o.ignored,
    undecided + o.undecided, proposals + o.proposals, closed + o.closed,
)

data class ProcessCounts(
    val newCards: Int = 0,
    val upcoming: Int = 0,
    val spam: Int = 0,
    val ignored: Int = 0,
    val undecided: Int = 0,
    /** New Upcoming proposals waiting for the user. */
    val proposals: Int = 0,
    /** Upcoming items closed by an arriving SMS. */
    val closed: Int = 0,
)

/**
 * Runs the classifier on every stored SMS that has no class yet, saves the class,
 * and creates a pending card for each transaction. Safe to run again: an SMS that
 * already has a card never gets a second one.
 */
class ProcessSms @Inject constructor(
    private val db: BlueDatabase,
    private val smsDao: SmsDao,
    private val txnDao: TransactionDao,
    private val accountDao: AccountDao,
    private val spamSenderDao: SpamSenderDao,
    private val classifier: SmsClassifier,
    private val upcomingDao: UpcomingDao,
    private val categoryDao: CategoryDao,
    private val secrets: SecretStore,
    private val pendingLocations: app.blue.sms.data.db.PendingLocationDao,
) {
    suspend operator fun invoke(): ProcessCounts {
        // Locations whose SMS never turned up (or was never a payment) are not kept.
        pendingLocations.deleteOlderThan(System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000L)
        val spam = spamSenderDao.allSenders().toSet()
        val accounts = HashMap<Triple<String, AccountType, String>, Long>()
        var c = ProcessCounts()
        smsDao.unclassified().chunked(500).forEach { chunk ->
            db.withTransaction {
                for (sms in chunk) {
                    val p = classifier.classify(SmsInput(sms.sender, sms.body, sms.receivedAt), spam)
                    c = c + applyOne(sms, p, ProcessedBy.RULES, accounts)
                }
            }
        }
        return c
    }

    /** Saves one SMS's class and acts on it: card, proposal, closing. Shared by rules and Gemini. */
    private suspend fun applyOne(
        sms: SmsMessageEntity,
        p: ParsedSms,
        by: ProcessedBy,
        accounts: HashMap<Triple<String, AccountType, String>, Long>,
    ): ProcessCounts {
        smsDao.setClassification(sms.id, p.smsClass, p.smsClass?.let { by }, p.ruleId)
        // A location caught when this SMS arrived goes onto its card; any other class drops it.
        // Undecided SMS keep theirs until Gemini decides.
        val location = if (p.smsClass != null) takeLocation(sms) else null
        return when (p.smsClass) {
            SmsClass.TRANSACTION -> {
                if (txnDao.countForSms(sms.id) == 0) {
                    // Rule-made cards are refilled by Gemini when a key is set (offline: later, by the queue).
                    val card = p.toCard(sms.id, sms.receivedAt, accountId(p, accounts), by)
                        .copy(
                            aiPending = by == ProcessedBy.RULES && secrets.hasGeminiKey.value,
                            locLat = location?.lat, locLng = location?.lng,
                            locAccuracy = location?.accuracy, locAt = location?.capturedAt,
                        )
                    val txnId = txnDao.insert(card)
                    val closed = if (card.direction == Direction.OUT) closeMoneyItem(card.copy(id = txnId), sms.id) else false
                    ProcessCounts(newCards = 1, closed = if (closed) 1 else 0)
                } else ProcessCounts()
            }
            SmsClass.UPCOMING_FINANCE -> ProcessCounts(upcoming = 1, proposals = if (proposeMoney(sms, p)) 1 else 0)
            SmsClass.UPCOMING_DELIVERY -> handleParcel(sms, p).let { (made, closed) -> ProcessCounts(upcoming = 1, proposals = made, closed = closed) }
            SmsClass.SPAM -> ProcessCounts(spam = 1)
            SmsClass.IGNORE -> ProcessCounts(ignored = 1)
            null -> ProcessCounts(undecided = 1)
        }
    }

    private suspend fun takeLocation(sms: SmsMessageEntity): app.blue.sms.data.db.PendingLocationEntity? {
        val w = app.blue.sms.domain.sms.SmsFingerprint.WINDOW_MS
        val hit = pendingLocations.match(
            sms.sender, app.blue.sms.domain.sms.SmsFingerprint.bodyHash(sms.body), sms.receivedAt, sms.receivedAt - w, sms.receivedAt + w,
        ) ?: return null
        pendingLocations.delete(hit.id)
        return hit
    }

    /** Applies Gemini's answer for an SMS the rules couldn't decide. */
    suspend fun applyAi(sms: SmsMessageEntity, p: ParsedSms): ProcessCounts =
        db.withTransaction { applyOne(sms, p, ProcessedBy.AI, HashMap()) }

    /** Account id for a parsed bank/card, created on first sight. */
    suspend fun accountFor(p: ParsedSms): Long? = accountId(p, HashMap())

    private suspend fun proposeMoney(sms: SmsMessageEntity, p: ParsedSms): Boolean {
        if (upcomingDao.countForSms(sms.id) > 0) return false
        upcomingDao.insert(
            UpcomingItemEntity(
                smsId = sms.id,
                kind = p.upcomingKind ?: UpcomingKind.AUTO_DEBIT,
                title = p.title ?: p.merchant ?: "Upcoming payment",
                amountPaise = p.amountPaise,
                dueAt = p.dueAt,
                matchKey = UpcomingMatcher.moneyKey(p.merchant),
            )
        )
        return true
    }

    /** Shipped / out for delivery -> one proposal per parcel. Delivered -> closes it. Returns (made, closed). */
    private suspend fun handleParcel(sms: SmsMessageEntity, p: ParsedSms): Pair<Int, Int> {
        val header = SenderIds.parse(sms.sender).header
        val key = UpcomingMatcher.parcelKey(p.trackingRef, header)
        if (p.deliveryEvent == DeliveryEvent.DELIVERED) {
            val target = upcomingDao.openByKey(key).firstOrNull()
                ?: if (p.trackingRef == null) upcomingDao.openParcelsFrom("$header:").firstOrNull() else null
            target ?: return 0 to 0
            upcomingDao.update(target.copy(status = UpcomingStatus.CLOSED, closedBySmsId = sms.id, closedAt = sms.receivedAt))
            return 0 to 1
        }
        if (upcomingDao.countForSms(sms.id) > 0 || upcomingDao.openByKey(key).isNotEmpty()) return 0 to 0
        upcomingDao.insert(
            UpcomingItemEntity(
                smsId = sms.id, kind = UpcomingKind.DELIVERY, title = p.title ?: "$header: parcel",
                amountPaise = null, dueAt = null, matchKey = key,
            )
        )
        return 1 to 0
    }

    /**
     * A debit that fulfils an open money item closes it and is linked to it. SIP debits get the
     * Investment category pre-filled (the user still confirms the card in Review).
     */
    private suspend fun closeMoneyItem(card: TransactionEntity, smsId: Long): Boolean {
        val open = upcomingDao.openMoneyItems()
        val smsTimes = open.associate { it.id to it.smsId?.let { id -> smsDao.receivedAt(id) } }
        val item = UpcomingMatcher.findMoneyMatch(open, card.merchantRaw, card.amountPaise, card.txnAt ?: 0L) { i ->
            smsTimes[i.id]
        } ?: return false
        upcomingDao.update(
            item.copy(status = UpcomingStatus.CLOSED, closedBySmsId = smsId, linkedTransactionId = card.id, closedAt = card.txnAt)
        )
        if (item.kind == UpcomingKind.SIP) {
            categoryDao.findByName("Investment")?.let { inv -> txnDao.update(card.copy(categoryId = inv.id)) }
        }
        return true
    }

    private suspend fun accountId(p: ParsedSms, cache: HashMap<Triple<String, AccountType, String>, Long>): Long? {
        val bank = p.bank ?: return null
        val last4 = p.account?.removePrefix("X") ?: return null
        val type = p.accountType ?: AccountType.SAVINGS
        val key = Triple(bank, type, last4)
        cache[key]?.let { return it }
        val id = accountDao.findId(bank, type, last4)
            ?: accountDao.insert(AccountEntity(bank = bank, type = type, last4 = last4, label = null))
                .takeIf { it > 0 }
            ?: accountDao.findId(bank, type, last4)
        return id?.also { cache[key] = it }
    }

    private fun ParsedSms.toCard(smsId: Long, receivedAt: Long, accountId: Long?, by: ProcessedBy) = TransactionEntity(
        smsId = smsId,
        amountPaise = amountPaise,
        direction = direction,
        merchantRaw = merchant,
        upiId = upiId,
        accountId = accountId,
        method = method,
        refNo = refNo,
        txnAt = txnAt ?: receivedAt,
        categoryId = null,
        forWhomId = null,
        notes = null,
        status = TxnStatus.PENDING,
        extractedBy = by,
        isRefund = isRefund,
        createdAt = System.currentTimeMillis(),
    )
}

/** Debug: throw away untouched rule-made cards and decisions, then run the current rules again. */
class RerunRules @Inject constructor(
    private val db: BlueDatabase,
    private val smsDao: SmsDao,
    private val txnDao: TransactionDao,
    private val upcomingDao: UpcomingDao,
    private val process: ProcessSms,
) {
    suspend operator fun invoke(): ProcessCounts {
        db.withTransaction {
            upcomingDao.deleteUnaccepted()
            txnDao.deletePendingRuleCards()
            smsDao.resetRuleDecisions()
        }
        return process()
    }
}
