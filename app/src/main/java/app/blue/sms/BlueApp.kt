package app.blue.sms

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.blue.sms.data.secure.SecretStore
import app.blue.sms.work.AiScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class BlueApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var secrets: SecretStore
    @Inject lateinit var aiScheduler: AiScheduler
    @Inject lateinit var dailyScan: app.blue.sms.work.DailyScanScheduler
    @Inject lateinit var settings: app.blue.sms.data.prefs.SettingsRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Pick up anything left in the Gemini queue (it waits for a connection).
        if (secrets.hasGeminiKey.value) aiScheduler.enqueue()
        // Make sure tonight's automatic scan is booked (keeps an existing booking).
        kotlinx.coroutines.MainScope().launch {
            val s = settings.settings.first()
            if (s.dailyScan) dailyScan.scheduleNext(s.dailyScanMinutes, replace = false)
        }
    }
}
