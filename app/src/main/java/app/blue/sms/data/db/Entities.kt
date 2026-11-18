package app.blue.sms.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.ForWhomType
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.ProcessedBy
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.model.TxnStatus
import app.blue.sms.domain.model.UpcomingKind
import app.blue.sms.domain.model.UpcomingStatus

/** A copy of one SMS from the phone's SMS provider. Blue never changes the original. */
@Serializable
@Entity(
    tableName = "sms_message",
    indices = [
        Index(value = ["provider_sms_id"], unique = true),
        Index("received_at"),
        Index("sender"),
        Index("scan_id"),
    ],
)
data class SmsMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "provider_sms_id") val providerSmsId: Long,
    val sender: String,
    val body: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Int,
    /** Null until the classifier has run (Phase 2). */
    val classification: SmsClass? = null,
    @ColumnInfo(name = "classified_by") val classifiedBy: ProcessedBy? = null,
    @ColumnInfo(name = "scan_id") val scanId: Long,
    /** Which rule decided the class, e.g. "federal.upi_debit". For debugging and re-runs. */
    @ColumnInfo(name = "rule_id") val ruleId: String? = null,
    @ColumnInfo(name = "is_starred", defaultValue = "0") val isStarred: Boolean = false,
    @ColumnInfo(name = "starred_at") val starredAt: Long? = null,
    /** Failed Gemini tries. The queue gives up after 3; "Process with AI" resets it. */
    @ColumnInfo(name = "ai_attempts", defaultValue = "0") val aiAttempts: Int = 0,
    @ColumnInfo(name = "ai_error") val aiError: String? = null,
)

@Serializable
@Entity(tableName = "scan")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    @ColumnInfo(name = "range_from") val rangeFrom: Long,
    @ColumnInfo(name = "range_to") val rangeTo: Long,
    /** Comma-separated SIM subscription ids, e.g. "3,1,-1". */
    @ColumnInfo(name = "subscription_ids") val subscriptionIds: String,
    @ColumnInfo(name = "sms_read") val smsRead: Int = 0,
    @ColumnInfo(name = "sms_new") val smsNew: Int = 0,
    @ColumnInfo(name = "sms_already_stored") val smsAlreadyStored: Int = 0,
    @ColumnInfo(name = "new_cards") val newCards: Int = 0,
    @ColumnInfo(name = "new_upcoming") val newUpcoming: Int = 0,
    @ColumnInfo(name = "new_spam") val newSpam: Int = 0,
    val ignored: Int = 0,
    /** SMS the rules could not decide. They wait for Gemini (Phase 6). */
    val undecided: Int = 0,
    val error: String? = null,
)

/** One money movement. Amounts are integer paise. Fields are nullable so a card can exist with empty fields. */
@Serializable
@Entity(
    tableName = "transactions",
    indices = [Index("sms_id"), Index("status"), Index("txn_at"), Index("merchant_raw")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sms_id") val smsId: Long?,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long?,
    val direction: Direction?,
    @ColumnInfo(name = "merchant_raw") val merchantRaw: String?,
    @ColumnInfo(name = "upi_id") val upiId: String?,
    @ColumnInfo(name = "account_id") val accountId: Long?,
    val method: PaymentMethod?,
    @ColumnInfo(name = "ref_no") val refNo: String?,
    @ColumnInfo(name = "txn_at") val txnAt: Long?,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "for_whom_id") val forWhomId: Long?,
    val notes: String?,
    val status: TxnStatus = TxnStatus.PENDING,
    @ColumnInfo(name = "extracted_by") val extractedBy: ProcessedBy?,
    @ColumnInfo(name = "ai_pending") val aiPending: Boolean = false,
    @ColumnInfo(name = "is_refund") val isRefund: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "confirmed_at") val confirmedAt: Long? = null,
)

@Serializable
@Entity(tableName = "account", indices = [Index(value = ["bank", "type", "last4"], unique = true)])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bank: String,
    val type: AccountType,
    val last4: String,
    val label: String?,
)

/** Deleting a category sets is_active = 0 so old entries keep it. */
@Serializable
@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

/** The name is stored so a deleted phone contact still shows on old entries. */
@Serializable
@Entity(tableName = "for_whom")
data class ForWhomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: ForWhomType,
    val name: String,
    @ColumnInfo(name = "contact_lookup_key") val contactLookupKey: String? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
)

/** Exact-match memory: merchant string -> last confirmed category and "for whom". */
@Serializable
@Entity(tableName = "merchant_memory")
data class MerchantMemoryEntity(
    @PrimaryKey @ColumnInfo(name = "merchant_raw") val merchantRaw: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "for_whom_id") val forWhomId: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
    @ColumnInfo(name = "use_count") val useCount: Int,
)

@Serializable
@Entity(tableName = "upcoming_item", indices = [Index("status"), Index("sms_id"), Index("match_key")])
data class UpcomingItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sms_id") val smsId: Long?,
    val kind: UpcomingKind,
    val title: String,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long?,
    @ColumnInfo(name = "due_at") val dueAt: Long?,
    val status: UpcomingStatus = UpcomingStatus.PROPOSED,
    @ColumnInfo(name = "closed_by_sms_id") val closedBySmsId: Long? = null,
    @ColumnInfo(name = "linked_transaction_id") val linkedTransactionId: Long? = null,
    /** What a closing SMS must match: "money:APPLE" or "parcel:IT833646699IN". */
    @ColumnInfo(name = "match_key") val matchKey: String? = null,
    /** Set when the user accepts the proposal. Rule re-runs never delete accepted items. */
    @ColumnInfo(name = "accepted_at") val acceptedAt: Long? = null,
    @ColumnInfo(name = "closed_at") val closedAt: Long? = null,
)

@Serializable
@Entity(tableName = "spam_sender")
data class SpamSenderEntity(
    @PrimaryKey val sender: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** What undo needs for the last actions. previous_state is a JSON snapshot. */
@Serializable
@Entity(tableName = "action_log", indices = [Index("at")])
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "card_id") val cardId: Long,
    val action: String,
    @ColumnInfo(name = "previous_state") val previousState: String,
    val at: Long,
)
