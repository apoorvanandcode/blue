package app.blue.sms.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.blue.sms.data.secure.SecretStore
import app.blue.sms.domain.ai.AiProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Works through the Gemini queue whenever the phone is online. */
@HiltWorker
class AiWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: AiProcessor,
    private val secrets: SecretStore,
    private val scheduler: AiScheduler,
    private val status: AiStatus,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!secrets.hasGeminiKey.value) return Result.success()
        val r = processor.runQueue()
        status.setProblem(r.setupProblem)
        return when {
            r.setupProblem != null -> Result.success() // waits until the key or model is fixed in Settings
            r.retryLater -> Result.retry()
            r.moreLeft -> { scheduler.enqueue(); Result.success() }
            else -> Result.success()
        }
    }
}

@Singleton
class AiScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<AiWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object { const val NAME = "gemini_queue" }
}

/** Last key/model/billing problem the queue hit, shown in Settings. */
@Singleton
class AiStatus @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("ai_status", Context.MODE_PRIVATE)
    private val _problem = kotlinx.coroutines.flow.MutableStateFlow(prefs.getString("problem", null))
    val problem: kotlinx.coroutines.flow.StateFlow<String?> = _problem

    fun setProblem(p: String?) {
        prefs.edit().apply { if (p == null) remove("problem") else putString("problem", p) }.apply()
        _problem.value = p
    }
}
