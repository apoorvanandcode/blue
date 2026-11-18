package app.blue.sms.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val scanRangeDays: Int = DEFAULT_SCAN_DAYS,
    /** SIM subscription ids to read. Empty until the user picks on first scan. -1 = SMS with no SIM id. */
    val smsSubscriptionIds: Set<Int> = emptySet(),
    /** Gemini model code. Cheapest Flash-Lite open to new keys as of Nov 2026. */
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    /** Scan automatically every evening between 8 and 9 PM. */
    val dailyScan: Boolean = true,
    /** Time of day for the automatic scan, in minutes after midnight. Default 8:00 PM. */
    val dailyScanMinutes: Int = DEFAULT_DAILY_SCAN_MINUTES,
    /** Save where the phone is when a payment SMS arrives. Off until the user turns it on. */
    val saveLocation: Boolean = false,
) {
    companion object {
        const val DEFAULT_SCAN_DAYS = 7
        const val DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite"
        const val DEFAULT_DAILY_SCAN_MINUTES = 20 * 60
    }
}

private val Context.blueSettings by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val rangeKey = intPreferencesKey("scan_range_days")
    private val subsKey = stringSetPreferencesKey("sms_subscription_ids")
    /** Phase 1 stored a single id here. Read as a fallback. */
    private val legacySubKey = intPreferencesKey("sms_subscription_id")
    private val modelKey = stringPreferencesKey("gemini_model")
    private val dailyKey = booleanPreferencesKey("daily_scan")
    private val dailyTimeKey = intPreferencesKey("daily_scan_minutes")
    private val locationKey = booleanPreferencesKey("save_location")

    val settings: Flow<AppSettings> = context.blueSettings.data.map { p ->
        AppSettings(
            scanRangeDays = p[rangeKey] ?: AppSettings.DEFAULT_SCAN_DAYS,
            smsSubscriptionIds = p[subsKey]?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: p[legacySubKey]?.let { setOf(it) }
                ?: emptySet(),
            geminiModel = p[modelKey]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_GEMINI_MODEL,
            dailyScan = p[dailyKey] ?: true,
            dailyScanMinutes = p[dailyTimeKey] ?: AppSettings.DEFAULT_DAILY_SCAN_MINUTES,
            saveLocation = p[locationKey] ?: false,
        )
    }

    suspend fun setDailyScanMinutes(minutes: Int) {
        context.blueSettings.edit { it[dailyTimeKey] = minutes.coerceIn(0, 24 * 60 - 1) }
    }

    suspend fun setSaveLocation(on: Boolean) {
        context.blueSettings.edit { it[locationKey] = on }
    }

    suspend fun setDailyScan(on: Boolean) {
        context.blueSettings.edit { it[dailyKey] = on }
    }

    suspend fun setGeminiModel(model: String) {
        context.blueSettings.edit { it[modelKey] = model.trim() }
    }

    suspend fun setScanRangeDays(days: Int) {
        context.blueSettings.edit { it[rangeKey] = days.coerceAtLeast(1) }
    }

    suspend fun setSmsSubscriptionIds(ids: Set<Int>) {
        context.blueSettings.edit {
            it[subsKey] = ids.map(Int::toString).toSet()
            it.remove(legacySubKey)
        }
    }
}
