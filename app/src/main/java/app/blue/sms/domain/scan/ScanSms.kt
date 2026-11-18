package app.blue.sms.domain.scan

import app.blue.sms.data.db.ScanDao
import app.blue.sms.data.db.ScanEntity
import app.blue.sms.data.db.SmsDao
import app.blue.sms.data.db.SmsMessageEntity
import app.blue.sms.domain.sms.SmsSource
import app.blue.sms.data.secure.SecretStore
import app.blue.sms.work.AiScheduler
import javax.inject.Inject

/**
 * Reads SMS for the chosen SIM ids and range, stores the ones Blue hasn't seen before
 * (matched on the provider _id), then classifies everything not yet classified.
 */
class ScanSms @Inject constructor(
    private val source: SmsSource,
    private val smsDao: SmsDao,
    private val scanDao: ScanDao,
    private val process: ProcessSms,
    private val secrets: SecretStore,
    private val aiScheduler: AiScheduler,
) {
    suspend operator fun invoke(rangeDays: Int, subscriptionIds: Set<Int>): ScanEntity {
        val now = System.currentTimeMillis()
        val from = now - rangeDays.toLong() * DAY_MS
        val started = ScanEntity(
            startedAt = now, rangeFrom = from, rangeTo = now,
            subscriptionIds = subscriptionIds.sorted().joinToString(","),
        )
        val scanId = scanDao.insert(started)
        try {
            val raw = source.readInbox(from, now, subscriptionIds)
            var inserted = 0
            raw.chunked(500).forEach { chunk ->
                val ids = smsDao.insertAll(
                    chunk.map {
                        SmsMessageEntity(
                            providerSmsId = it.providerId,
                            sender = it.sender,
                            body = it.body,
                            receivedAt = it.receivedAt,
                            subscriptionId = it.subscriptionId,
                            scanId = scanId,
                        )
                    }
                )
                inserted += ids.count { it != -1L }
            }
            val counts = process()
            val done = started.copy(
                id = scanId,
                finishedAt = System.currentTimeMillis(),
                smsRead = raw.size,
                smsNew = inserted,
                smsAlreadyStored = raw.size - inserted,
                newCards = counts.newCards,
                newUpcoming = counts.proposals,
                newSpam = counts.spam,
                ignored = counts.ignored,
                undecided = counts.undecided,
            )
            scanDao.update(done)
            // Online: Gemini starts right away. Offline: the queue waits for a connection.
            if (secrets.hasGeminiKey.value) aiScheduler.enqueue()
            return done
        } catch (e: Exception) {
            scanDao.update(started.copy(id = scanId, finishedAt = System.currentTimeMillis(), error = e.toString()))
            throw e
        }
    }

    companion object { const val DAY_MS = 24L * 60 * 60 * 1000 }
}
