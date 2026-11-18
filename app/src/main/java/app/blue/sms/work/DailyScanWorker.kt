package app.blue.sms.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.blue.sms.data.db.ScanDao
import app.blue.sms.data.prefs.SettingsRepository
import app.blue.sms.data.sms.hasSmsPermission
import app.blue.sms.domain.scan.ScanSms
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans automatically once a day at the time chosen in Settings (default 8:00 PM). Reads everything since the last scan
 * (at least 2 days, at most 60), so a missed evening is caught up next time. SMS already stored
 * are skipped by their SMS provider _id, so this never creates duplicates with a manual Scan.
 */
@HiltWorker
class DailyScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val scanDao: ScanDao,
    private val scanSms: ScanSms,
    private val scheduler: DailyScanScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = settings.settings.first()
        try {
            if (s.dailyScan && s.smsSubscriptionIds.isNotEmpty() && hasSmsPermission(applicationContext)) {
                val last = scanDao.observeLatest().first()?.startedAt
                val days = if (last == null) 7 else
                    (Duration.ofMillis(System.currentTimeMillis() - last).toDays().toInt() + 2).coerceIn(2, 60)
                scanSms(days, s.smsSubscriptionIds)
            }
        } finally {
            if (s.dailyScan) scheduler.scheduleNext(s.dailyScanMinutes)
        }
        return Result.success()
    }
}

@Singleton
class DailyScanScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    /** Next run at [minutes] after midnight: today if that is still ahead, else tomorrow. */
    fun scheduleNext(minutes: Int, replace: Boolean = true) {
        val now = ZonedDateTime.now()
        var at = now.with(LocalTime.of(minutes / 60, minutes % 60)).withSecond(0).withNano(0)
        if (!at.isAfter(now.plusMinutes(5))) at = at.plusDays(1)
        val request = OneTimeWorkRequestBuilder<DailyScanWorker>()
            .setInitialDelay(Duration.between(now, at).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request,
        )
    }

    fun cancel() = WorkManager.getInstance(context).cancelUniqueWork(NAME)

    private companion object { const val NAME = "daily_scan" }
}
