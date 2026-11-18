package app.blue.sms.data.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.provider.Telephony
import android.util.Log
import app.blue.sms.data.db.PendingLocationDao
import app.blue.sms.data.db.PendingLocationEntity
import app.blue.sms.data.prefs.SettingsRepository
import app.blue.sms.domain.model.SmsClass
import app.blue.sms.domain.parse.SmsClassifier
import app.blue.sms.domain.parse.SmsInput
import app.blue.sms.domain.sms.SmsFingerprint
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** What "Save where I pay" needs from Android. */
object PaymentLocation {
    private fun granted(context: Context, p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun hasForeground(context: Context) =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Location while Blue is closed ("Allow all the time"). Needed because SMS arrive with Blue in the background. */
    fun hasBackground(context: Context) = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasReceiveSms(context: Context) = granted(context, Manifest.permission.RECEIVE_SMS)

    fun ready(context: Context) = hasForeground(context) && hasBackground(context) && hasReceiveSms(context)

    /** A recent fix if one is fresh enough, else asks for a new one for up to [timeoutMs]. */
    suspend fun current(context: Context, timeoutMs: Long = 8_000): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val last = runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull()
        if (last != null && now - last.time < 2 * 60_000 && last.accuracy <= 100f) return last
        // Ask every switched-on source at once; the first answer wins.
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.hasProvider(it) && lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return last
        val fresh = runCatching {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val cancels = providers.map { CancellationSignal() }
                    var pending = providers.size
                    cont.invokeOnCancellation { cancels.forEach { it.cancel() } }
                    providers.forEachIndexed { i, p ->
                        lm.getCurrentLocation(p, cancels[i], context.mainExecutor) { loc ->
                            pending--
                            if (cont.isActive && (loc != null || pending == 0)) {
                                cancels.forEach { it.cancel() }
                                cont.resume(loc)
                            }
                        }
                    }
                }
            }
        }.getOrNull()
        return fresh ?: last?.takeIf { now - it.time < 30 * 60_000 }
    }
}

/**
 * Notes where the phone is when a payment-looking SMS arrives. It reads nothing else: the SMS itself
 * is still picked up by the next scan, which attaches this location to the entry (or drops it).
 */
class PaymentLocationReceiver : BroadcastReceiver() {
    private companion object { const val TAG = "BluePayLocation" }
    /** Receivers can't take constructor injection; Hilt hands the pieces over through this. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun settings(): SettingsRepository
        fun classifier(): SmsClassifier
        fun pending(): PendingLocationDao
    }

    override fun onReceive(context: Context, intent: Intent) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val settings = deps.settings(); val classifier = deps.classifier(); val pending = deps.pending()
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()?.filterNotNull()
        if (parts.isNullOrEmpty()) return
        val sender = parts.first().displayOriginatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val arrivedAt = System.currentTimeMillis()
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!settings.settings.first().saveLocation || !PaymentLocation.ready(context)) { Log.d(TAG, "off or missing permission"); return@launch }
                // Only payments (or SMS the rules can't decide yet, which Gemini may call a payment).
                val cls = classifier.classify(SmsInput(sender, body, arrivedAt), emptySet()).smsClass
                if (cls != null && cls != SmsClass.TRANSACTION) { Log.d(TAG, "not a payment: $cls"); return@launch }
                val loc = PaymentLocation.current(context) ?: run { Log.d(TAG, "no location fix"); return@launch }
                Log.d(TAG, "saved location for $cls SMS")
                pending.insert(
                    PendingLocationEntity(
                        sender = sender,
                        bodyHash = SmsFingerprint.bodyHash(body),
                        receivedAt = arrivedAt,
                        lat = loc.latitude,
                        lng = loc.longitude,
                        accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                        capturedAt = arrivedAt,
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "could not save location", e)
                // Never let a location problem affect SMS delivery.
            } finally {
                result.finish()
            }
        }
    }
}
