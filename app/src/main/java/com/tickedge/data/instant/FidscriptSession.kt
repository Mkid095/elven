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
 * The FIDScript session that makes the app's data cross-device.
 *
 * The identity is the **person's email**, signed in with a magic code. That is deliberate: Deriv's
 * own OAuth cannot feed FIDScript — it issues no ID token to third-party apps and no refresh
 * tokens, so the `openid`-scoped path is not available. Magic code requires no Deriv change at
 * all, and the email is the one identifier that follows a person to their next phone.
 *
 * One session, not one per Deriv account: a person may link several Deriv accounts, and the
 * point of the cloud is that their record is one record. Splitting it per Deriv account would
 * defeat the purpose.
 *
 * The returned `refresh_token` is the credential used for every subsequent
 * `POST /runtime/:app_id/transact` and `POST /runtime/framework/query`. It has no server-side
 * expiry — rotation is explicit via `POST /runtime/signout`, then sign in again.
 *
 * Failure here is non-fatal. The app keeps working with local Room state and Deriv trading;
 * only cross-device sync is unavailable.
 */
class FidscriptSession(context: Context) {

    /**
     * The session for the TickEdge account: one person, all of their Deriv accounts, all devices.
     */
    private val store = SecureTokenStore(context.applicationContext, PREFS_NAME)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** An established FIDScript session. [userId] is FIDScript's `auth.id` for this user. */
    data class Session(
        val userId: String,
        val refreshToken: String,
        val email: String? = null,
        /** True when the server created the account on this verification — i.e. this is the sign-up. */
        val created: Boolean = false
    )

    /** The stored FIDScript user id, or null when not signed in. */
    fun userId(): String? = store.getEncrypted(KEY_USER_ID)

    /** The stored FIDScript session token, or null when not signed in. */
    fun refreshToken(): String? = store.getEncrypted(KEY_REFRESH)

    /** The email the cloud session was created with, or null. */
    fun email(): String? = store.getEncrypted(KEY_EMAIL)

    /** Whether a registration (first/last/username) has been stored for this session, or null. */
    fun hasProfile(): Boolean? =
        when (val raw = store.getEncrypted(KEY_PROFILE)) {
            null -> null
            "" -> false
            else -> true
        }

    fun isSignedIn(): Boolean = !refreshToken().isNullOrBlank()

    /** The stored cloud session, or null when not signed in. */
    fun session(): Session? {
        val user = userId() ?: return null
        val refresh = refreshToken() ?: return null
        return Session(user, refresh, email())
    }

    /**
     * Sends the sign-in code to [email].
     *
     * This is "create your free TickEdge account": the first sign-in from an email creates it,
     * and every later sign-in from the same email finds it — which is what makes the next phone
     * show the same history and settings.
     */
    suspend fun sendMagicCode(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BuildConfig.INSTANT_API_BASE}/runtime/auth/send_magic_code")
            .post(JSONObject()
                .put("app-id", BuildConfig.INSTANT_APP_ID)
                .put("email", email)
                .toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException(
                    "Could not send the sign-in code (HTTP ${response.code})."
                ))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Verifies the code and stores the resulting session.
     *
     * The email is persisted with the session so the user can see which account they are signed
     * in as, and a reinstalled device knows whose history to expect.
     */
    suspend fun verifyMagicCode(email: String, code: String): Result<Session> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${BuildConfig.INSTANT_API_BASE}/runtime/auth/verify_magic_code")
                .post(JSONObject()
                    .put("app-id", BuildConfig.INSTANT_APP_ID)
                    .put("email", email)
                    .put("code", code)
                    .toString().toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .build()

            try {
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val detail = runCatching { JSONObject(body).optString("message") }
                            .getOrDefault("HTTP ${response.code}")
                        return@withContext Result.failure(
                            IOException(
                                "Sign-in failed: " +
                                    (detail.ifBlank { "the code may have expired — request a new one." })
                            )
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
                    store.putEncrypted(KEY_EMAIL, email)
                    // `created` tells the app this verification is the sign-up, not a sign-in —
                    // the difference the registration step keys off: an account that has just
                    // been created owes the platform a name.
                    Result.success(Session(newUserId, newRefresh, email, user.optBoolean("created", false)))
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /**
     * Persists the account registration (first name, last name, username) alongside the session.
     *
     * Stored on the device, not the cloud, because the cloud copy is a separate concern — the
     * registration is local identity metadata the app uses to seed the display name, and it
     * survives reinstall so a returning user is not re-asked.
     */
    fun storeProfile(firstName: String, lastName: String, username: String) {
        store.putEncrypted(KEY_PROFILE, "$firstName|$lastName|$username")
    }

    /**
     * The stored registration, as (firstName, lastName, username) — or null when not set.
     *
     * The empty marker is distinct from "not set": a device that signed up and then the user
     * wants to re-do the names has an empty profile, not a missing one.
     */
    fun profile(): Triple<String, String, String>? {
        val raw = store.getEncrypted(KEY_PROFILE) ?: return null
        if (raw.isEmpty()) return Triple("", "", "")
        val parts = raw.split('|')
        if (parts.size < 3) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    /** Clears the local cloud session. */
    fun signOut() = store.clear()

    private companion object {
        const val PREFS_NAME = "fidscript_secure"
        const val KEY_USER_ID = "user_id"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EMAIL = "email"
        const val KEY_PROFILE = "profile"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
