package app.blue.sms.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SmsMessageEntity::class,
        ScanEntity::class,
        TransactionEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        ForWhomEntity::class,
        MerchantMemoryEntity::class,
        UpcomingItemEntity::class,
        SpamSenderEntity::class,
        ActionLogEntity::class,
        MerchantAliasEntity::class,
        PendingLocationEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class BlueDatabase : RoomDatabase() {
    abstract fun smsDao(): SmsDao
    abstract fun scanDao(): ScanDao
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun forWhomDao(): ForWhomDao
    abstract fun accountDao(): AccountDao
    abstract fun spamSenderDao(): SpamSenderDao
    abstract fun merchantMemoryDao(): MerchantMemoryDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun upcomingDao(): UpcomingDao
    abstract fun backupDao(): BackupDao
    abstract fun merchantAliasDao(): MerchantAliasDao
    abstract fun pendingLocationDao(): PendingLocationDao

    companion object {
        const val NAME = "blue.db"

        fun build(context: Context): BlueDatabase =
            Room.databaseBuilder(context, BlueDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .addCallback(SeedCallback)
                // No destructive fallback: a missing migration must crash in testing, never wipe data.
                .build()
    }
}

/** Add one Migration per schema version bump. Schemas are exported to app/schemas. */
object Migrations {
    /** v2: starring, rule id per SMS, several SIM ids per scan, undecided count. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sms_message ADD COLUMN rule_id TEXT")
            db.execSQL("ALTER TABLE sms_message ADD COLUMN is_starred INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sms_message ADD COLUMN starred_at INTEGER")
            db.execSQL(
                """CREATE TABLE scan_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    started_at INTEGER NOT NULL, finished_at INTEGER,
                    range_from INTEGER NOT NULL, range_to INTEGER NOT NULL,
                    subscription_ids TEXT NOT NULL,
                    sms_read INTEGER NOT NULL, sms_new INTEGER NOT NULL, sms_already_stored INTEGER NOT NULL,
                    new_cards INTEGER NOT NULL, new_upcoming INTEGER NOT NULL, new_spam INTEGER NOT NULL,
                    ignored INTEGER NOT NULL, undecided INTEGER NOT NULL, error TEXT)"""
            )
            db.execSQL(
                """INSERT INTO scan_new (id, started_at, finished_at, range_from, range_to, subscription_ids,
                    sms_read, sms_new, sms_already_stored, new_cards, new_upcoming, new_spam, ignored, undecided, error)
                   SELECT id, started_at, finished_at, range_from, range_to, CAST(subscription_id AS TEXT),
                    sms_read, sms_new, sms_already_stored, new_cards, new_upcoming, new_spam, ignored, 0, error
                   FROM scan"""
            )
            db.execSQL("DROP TABLE scan")
            db.execSQL("ALTER TABLE scan_new RENAME TO scan")
        }
    }

    /** v3: matching keys for Upcoming items. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE upcoming_item ADD COLUMN match_key TEXT")
            db.execSQL("ALTER TABLE upcoming_item ADD COLUMN accepted_at INTEGER")
            db.execSQL("ALTER TABLE upcoming_item ADD COLUMN closed_at INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_upcoming_item_match_key ON upcoming_item (match_key)")
        }
    }

    /** v4: Gemini retry bookkeeping per SMS. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sms_message ADD COLUMN ai_attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sms_message ADD COLUMN ai_error TEXT")
        }
    }

    /** v5: payment location on entries, merchant nicknames, locations waiting for their SMS. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN loc_lat REAL")
            db.execSQL("ALTER TABLE transactions ADD COLUMN loc_lng REAL")
            db.execSQL("ALTER TABLE transactions ADD COLUMN loc_accuracy REAL")
            db.execSQL("ALTER TABLE transactions ADD COLUMN loc_at INTEGER")
            db.execSQL("CREATE TABLE IF NOT EXISTS `merchant_alias` (`merchant_key` TEXT NOT NULL, `nickname` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`merchant_key`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `pending_location` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sender` TEXT NOT NULL, `body_hash` TEXT NOT NULL, `received_at` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `accuracy` REAL, `captured_at` INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_location_received_at` ON `pending_location` (`received_at`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}

val DEFAULT_CATEGORIES = listOf(
    "Food & dining", "Groceries", "Transport", "Fuel", "Shopping", "Bills & utilities", "Rent",
    "Subscriptions", "Health", "Entertainment", "Travel", "Personal care", "Gifts", "Education",
    "Investment", "Self-transfer", "Income", "Refund", "Other",
)

private object SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        DEFAULT_CATEGORIES.forEachIndexed { i, name ->
            db.execSQL("INSERT INTO category (name, is_active, sort_order) VALUES (?, 1, ?)", arrayOf(name, i))
        }
        db.execSQL("INSERT INTO for_whom (type, name, contact_lookup_key, is_active) VALUES ('ME', 'Me', NULL, 1)")
    }
}
