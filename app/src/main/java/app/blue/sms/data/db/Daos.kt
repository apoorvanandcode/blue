package app.blue.sms.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.ProcessedBy
import app.blue.sms.domain.model.SmsClass
import kotlinx.coroutines.flow.Flow

/** An SMS plus the parsed transaction (if any), for lists. */
data class SmsRow(
    @Embedded val sms: SmsMessageEntity,
    @ColumnInfo(name = "t_amount_paise") val amountPaise: Long?,
    @ColumnInfo(name = "t_direction") val direction: Direction?,
    @ColumnInfo(name = "t_method") val method: PaymentMethod?,
    @ColumnInfo(name = "t_merchant") val merchant: String?,
    @ColumnInfo(name = "t_is_refund") val isRefund: Boolean?,
    @ColumnInfo(name = "a_bank") val bank: String?,
    @ColumnInfo(name = "a_last4") val last4: String?,
    @ColumnInfo(name = "a_type") val accountType: AccountType?,
)

/** List filters for [SmsDao.observeRows]. Class names, or one of the special values. */
object SmsFilter {
    const val ALL = "ALL"
    const val UNDECIDED = "UNDECIDED"
    const val STARRED = "STARRED"
}

@Dao
interface SmsDao {
    /** Returns -1 for rows that were already stored (same provider _id). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<SmsMessageEntity>): List<Long>

    @Query(
        """SELECT s.*, t.amount_paise AS t_amount_paise, t.direction AS t_direction, t.method AS t_method,
                  t.merchant_raw AS t_merchant, t.is_refund AS t_is_refund,
                  a.bank AS a_bank, a.last4 AS a_last4, a.type AS a_type
           FROM sms_message s
           LEFT JOIN transactions t ON t.sms_id = s.id
           LEFT JOIN account a ON a.id = t.account_id
           WHERE :filter = 'ALL'
              OR (:filter = 'UNDECIDED' AND s.classification IS NULL)
              OR (:filter = 'STARRED' AND s.is_starred = 1)
              OR s.classification = :filter
           ORDER BY CASE WHEN :filter = 'STARRED' THEN s.starred_at ELSE s.received_at END DESC
           LIMIT :limit"""
    )
    fun observeRows(filter: String, limit: Int): Flow<List<SmsRow>>

    @Query("SELECT COUNT(*) FROM sms_message")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms_message WHERE is_starred = 1")
    fun observeStarredCount(): Flow<Int>

    @Query("SELECT classification AS cls, COUNT(*) AS n FROM sms_message GROUP BY classification")
    fun observeClassCounts(): Flow<List<ClassCount>>

    /** SMS the rules haven't decided yet (new, or undecided last time). */
    @Query("SELECT * FROM sms_message WHERE classification IS NULL ORDER BY received_at")
    suspend fun unclassified(): List<SmsMessageEntity>

    @Query("UPDATE sms_message SET classification = :cls, classified_by = :by, rule_id = :ruleId WHERE id = :id")
    suspend fun setClassification(id: Long, cls: SmsClass?, by: ProcessedBy?, ruleId: String?)

    @Query("SELECT * FROM sms_message WHERE id = :id")
    suspend fun get(id: Long): SmsMessageEntity?

    /** Recent SMS classified as transactions, for the rules-vs-Gemini check. */
    @Query("SELECT * FROM sms_message WHERE classification = 'TRANSACTION' ORDER BY received_at DESC LIMIT :limit")
    suspend fun recentTransactionSms(limit: Int): List<SmsMessageEntity>

    /** Every stored SMS's sender and text, for the masker's on-phone vocabulary. */
    @Query("SELECT sender, body FROM sms_message")
    suspend fun senderBodies(): List<SenderBody>

    /** Undecided SMS the AI queue should still try. */
    @Query("SELECT * FROM sms_message WHERE classification IS NULL AND ai_attempts < :maxAttempts ORDER BY received_at DESC LIMIT :limit")
    suspend fun aiQueueUndecided(maxAttempts: Int, limit: Int): List<SmsMessageEntity>

    @Query("UPDATE sms_message SET ai_attempts = ai_attempts + 1, ai_error = :error WHERE id = :id")
    suspend fun aiFailed(id: Long, error: String)

    @Query("UPDATE sms_message SET ai_attempts = 0, ai_error = NULL WHERE id = :id")
    suspend fun aiReset(id: Long)

    /** Shows a message on the card without using up a try. */
    @Query("UPDATE sms_message SET ai_error = :message WHERE id = :id")
    suspend fun aiNote(id: Long, message: String)

    /** After the key or model changes, everything gets its tries back. */
    @Query("UPDATE sms_message SET ai_attempts = 0, ai_error = NULL WHERE ai_attempts > 0 OR ai_error IS NOT NULL")
    suspend fun aiResetAll()

    @Query("SELECT received_at FROM sms_message WHERE id = :id")
    suspend fun receivedAt(id: Long): Long?

    @Query("SELECT * FROM sms_message WHERE classification = 'SPAM' ORDER BY received_at DESC")
    fun observeSpam(): Flow<List<SmsMessageEntity>>

    /** The user said these aren't spam. Re-running rules never overrides a USER decision. */
    @Query("UPDATE sms_message SET classification = 'IGNORE', classified_by = 'USER', rule_id = 'user.not_spam' WHERE id IN (:ids)")
    suspend fun markNotSpam(ids: List<Long>)

    @Query("UPDATE sms_message SET is_starred = :starred, starred_at = CASE WHEN :starred THEN :at ELSE NULL END WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean, at: Long)

    /** For "Re-run rules": forget rule decisions on SMS whose card was never touched by hand. */
    @Query(
        """UPDATE sms_message SET classification = NULL, classified_by = NULL, rule_id = NULL
           WHERE (classified_by IS NULL OR classified_by = 'RULES')
             AND id NOT IN (SELECT sms_id FROM transactions WHERE sms_id IS NOT NULL AND status != 'PENDING')"""
    )
    suspend fun resetRuleDecisions()
}

data class ClassCount(val cls: SmsClass?, val n: Int)

@Dao
interface ScanDao {
    @Insert
    suspend fun insert(scan: ScanEntity): Long

    @Update
    suspend fun update(scan: ScanEntity)

    @Query("SELECT * FROM scan ORDER BY started_at DESC LIMIT 1")
    fun observeLatest(): Flow<ScanEntity?>
}

/** A card with everything Review needs: SMS, account, names, and the merchant memory suggestion. */
data class ReviewItem(
    @Embedded val txn: TransactionEntity,
    @ColumnInfo(name = "s_body") val smsBody: String?,
    @ColumnInfo(name = "s_sender") val smsSender: String?,
    @ColumnInfo(name = "s_starred") val smsStarred: Boolean?,
    @ColumnInfo(name = "a_bank") val bank: String?,
    @ColumnInfo(name = "a_last4") val last4: String?,
    @ColumnInfo(name = "a_type") val accountType: AccountType?,
    @ColumnInfo(name = "c_name") val categoryName: String?,
    @ColumnInfo(name = "f_name") val forWhomName: String?,
    @ColumnInfo(name = "m_category_id") val memoryCategoryId: Long?,
    @ColumnInfo(name = "m_for_whom_id") val memoryForWhomId: Long?,
    @ColumnInfo(name = "mc_active") val memoryCategoryActive: Boolean?,
    /** Last Gemini error for this card's SMS, shown with a Retry. */
    @ColumnInfo(name = "s_ai_error") val aiError: String? = null,
    /** The user's nickname for this merchant, shown instead of the bank's name. */
    @ColumnInfo(name = "n_nickname") val nickname: String? = null,
)

private const val REVIEW_SELECT = """
    SELECT t.*, s.body AS s_body, s.sender AS s_sender, s.is_starred AS s_starred, s.ai_error AS s_ai_error,
           a.bank AS a_bank, a.last4 AS a_last4, a.type AS a_type,
           c.name AS c_name, f.name AS f_name,
           m.category_id AS m_category_id, m.for_whom_id AS m_for_whom_id, mc.is_active AS mc_active,
           n.nickname AS n_nickname
    FROM transactions t
    LEFT JOIN sms_message s ON s.id = t.sms_id
    LEFT JOIN account a ON a.id = t.account_id
    LEFT JOIN category c ON c.id = t.category_id
    LEFT JOIN for_whom f ON f.id = t.for_whom_id
    LEFT JOIN merchant_memory m ON m.merchant_raw = t.merchant_raw
    LEFT JOIN category mc ON mc.id = m.category_id
    LEFT JOIN merchant_alias n ON n.merchant_key = COALESCE(t.merchant_raw, t.upi_id)"""

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(txn: TransactionEntity): Long

    @Update
    suspend fun update(txn: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    /** Newest first, as the spec asks for both Review views. */
    @Query("$REVIEW_SELECT WHERE t.status = :status ORDER BY t.txn_at DESC, t.id DESC")
    fun observeByStatus(status: String): Flow<List<ReviewItem>>

    /** Every entry (any status) dated in [from, to), newest first. Missing dates fall back to when the card was made. */
    @Query("$REVIEW_SELECT WHERE COALESCE(t.txn_at, t.created_at) >= :from AND COALESCE(t.txn_at, t.created_at) < :to ORDER BY COALESCE(t.txn_at, t.created_at) DESC, t.id DESC")
    fun observeRange(from: Long, to: Long): Flow<List<ReviewItem>>

    @Query("$REVIEW_SELECT WHERE t.id = :id")
    fun observeItem(id: Long): Flow<ReviewItem?>

    /** Pending cards waiting for Gemini to refill their fields. */
    @Query(
        """SELECT t.* FROM transactions t JOIN sms_message s ON s.id = t.sms_id
           WHERE t.ai_pending = 1 AND t.status = 'PENDING' AND s.ai_attempts < :maxAttempts
           ORDER BY t.txn_at DESC LIMIT :limit"""
    )
    suspend fun aiQueueCards(maxAttempts: Int, limit: Int): List<TransactionEntity>

    @Query(
        """SELECT (SELECT COUNT(*) FROM sms_message WHERE classification IS NULL AND ai_attempts < 3)
                + (SELECT COUNT(*) FROM transactions t JOIN sms_message s ON s.id = t.sms_id
                   WHERE t.ai_pending = 1 AND t.status = 'PENDING' AND s.ai_attempts < 3)"""
    )
    fun observeAiQueueSize(): Flow<Int>

    /** Once a key exists, rule-made pending cards get refilled by Gemini. */
    @Query("UPDATE transactions SET ai_pending = 1 WHERE status = 'PENDING' AND extracted_by = 'RULES' AND confirmed_at IS NULL")
    suspend fun markRuleCardsForAi()

    @Query("SELECT * FROM transactions WHERE sms_id = :smsId LIMIT 1")
    suspend fun forSms(smsId: Long): TransactionEntity?

    @Query("SELECT COUNT(*) FROM transactions WHERE sms_id = :smsId")
    suspend fun countForSms(smsId: Long): Int

    /** Only untouched rule-made cards. Confirmed or edited cards are never deleted. */
    @Query("DELETE FROM transactions WHERE status = 'PENDING' AND extracted_by = 'RULES' AND confirmed_at IS NULL")
    suspend fun deletePendingRuleCards()

    @Query("SELECT COUNT(*) FROM transactions WHERE status = :status")
    fun observeCountWithStatus(status: String): Flow<Int>

    /** Money out since [from], including pending and skipped, excluding duplicates and non-transactions. */
    @Query(
        """SELECT COALESCE(SUM(amount_paise), 0) FROM transactions
           WHERE direction = 'OUT' AND txn_at >= :from
           AND status NOT IN ('DUPLICATE', 'NOT_A_TRANSACTION', 'INTERNAL_TRANSFER')"""
    )
    fun observeSpendSince(from: Long): Flow<Long>

    @Query(
        """SELECT COALESCE(SUM(amount_paise), 0) FROM transactions
           WHERE direction = 'OUT' AND txn_at >= :from AND txn_at < :to
           AND status NOT IN ('DUPLICATE', 'NOT_A_TRANSACTION', 'INTERNAL_TRANSFER')"""
    )
    fun observeSpendBetween(from: Long, to: Long): Flow<Long>

    @Query("SELECT COUNT(*) FROM transactions WHERE txn_at >= :from AND status IN ('PENDING', 'SKIPPED')")
    fun observeUnconfirmedSince(from: Long): Flow<Int>
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account ORDER BY bank, type, last4")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT id FROM account WHERE bank = :bank AND type = :type AND last4 = :last4")
    suspend fun findId(bank: String, type: AccountType, last4: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: AccountEntity): Long
}

@Dao
interface SpamSenderDao {
    @Query("SELECT sender FROM spam_sender")
    suspend fun allSenders(): List<String>

    @Query("SELECT * FROM spam_sender ORDER BY sender")
    fun observeAll(): Flow<List<SpamSenderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(senders: List<SpamSenderEntity>)

    @Query("DELETE FROM spam_sender WHERE sender = :sender")
    suspend fun delete(sender: String)
}

@Dao
interface UpcomingDao {
    @Insert
    suspend fun insert(item: UpcomingItemEntity): Long

    @Update
    suspend fun update(item: UpcomingItemEntity)

    @Query("SELECT * FROM upcoming_item WHERE id = :id")
    suspend fun get(id: Long): UpcomingItemEntity?

    @Query("SELECT COUNT(*) FROM upcoming_item WHERE sms_id = :smsId")
    suspend fun countForSms(smsId: Long): Int

    /** Proposed or active items, oldest first, so the oldest is closed first. */
    @Query("SELECT * FROM upcoming_item WHERE status IN ('PROPOSED', 'ACTIVE') AND kind != 'DELIVERY' ORDER BY id")
    suspend fun openMoneyItems(): List<UpcomingItemEntity>

    @Query("SELECT * FROM upcoming_item WHERE status IN ('PROPOSED', 'ACTIVE') AND match_key = :key ORDER BY id")
    suspend fun openByKey(key: String): List<UpcomingItemEntity>

    /** Open parcels whose key starts with a sender header, for closing SMS without a tracking number. */
    @Query("SELECT * FROM upcoming_item WHERE status IN ('PROPOSED', 'ACTIVE') AND kind = 'DELIVERY' AND title LIKE :headerPrefix || '%' ORDER BY id")
    suspend fun openParcelsFrom(headerPrefix: String): List<UpcomingItemEntity>

    @Query("SELECT * FROM upcoming_item WHERE status = :status ORDER BY COALESCE(due_at, 9223372036854775807), id DESC")
    fun observeByStatus(status: String): Flow<List<UpcomingItemEntity>>

    @Query("SELECT COUNT(*) FROM upcoming_item WHERE status = :status")
    fun observeCount(status: String): Flow<Int>

    @Query("UPDATE upcoming_item SET status = :to WHERE status = :from")
    suspend fun moveAll(from: String, to: String)

    /** For "Re-run rules": drop items the user never accepted. Accepted and rejected ones stay. */
    @Query("DELETE FROM upcoming_item WHERE status = 'PROPOSED' OR (status = 'CLOSED' AND accepted_at IS NULL)")
    suspend fun deleteUnaccepted()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE is_active = 1 ORDER BY sort_order, name")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY sort_order, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    @Query("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM category")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Query("UPDATE category SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /** Delete = hide for new entries. Old entries keep the category. */
    @Query("UPDATE category SET is_active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)
}

@Dao
interface ForWhomDao {
    @Query("SELECT * FROM for_whom WHERE is_active = 1 ORDER BY type, name")
    fun observeActive(): Flow<List<ForWhomEntity>>

    @Query("SELECT * FROM for_whom ORDER BY type, name")
    fun observeAll(): Flow<List<ForWhomEntity>>

    @Query("SELECT * FROM for_whom WHERE type = 'ME' LIMIT 1")
    suspend fun me(): ForWhomEntity?

    @Query("SELECT * FROM for_whom WHERE type = 'CONTACT' AND contact_lookup_key = :key LIMIT 1")
    suspend fun findContact(key: String): ForWhomEntity?

    @Query("SELECT * FROM for_whom WHERE type = 'TAG' AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findTag(name: String): ForWhomEntity?

    @Insert
    suspend fun insert(forWhom: ForWhomEntity): Long

    @Query("UPDATE for_whom SET name = :name WHERE id = :id AND type != 'ME'")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE for_whom SET is_active = :active WHERE id = :id AND type != 'ME'")
    suspend fun setActive(id: Long, active: Boolean)
}

@Dao
interface MerchantMemoryDao {
    @Query("SELECT * FROM merchant_memory WHERE merchant_raw = :merchant")
    suspend fun get(merchant: String): MerchantMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MerchantMemoryEntity)

    @Query("DELETE FROM merchant_memory WHERE merchant_raw = :merchant")
    suspend fun delete(merchant: String)
}

@Dao
interface ActionLogDao {
    @Insert
    suspend fun insert(entry: ActionLogEntity): Long

    @Query("SELECT * FROM action_log ORDER BY id DESC LIMIT 1")
    suspend fun latest(): ActionLogEntity?

    @Query("DELETE FROM action_log WHERE id = :id")
    suspend fun delete(id: Long)

    /** Undo goes back at most [keep] steps. */
    @Query("DELETE FROM action_log WHERE id NOT IN (SELECT id FROM action_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("SELECT COUNT(*) FROM action_log")
    fun observeCount(): Flow<Int>
}

/** Whole-database read and replace, for Drive backup and restore. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM sms_message") suspend fun sms(): List<SmsMessageEntity>
    @Query("SELECT * FROM scan") suspend fun scans(): List<ScanEntity>
    @Query("SELECT * FROM transactions") suspend fun transactions(): List<TransactionEntity>
    @Query("SELECT * FROM account") suspend fun accounts(): List<AccountEntity>
    @Query("SELECT * FROM category") suspend fun categories(): List<CategoryEntity>
    @Query("SELECT * FROM for_whom") suspend fun forWhom(): List<ForWhomEntity>
    @Query("SELECT * FROM merchant_memory") suspend fun memory(): List<MerchantMemoryEntity>
    @Query("SELECT * FROM upcoming_item") suspend fun upcoming(): List<UpcomingItemEntity>
    @Query("SELECT * FROM spam_sender") suspend fun spamSenders(): List<SpamSenderEntity>
    @Query("SELECT * FROM merchant_alias") suspend fun aliases(): List<MerchantAliasEntity>
    @Query("DELETE FROM merchant_alias") suspend fun clearAliases()
    @Query("DELETE FROM pending_location") suspend fun clearPendingLocations()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAliases(rows: List<MerchantAliasEntity>)

    @Query("DELETE FROM sms_message") suspend fun clearSms()
    @Query("DELETE FROM scan") suspend fun clearScans()
    @Query("DELETE FROM transactions") suspend fun clearTransactions()
    @Query("DELETE FROM account") suspend fun clearAccounts()
    @Query("DELETE FROM category") suspend fun clearCategories()
    @Query("DELETE FROM for_whom") suspend fun clearForWhom()
    @Query("DELETE FROM merchant_memory") suspend fun clearMemory()
    @Query("DELETE FROM upcoming_item") suspend fun clearUpcoming()
    @Query("DELETE FROM spam_sender") suspend fun clearSpamSenders()
    @Query("DELETE FROM action_log") suspend fun clearActionLog()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSms(rows: List<SmsMessageEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putScans(rows: List<ScanEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTransactions(rows: List<TransactionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccounts(rows: List<AccountEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCategories(rows: List<CategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putForWhom(rows: List<ForWhomEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMemory(rows: List<MerchantMemoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putUpcoming(rows: List<UpcomingItemEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSpamSenders(rows: List<SpamSenderEntity>)

    /** Every entry with names, for the Sheets export. */
    @Query("$REVIEW_SELECT ORDER BY COALESCE(t.txn_at, t.created_at) DESC, t.id DESC")
    suspend fun allEntries(): List<ReviewItem>
}

@Dao
interface MerchantAliasDao {
    @Query("SELECT nickname FROM merchant_alias WHERE merchant_key = :key")
    suspend fun nickname(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alias: MerchantAliasEntity)

    @Query("DELETE FROM merchant_alias WHERE merchant_key = :key")
    suspend fun delete(key: String)
}

@Dao
interface PendingLocationDao {
    @Insert
    suspend fun insert(row: PendingLocationEntity): Long

    /** The location caught for this exact SMS: same sender and text, arrival within [from, to]. */
    @Query(
        """SELECT * FROM pending_location WHERE sender = :sender AND body_hash = :bodyHash
           AND received_at BETWEEN :from AND :to ORDER BY ABS(received_at - :at) LIMIT 1"""
    )
    suspend fun match(sender: String, bodyHash: String, at: Long, from: Long, to: Long): PendingLocationEntity?

    @Query("DELETE FROM pending_location WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_location WHERE captured_at < :before")
    suspend fun deleteOlderThan(before: Long)
}

data class SenderBody(val sender: String, val body: String)
