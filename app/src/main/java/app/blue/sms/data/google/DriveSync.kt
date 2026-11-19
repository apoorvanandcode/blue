package app.blue.sms.data.google

import app.blue.sms.ui.common.mapsUrl
import app.blue.sms.BuildConfig
import app.blue.sms.data.backup.BackupCodec
import app.blue.sms.data.backup.BackupFile
import app.blue.sms.data.backup.BackupRepository
import app.blue.sms.data.db.BackupDao
import app.blue.sms.data.db.ReviewItem
import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.TxnStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** One row per entry, every field. Pure, so it's unit-tested. */
object SheetExport {
    val HEADER = listOf(
        "Date", "Time", "Amount (₹)", "In/Out", "Nickname", "Merchant (bank's name)", "UPI ID", "Bank", "Account type", "Account",
        "Method", "Reference", "Category", "For whom", "Notes", "Location", "Status", "Refund", "Filled by",
        "SMS sender", "SMS text", "Entry id",
    )

    fun rows(items: List<ReviewItem>, zone: ZoneId): List<List<Any?>> = listOf(HEADER) + items.map { i ->
        val t = i.txn
        val at = (t.txnAt ?: t.createdAt).let { Instant.ofEpochMilli(it).atZone(zone) }
        listOf(
            at.format(DateTimeFormatter.ISO_LOCAL_DATE),
            at.format(DateTimeFormatter.ofPattern("HH:mm")),
            t.amountPaise?.let { BigDecimal(it).movePointLeft(2).toDouble() },
            when (t.direction) { Direction.IN -> "In"; Direction.OUT -> "Out"; null -> null },
            i.nickname,
            t.merchantRaw,
            t.upiId,
            i.bank,
            when (i.accountType) { AccountType.SAVINGS -> "Savings"; AccountType.CREDIT_CARD -> "Credit card"; AccountType.DEBIT_CARD -> "Debit card"; null -> null },
            i.last4?.let { "X$it" },
            when (t.method) {
                PaymentMethod.UPI -> "UPI"; PaymentMethod.CARD -> "Card"; PaymentMethod.NETBANKING -> "Net banking"
                PaymentMethod.AUTO_DEBIT -> "Auto-debit"; PaymentMethod.ATM -> "ATM"; PaymentMethod.OTHER -> "Other"; null -> null
            },
            t.refNo,
            i.categoryName,
            i.forWhomName,
            t.notes,
            if (t.locLat != null && t.locLng != null) mapsUrl(t.locLat, t.locLng) else null,
            when (t.status) {
                TxnStatus.PENDING -> "To review"; TxnStatus.CONFIRMED -> "Confirmed"; TxnStatus.SKIPPED -> "Skipped"
                TxnStatus.DUPLICATE -> "Duplicate"; TxnStatus.NOT_A_TRANSACTION -> "Not a transaction"
                TxnStatus.INTERNAL_TRANSFER -> "Self transfer"
            },
            t.isRefund,
            t.extractedBy?.name?.lowercase(),
            i.smsSender,
            i.smsBody,
            t.id,
        )
    }
}

/** Manual Drive backup/restore and Sheets export. Nothing runs automatically. */
class DriveSync @Inject constructor(
    private val api: GoogleApi,
    private val backup: BackupRepository,
    private val dao: BackupDao,
) {
    suspend fun backup(token: String): BackupFile {
        val snap = backup.snapshot(BuildConfig.VERSION_NAME)
        val existing = api.findFile(token, BACKUP_NAME)
        api.saveFile(token, BACKUP_NAME, BackupCodec.encode(snap), existing?.id)
        return snap
    }

    suspend fun findBackup(token: String): DriveFile? = api.findFile(token, BACKUP_NAME)

    suspend fun fetch(token: String, file: DriveFile): BackupFile = BackupCodec.decode(api.download(token, file.id))

    suspend fun restore(b: BackupFile) = backup.restore(b)

    suspend fun exportSheet(token: String): Pair<String, Int> {
        val items = dao.allEntries()
        val title = "Blue export " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.now().atZone(ZoneId.systemDefault()))
        return api.createSheet(token, title, "Transactions", SheetExport.rows(items, ZoneId.systemDefault())) to items.size
    }

    companion object { const val BACKUP_NAME = "Blue backup.json" }
}
