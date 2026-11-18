package app.blue.sms.domain.ai

import app.blue.sms.data.db.SmsDao
import app.blue.sms.data.db.SmsMessageEntity
import app.blue.sms.data.db.TransactionDao
import app.blue.sms.data.db.TransactionEntity
import app.blue.sms.domain.model.ProcessedBy
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.parse.ParsedSms
import app.blue.sms.domain.parse.SmsInput
import app.blue.sms.domain.scan.ProcessSms
import javax.inject.Inject

data class QueueResult(
    val done: Int,
    val failed: Int,
    val retryLater: Boolean,
    val moreLeft: Boolean,
    /** Set when the queue stopped on a key/model/billing problem. */
    val setupProblem: String? = null,
)

/**
 * The Gemini side of the table in the spec:
 * - SMS the rules couldn't classify go to Gemini, which classifies and extracts them.
 * - Rule-made pending cards go to Gemini, which refills their fields.
 * One SMS per request. A failure keeps the rule values and records the error for a retry.
 */
class AiProcessor @Inject constructor(
    private val ai: AiExtractor,
    private val smsDao: SmsDao,
    private val txnDao: TransactionDao,
    private val process: ProcessSms,
) {
    suspend fun runQueue(maxItems: Int = 300): QueueResult {
        var done = 0
        var failed = 0
        while (done + failed < maxItems) {
            val undecided = smsDao.aiQueueUndecided(MAX_ATTEMPTS, 25)
            val cards = txnDao.aiQueueCards(MAX_ATTEMPTS, 25)
            if (undecided.isEmpty() && cards.isEmpty()) return QueueResult(done, failed, retryLater = false, moreLeft = false)
            for (sms in undecided) {
                when (val o = ai.extract(input(sms))) {
                    is AiOutcome.Ok -> { process.applyAi(sms, o.parsed); smsDao.aiReset(sms.id); done++ }
                    is AiOutcome.Failed -> stop(sms, o, done, failed + 1)?.let { return it } ?: failed++
                }
            }
            for (card in cards) {
                val sms = card.smsId?.let { smsDao.get(it) } ?: continue
                when (val o = ai.extract(input(sms))) {
                    is AiOutcome.Ok -> { refill(card, sms, o.parsed); done++ }
                    is AiOutcome.Failed -> stop(sms, o, done, failed + 1)?.let { return it } ?: failed++
                }
            }
        }
        return QueueResult(done, failed, retryLater = false, moreLeft = true)
    }

    /** Setup and temporary problems stop the queue without using up the SMS's tries. */
    private suspend fun stop(sms: SmsMessageEntity, o: AiOutcome.Failed, done: Int, failed: Int): QueueResult? = when (o.kind) {
        FailKind.THIS_SMS -> { smsDao.aiFailed(sms.id, o.reason); null }
        FailKind.TEMPORARY -> QueueResult(done, failed, retryLater = true, moreLeft = true)
        FailKind.SETUP -> QueueResult(done, failed, retryLater = false, moreLeft = true, setupProblem = o.reason)
    }

    /** "Process with AI" on one card. Runs now, whatever the attempt count. */
    suspend fun processCard(txnId: Long): AiOutcome {
        val card = txnDao.get(txnId) ?: return AiOutcome.Failed("Card not found", FailKind.THIS_SMS)
        val sms = card.smsId?.let { smsDao.get(it) } ?: return AiOutcome.Failed("This card has no SMS", FailKind.THIS_SMS)
        smsDao.aiReset(sms.id)
        val o = ai.extract(input(sms))
        when (o) {
            is AiOutcome.Ok -> refill(card, sms, o.parsed)
            is AiOutcome.Failed -> if (o.kind == FailKind.THIS_SMS) smsDao.aiFailed(sms.id, o.reason) else smsDao.aiNote(sms.id, o.reason)
        }
        return o
    }

    /** Overwrites rule values with Gemini's, field by field; Gemini's nulls keep the rule value. */
    private suspend fun refill(card: TransactionEntity, sms: SmsMessageEntity, p: ParsedSms) {
        if (p.smsClass != SmsClass.TRANSACTION) {
            txnDao.update(card.copy(aiPending = false))
            smsDao.aiFailed(sms.id, "Gemini thinks this isn't a transaction (${p.smsClass?.name?.lowercase()}). Kept the rule values.")
            return
        }
        val current = txnDao.get(card.id) ?: return
        txnDao.update(
            current.copy(
                amountPaise = p.amountPaise ?: current.amountPaise,
                direction = p.direction ?: current.direction,
                merchantRaw = p.merchant ?: current.merchantRaw,
                upiId = p.upiId ?: current.upiId,
                accountId = (if (p.bank != null && p.account != null) process.accountFor(p) else null) ?: current.accountId,
                method = p.method ?: current.method,
                refNo = p.refNo ?: current.refNo,
                txnAt = p.txnAt ?: current.txnAt,
                isRefund = p.isRefund || current.isRefund,
                extractedBy = ProcessedBy.AI,
                aiPending = false,
            )
        )
        smsDao.setClassification(sms.id, SmsClass.TRANSACTION, ProcessedBy.AI, "ai")
        smsDao.aiReset(sms.id)
    }

    private fun input(sms: SmsMessageEntity) = SmsInput(sms.sender, sms.body, sms.receivedAt)

    companion object { const val MAX_ATTEMPTS = 3 }
}
