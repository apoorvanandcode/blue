package app.blue.sms.data.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import app.blue.sms.domain.sms.ALL_SIMS
import app.blue.sms.domain.sms.RawSms
import app.blue.sms.domain.sms.SimSlotSummary
import app.blue.sms.domain.sms.SmsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

fun hasSmsPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

/** 1-based slot for a subscription. Needs no permission. Null if that SIM isn't active. */
fun slotNumberFor(subscriptionId: Int): Int? =
    SubscriptionManager.getSlotIndex(subscriptionId).takeIf { it >= 0 }?.plus(1)

fun simLabel(subscriptionId: Int): String = when {
    subscriptionId < 0 -> "No SIM id (older or restored SMS)"
    else -> slotNumberFor(subscriptionId)?.let { "SIM slot $it" } ?: "Old SIM id (not active now)"
}

/** e.g. "SIM slot 1 (id 3), Old SIM id (id 1)" */
fun simLabels(ids: Set<Int>): String = if (ALL_SIMS in ids) "All SMS on this phone" else
    ids.sortedWith(compareBy<Int> { it < 0 }.thenByDescending { it }).joinToString(", ") { "${simLabel(it)} (id $it)" }

/** Reads the system SMS inbox with a query only. Never writes to the provider. */
class AndroidSmsSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsSource {

    override suspend fun readInbox(fromMillis: Long, toMillis: Long, subscriptionIds: Set<Int>): List<RawSms> =
        withContext(Dispatchers.IO) {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.SUBSCRIPTION_ID,
            )
            if (subscriptionIds.isEmpty()) return@withContext emptyList()
            // "All SMS" reads every SIM, old ids and restored SMS included.
            val ids = if (ALL_SIMS in subscriptionIds) emptyList() else subscriptionIds.toList()
            val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?" +
                if (ids.isEmpty()) "" else " AND ${Telephony.Sms.SUBSCRIPTION_ID} IN (${ids.joinToString(",") { "?" }})"
            val args = arrayOf(fromMillis.toString(), toMillis.toString()) + ids.map(Int::toString)
            val out = ArrayList<RawSms>()
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection, selection, args, "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iSub = c.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)
                while (c.moveToNext()) {
                    out += RawSms(
                        providerId = c.getLong(iId),
                        sender = c.getString(iAddr).orEmpty(),
                        body = c.getString(iBody).orEmpty(),
                        receivedAt = c.getLong(iDate),
                        subscriptionId = c.getInt(iSub),
                    )
                }
            }
            out
        }

    override suspend fun simSlotsSeen(): List<SimSlotSummary> = withContext(Dispatchers.IO) {
        class Acc(var count: Int = 0, var latest: Long = 0, val senders: HashMap<String, Int> = HashMap())
        val bySub = HashMap<Int, Acc>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.SUBSCRIPTION_ID, Telephony.Sms.ADDRESS, Telephony.Sms.DATE),
            null, null, null,
        )?.use { c ->
            val iSub = c.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val acc = bySub.getOrPut(c.getInt(iSub)) { Acc() }
                acc.count++
                acc.latest = maxOf(acc.latest, c.getLong(iDate))
                val addr = c.getString(iAddr).orEmpty()
                acc.senders[addr] = (acc.senders[addr] ?: 0) + 1
            }
        }
        bySub.map { (sub, acc) ->
            SimSlotSummary(
                subscriptionId = sub,
                slotNumber = if (sub < 0) null else slotNumberFor(sub),
                messageCount = acc.count,
                topSenders = acc.senders.entries.sortedByDescending { it.value }.take(6).map { it.key },
                latestAt = acc.latest,
            )
        }.sortedWith(compareBy<SimSlotSummary> { it.slotNumber ?: Int.MAX_VALUE }.thenByDescending { it.messageCount })
    }
}
