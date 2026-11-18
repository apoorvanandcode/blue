package app.blue.sms.di

import android.content.Context
import app.blue.sms.data.db.BlueDatabase
import app.blue.sms.data.sms.AndroidSmsSource
import app.blue.sms.data.ai.GeminiClient
import app.blue.sms.domain.ai.AiExtractor
import app.blue.sms.domain.parse.RuleClassifier
import app.blue.sms.domain.parse.SmsClassifier
import app.blue.sms.domain.sms.SmsSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): BlueDatabase = BlueDatabase.build(context)

    @Provides fun smsDao(db: BlueDatabase) = db.smsDao()
    @Provides fun scanDao(db: BlueDatabase) = db.scanDao()
    @Provides fun transactionDao(db: BlueDatabase) = db.transactionDao()
    @Provides fun categoryDao(db: BlueDatabase) = db.categoryDao()
    @Provides fun forWhomDao(db: BlueDatabase) = db.forWhomDao()
    @Provides fun accountDao(db: BlueDatabase) = db.accountDao()
    @Provides fun spamSenderDao(db: BlueDatabase) = db.spamSenderDao()
    @Provides fun merchantMemoryDao(db: BlueDatabase) = db.merchantMemoryDao()
    @Provides fun actionLogDao(db: BlueDatabase) = db.actionLogDao()
    @Provides fun upcomingDao(db: BlueDatabase) = db.upcomingDao()
    @Provides fun backupDao(db: BlueDatabase) = db.backupDao()
    @Provides fun merchantAliasDao(db: BlueDatabase) = db.merchantAliasDao()
    @Provides fun pendingLocationDao(db: BlueDatabase) = db.pendingLocationDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun smsSource(impl: AndroidSmsSource): SmsSource
    @Binds abstract fun classifier(impl: RuleClassifier): SmsClassifier
    @Binds abstract fun aiExtractor(impl: GeminiClient): AiExtractor
}
