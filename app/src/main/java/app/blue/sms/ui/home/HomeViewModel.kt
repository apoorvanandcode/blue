package app.blue.sms.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.blue.sms.data.db.ScanDao
import app.blue.sms.data.db.ScanEntity
import app.blue.sms.data.db.SmsDao
import app.blue.sms.data.db.TransactionDao
import app.blue.sms.data.db.UpcomingDao
import app.blue.sms.data.db.UpcomingItemEntity
import app.blue.sms.domain.upcoming.UpcomingActions
import app.blue.sms.data.prefs.SettingsRepository
import app.blue.sms.data.sms.simLabels
import app.blue.sms.domain.scan.ScanSms
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class HomeUiState(
    val rangeDays: Int = 7,
    val subscriptionIds: Set<Int> = emptySet(),
    val simLabel: String? = null,
    val scanning: Boolean = false,
    val error: String? = null,
    val lastScan: ScanEntity? = null,
    val storedSmsCount: Int = 0,
    val pendingCount: Int = 0,
    val skippedCount: Int = 0,
    val monthSpendPaise: Long = 0,
    val monthUnconfirmed: Int = 0,
    val starredCount: Int = 0,
    val dailyScan: Boolean = true,
    val dailyScanMinutes: Int = 1200,
    val upcoming: List<UpcomingItemEntity> = emptyList(),
    val proposals: Int = 0,
    /** Same elapsed days of last month, for "₹X by this time in October". */
    val lastMonthSoFarPaise: Long = 0,
    val monthName: String = "",
    val lastMonthName: String = "",
)

private data class Counts(val sms: Int, val pending: Int, val skipped: Int, val spend: Long, val unconfirmed: Int, val starred: Int = 0)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val scanSms: ScanSms,
    scanDao: ScanDao,
    smsDao: SmsDao,
    private val txnDao: TransactionDao,
    upcomingDao: UpcomingDao,
    private val upcomingActions: UpcomingActions,
) : ViewModel() {

    private val rangeOverride = MutableStateFlow<Int?>(null)
    private val scanning = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val zone = ZoneId.systemDefault()
    private val monthStart = YearMonth.now().atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val lastMonthStart = YearMonth.now().minusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    /** The same moment last month (clipped to its length), so the comparison is like for like. */
    private val lastMonthSameTime = java.time.ZonedDateTime.now(zone).minusMonths(1).toInstant().toEpochMilli()
    private val monthName = YearMonth.now().month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
    private val lastMonthName = YearMonth.now().minusMonths(1).month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)

    private val counts = combine(
        combine(smsDao.observeCount(), smsDao.observeStarredCount()) { a, b -> a to b },
        txnDao.observeCountWithStatus("PENDING"),
        txnDao.observeCountWithStatus("SKIPPED"),
        txnDao.observeSpendSince(monthStart),
        txnDao.observeUnconfirmedSince(monthStart),
    ) { (sms, starred), pending, skipped, spend, unconfirmed -> Counts(sms, pending, skipped, spend, unconfirmed, starred) }

    private val base = combine(
        settingsRepo.settings,
        rangeOverride,
        combine(scanning, error) { s, e -> s to e },
        scanDao.observeLatest(),
        counts,
    ) { settings, override, (isScanning, err), last, c ->
        HomeUiState(
            rangeDays = override ?: settings.scanRangeDays,
            subscriptionIds = settings.smsSubscriptionIds,
            simLabel = settings.smsSubscriptionIds.takeIf { it.isNotEmpty() }?.let(::simLabels),
            scanning = isScanning,
            error = err,
            lastScan = last,
            storedSmsCount = c.sms,
            pendingCount = c.pending,
            skippedCount = c.skipped,
            monthSpendPaise = c.spend,
            monthUnconfirmed = c.unconfirmed,
            starredCount = c.starred,
            dailyScan = settings.dailyScan,
            dailyScanMinutes = settings.dailyScanMinutes,
        )
    }

    val state: StateFlow<HomeUiState> = combine(
        base, upcomingDao.observeByStatus("ACTIVE"), upcomingDao.observeCount("PROPOSED"),
        txnDao.observeSpendBetween(lastMonthStart, lastMonthSameTime),
    ) { b, active, proposals, lastSoFar ->
        b.copy(upcoming = active, proposals = proposals, lastMonthSoFarPaise = lastSoFar, monthName = monthName, lastMonthName = lastMonthName)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun closeUpcoming(id: Long) = viewModelScope.launch { upcomingActions.close(id) }
    fun dismissUpcoming(id: Long) = viewModelScope.launch { upcomingActions.dismiss(id) }

    fun setRange(days: Int) { rangeOverride.value = days }

    fun showError(message: String?) { error.value = message }

    fun scan() {
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true
            error.value = null
            try {
                val s = settingsRepo.settings.first()
                if (s.smsSubscriptionIds.isEmpty()) {
                    error.value = "Choose a SIM first."
                } else {
                    scanSms(rangeOverride.value ?: s.scanRangeDays, s.smsSubscriptionIds)
                }
            } catch (e: SecurityException) {
                error.value = "SMS permission is off. Tap Scan to allow it."
            } catch (e: Exception) {
                error.value = "Scan failed: ${e.message ?: e::class.simpleName}"
            } finally {
                scanning.value = false
            }
        }
    }
}
