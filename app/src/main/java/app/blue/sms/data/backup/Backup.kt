package app.blue.sms.data.backup

import androidx.room.withTransaction
import app.blue.sms.data.db.AccountEntity
import app.blue.sms.data.db.BackupDao
import app.blue.sms.data.db.BlueDatabase
import app.blue.sms.data.db.CategoryEntity
import app.blue.sms.data.db.ForWhomEntity
import app.blue.sms.data.db.MerchantMemoryEntity
import app.blue.sms.data.db.ScanEntity
import app.blue.sms.data.db.SmsMessageEntity
import app.blue.sms.data.db.SpamSenderEntity
import app.blue.sms.data.db.TransactionEntity
import app.blue.sms.data.db.UpcomingItemEntity
import app.blue.sms.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

@Serializable
data class BackupSettings(val scanRangeDays: Int, val smsSubscriptionIds: Set<Int>, val geminiModel: String)

/** Everything Blue knows, in one file. The Gemini key is never included. */
@Serializable
data class BackupFile(
    val format: Int = FORMAT,
    val dbVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val settings: BackupSettings,
    val sms: List<SmsMessageEntity>,
    val scans: List<ScanEntity>,
    val transactions: List<TransactionEntity>,
    val accounts: List<AccountEntity>,
    val categories: List<CategoryEntity>,
    val forWhom: List<ForWhomEntity>,
    val merchantMemory: List<MerchantMemoryEntity>,
    val upcoming: List<UpcomingItemEntity>,
    val spamSenders: List<SpamSenderEntity>,
    /** Merchant nicknames. Missing in backups made before v5. */
    val nicknames: List<app.blue.sms.data.db.MerchantAliasEntity> = emptyList(),
) {
    fun summary() = "${sms.size} SMS, ${transactions.size} entries, ${categories.size} categories, " +
        "${forWhom.size} \"for whom\", ${merchantMemory.size} remembered merchants, ${upcoming.size} upcoming, ${spamSenders.size} spam senders, ${nicknames.size} nicknames"

    companion object { const val FORMAT = 1 }
}

object BackupCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(b: BackupFile): ByteArray = json.encodeToString(BackupFile.serializer(), b).toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray): BackupFile = json.decodeFromString(BackupFile.serializer(), bytes.toString(Charsets.UTF_8))
}

class BackupRepository @Inject constructor(
    private val db: BlueDatabase,
    private val dao: BackupDao,
    private val settings: SettingsRepository,
) {
    suspend fun snapshot(appVersion: String): BackupFile {
        val s = settings.settings.first()
        return db.withTransaction {
            BackupFile(
                dbVersion = db.openHelper.readableDatabase.version,
                createdAt = System.currentTimeMillis(),
                appVersion = appVersion,
                settings = BackupSettings(s.scanRangeDays, s.smsSubscriptionIds, s.geminiModel),
                sms = dao.sms(), scans = dao.scans(), transactions = dao.transactions(), accounts = dao.accounts(),
                categories = dao.categories(), forWhom = dao.forWhom(), merchantMemory = dao.memory(),
                upcoming = dao.upcoming(), spamSenders = dao.spamSenders(), nicknames = dao.aliases(),
            )
        }
    }

    /** Replaces everything on the phone with the backup. Undo history is cleared. */
    suspend fun restore(b: BackupFile) {
        require(b.format <= BackupFile.FORMAT) { "This backup is from a newer Blue. Update Blue first." }
        db.withTransaction {
            dao.clearActionLog(); dao.clearUpcoming(); dao.clearTransactions(); dao.clearMemory(); dao.clearSms()
            dao.clearScans(); dao.clearAccounts(); dao.clearCategories(); dao.clearForWhom(); dao.clearSpamSenders()
            dao.clearAliases(); dao.clearPendingLocations()
            dao.putScans(b.scans)
            b.sms.chunked(500).forEach { dao.putSms(it) }
            dao.putAccounts(b.accounts); dao.putCategories(b.categories); dao.putForWhom(b.forWhom)
            b.transactions.chunked(500).forEach { dao.putTransactions(it) }
            dao.putMemory(b.merchantMemory); dao.putUpcoming(b.upcoming); dao.putSpamSenders(b.spamSenders)
            dao.putAliases(b.nicknames)
        }
        settings.setScanRangeDays(b.settings.scanRangeDays)
        if (b.settings.smsSubscriptionIds.isNotEmpty()) settings.setSmsSubscriptionIds(b.settings.smsSubscriptionIds)
        settings.setGeminiModel(b.settings.geminiModel)
    }
}
