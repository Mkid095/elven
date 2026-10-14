package com.tickedge.data.instant

import android.content.Context
import com.tickedge.BuildConfig
import com.tickedge.auth.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The FIDScript session used for cross-device sync.
 *
 * FIDScript is configured as a **public** custom-OIDC client (`deriv-prod`) against Deriv. That
 * means FIDScript never initiates a browser redirect and holds no client secret: the app obtains
 * the ID token from Deriv itself and posts it here, and FIDScript verifies it against Deriv's
 * JWKS before issuing a session.
 *
 * The returned `refresh_token` is the credential used for every subsequent
 * `POST /runtime/:app_id/transact` and `POST /runtime/framework/query`. It has no server-side
 * expiry — rotation is explicit via `POST /runtime/signout`, then sign in again.
 *
 * Failure here is non-fatal. The app keeps working with local Room state and Deriv trading;
 * only cross-device sync is unavailable.
 */
class FidscriptSession(context: Context) {

    /** The FIDScript OAuth client configured for Deriv. Must match the dashboard client name. */
    private val clientName = "deriv-prod"

    private val store = SecureTokenStore(context.applicationContext, PREFS_NAME)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** An established FIDScript session. [userId] is FIDScript's `auth.id` for this user. */
    data class Session(val userId: String, val refreshToken: String)

    /** The stored FIDScript user id, or null when not signed in. */
    fun userId(): String? = store.getEncrypted(KEY_USER_ID)

    /** The stored FIDScript session token, or null when not signed in. */
    fun refreshToken(): String? = store.getEncrypted(KEY_REFRESH)

    fun isSignedIn(): Boolean = !refreshToken().isNullOrBlank()

    /**
     * Exchange a Deriv ID token for a FIDScript session.
     *
     * The `sub` claim in the ID token becomes FIDScript's `auth.id`, which is what the row-level
     * permissions (`auth.id == data.userId`) key off. Because of that, the value written to a
     * record's `userId` field MUST be this value — never the Deriv app id.
     */
    suspend fun signIn(idToken: String): Result<Session> = withContext(Dispatchers.IO) {
        if (idToken.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "No Deriv ID token available. DERIV_OAUTH_SCOPES must include 'openid'."
                )
            )
        }

        val payload = JSONObject()
            .put("app_id", BuildConfig.INSTANT_APP_ID)
            .put("client_name", clientName)
            .put("id_token", idToken)

        val request = Request.Builder()
            .url("${BuildConfig.INSTANT_API_BASE}/runtime/oauth/id_token")
            .post(payload.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    // Surface the server's own message; it distinguishes a malformed
                    // token from an expired one, which matters when debugging login.
                    val detail = runCatching {
                        JSONObject(body).let {
                            it.optString("message").ifBlank { it.optString("error") }
                        }
                    }.getOrDefault("HTTP ${response.code}")

                    return@withContext Result.failure(
                        IOException("FIDScript sign-in failed (HTTP ${response.code}): $detail")
                    )
                }

                val user = JSONObject(body).optJSONObject("user")
                    ?: return@withContext Result.failure(
                        IOException("FIDScript sign-in response contained no user object")
                    )

                val newUserId = user.optString("id")
                val newRefresh = user.optString("refresh_token")
                if (newUserId.isBlank() || newRefresh.isBlank()) {
                    return@withContext Result.failure(
                        IOException("FIDScript sign-in response missing 'id' or 'refresh_token'")
                    )
                }

                store.putEncrypted(KEY_USER_ID, newUserId)
                store.putEncrypted(KEY_REFRESH, newRefresh)
                Result.success(Session(newUserId, newRefresh))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** Clear the local FIDScript session. Local Room data is untouched. */
    fun signOut() = store.clear()

    private companion object {
        const val PREFS_NAME = "fidscript_secure"
        const val KEY_USER_ID = "user_id"
        const val KEY_REFRESH = "refresh_token"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}