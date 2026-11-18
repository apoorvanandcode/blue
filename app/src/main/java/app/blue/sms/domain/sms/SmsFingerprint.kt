package app.blue.sms.domain.sms

import java.security.MessageDigest

/** Matches an SMS caught on arrival with the same SMS read later from the inbox, without storing its text twice. */
object SmsFingerprint {
    fun bodyHash(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.trim().toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }

    /** How far apart the two arrival times may be. */
    const val WINDOW_MS = 15 * 60 * 1000L
}
