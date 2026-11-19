package app.blue.sms.data.ai

import android.content.Context
import app.blue.sms.data.db.SmsDao
import app.blue.sms.domain.ai.EnglishWords
import app.blue.sms.domain.ai.MaskVocabulary
import app.blue.sms.domain.ai.SmsMasker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the masker from the bundled word list and this phone's own SMS (which words are bank
 * boilerplate, which are the user's name). Rebuilt at most every few hours, as new SMS arrive.
 */
@Singleton
class MaskerProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsDao: SmsDao,
) {
    private val lock = Mutex()
    private var english: EnglishWords? = null
    private var masker: SmsMasker? = null
    private var builtAt = 0L

    suspend fun get(): SmsMasker = lock.withLock {
        val now = System.currentTimeMillis()
        masker?.takeIf { now - builtAt < REBUILD_MS }?.let { return it }
        withContext(Dispatchers.Default) {
            val words = english ?: context.assets.open(EnglishWords.ASSET).use(EnglishWords::load).also { english = it }
            val sms = smsDao.senderBodies().map { it.sender to it.body }
            SmsMasker(MaskVocabulary.build(words, sms)).also { masker = it; builtAt = now }
        }
    }

    private companion object { const val REBUILD_MS = 6 * 60 * 60 * 1000L }
}
