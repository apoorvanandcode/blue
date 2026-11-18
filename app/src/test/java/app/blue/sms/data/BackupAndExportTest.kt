package app.blue.sms.data

import app.blue.sms.data.backup.BackupCodec
import app.blue.sms.data.backup.BackupFile
import app.blue.sms.data.backup.BackupSettings
import app.blue.sms.data.db.CategoryEntity
import app.blue.sms.data.db.ReviewItem
import app.blue.sms.data.db.SmsMessageEntity
import app.blue.sms.data.db.TransactionEntity
import app.blue.sms.data.google.SheetExport
import app.blue.sms.domain.model.AccountType
import app.blue.sms.domain.model.Direction
import app.blue.sms.domain.model.PaymentMethod
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.model.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BackupAndExportTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val at = LocalDateTime.of(2026, 11, 17, 2, 44).atZone(zone).toInstant().toEpochMilli()
    private val txn = TransactionEntity(
        id = 7, smsId = 3, amountPaise = 106082, direction = Direction.OUT, merchantRaw = "ATRIA CONVER", upiId = null,
        accountId = 1, method = PaymentMethod.UPI, refNo = "632144056022", txnAt = at, categoryId = 6, forWhomId = 1,
        notes = "Diwali", status = TxnStatus.CONFIRMED, extractedBy = null, createdAt = at,
    )

    @Test fun `backup survives encode and decode unchanged`() {
        val b = BackupFile(
            dbVersion = 4, createdAt = at, appVersion = "0.7.0",
            settings = BackupSettings(7, setOf(3, 1, -1), "gemini-3.1-flash-lite"),
            sms = listOf(SmsMessageEntity(3, 99, "AD-FEDBNK-S", "Debited Rs 1060.82…\nline 2 ₹", at, 3, SmsClass.TRANSACTION, null, 1, "federal.upi_debit", true, at)),
            scans = emptyList(), transactions = listOf(txn), accounts = emptyList(),
            categories = listOf(CategoryEntity(6, "Bills & utilities", true, 5)), forWhom = emptyList(),
            merchantMemory = emptyList(), upcoming = emptyList(), spamSenders = emptyList(),
        )
        assertEquals(b, BackupCodec.decode(BackupCodec.encode(b)))
    }

    @Test fun `sheet has a header and one full row per entry`() {
        val item = ReviewItem(
            txn.copy(locLat = 12.9716, locLng = 77.5946), "Debited…", "AD-FEDBNK-S", false, "Federal Bank", "4321", AccountType.SAVINGS,
            "Bills & utilities", "Me", null, null, null, nickname = "Internet bill",
        )
        val rows = SheetExport.rows(listOf(item), zone)
        assertEquals(2, rows.size)
        assertEquals(SheetExport.HEADER.size, rows[1].size)
        // The nickname has its own column; the bank's name is still exported as it is.
        assertEquals(listOf("2026-11-17", "02:44", 1060.82, "Out", "Internet bill", "ATRIA CONVER"), rows[1].take(6))
        assertEquals("X4321", rows[1][9])
        assertEquals("https://www.google.com/maps/search/?api=1&query=12.971600,77.594600", rows[1][15])
        assertEquals("Confirmed", rows[1][16])
    }

    @Test fun `a backup made before nicknames still restores`() {
        val old = """{"format":1,"dbVersion":4,"createdAt":1,"appVersion":"1.0.0",
            "settings":{"scanRangeDays":7,"smsSubscriptionIds":[3],"geminiModel":"m"},
            "sms":[],"scans":[],"transactions":[],"accounts":[],"categories":[],"forWhom":[],
            "merchantMemory":[],"upcoming":[],"spamSenders":[]}"""
        assertEquals(emptyList<Any>(), BackupCodec.decode(old.toByteArray()).nicknames)
    }
}
