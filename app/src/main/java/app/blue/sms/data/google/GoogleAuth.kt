package app.blue.sms.data.google

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthResult {
    data class Token(val accessToken: String) : AuthResult
    /** First time (or after revoking): Google shows an account picker and consent screen. */
    data class NeedsConsent(val intent: PendingIntent) : AuthResult
    data class Failed(val message: String) : AuthResult
}

/**
 * Google authorization for one scope only: drive.file (files Blue creates). The Android OAuth client
 * (package app.blue.sms + signing SHA-1) in Google Cloud is what lets this work; no client id is in the app.
 */
@Singleton
class GoogleAuth @Inject constructor(@ApplicationContext private val context: Context) {
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE))).build()

    suspend fun authorize(): AuthResult = try {
        val r = Identity.getAuthorizationClient(context).authorize(request).await()
        when {
            r.hasResolution() -> AuthResult.NeedsConsent(r.pendingIntent!!)
            r.accessToken != null -> AuthResult.Token(r.accessToken!!)
            else -> AuthResult.Failed("Google gave no access token")
        }
    } catch (e: ApiException) {
        AuthResult.Failed(explain(e))
    } catch (e: Exception) {
        AuthResult.Failed("Google sign-in failed: ${e.message}")
    }

    fun fromConsent(data: Intent?): AuthResult = try {
        val r = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        r.accessToken?.let { AuthResult.Token(it) } ?: AuthResult.Failed("No access granted")
    } catch (e: ApiException) {
        AuthResult.Failed(if (e.statusCode == CommonStatusCodes.CANCELED) "Cancelled" else explain(e))
    }

    private fun explain(e: ApiException) = when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Google Cloud setup isn't finished: check the Android OAuth client (package app.blue.sms and this build's SHA-1) and that the Drive and Sheets APIs are enabled."
        CommonStatusCodes.NETWORK_ERROR -> "No connection to Google."
        CommonStatusCodes.SIGN_IN_REQUIRED -> "Sign in to a Google account on this phone first."
        else -> "Google sign-in failed (code ${e.statusCode})."
    }

    companion object { const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file" }
}
