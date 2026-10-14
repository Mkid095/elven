package com.tickedge.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.tickedge.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Deriv OAuth2 (Authorization Code + PKCE, public client) for multi-user login.
 *
 * The app opens Deriv's authorization page in the *system browser* (never a WebView). On the
 * registered redirect it validates `state` + PKCE, exchanges the code for tokens, and stores them
 * in [SecureTokenStore].
 *
 * When [BuildConfig.DERIV_OAUTH_CLIENT_ID] is not configured, the app falls back to the embedded
 * dev PAT so a developer build still works. That fallback is dev-only, not for production.
 *
 * ## Lifecycle Safety
 *
 * Pending auth state (verifier + state) is persisted to encrypted storage before the browser
 * is launched. If the process is killed before the callback arrives, [rehydrate] will
 * recover the pending state. The pending auth is cleared on success, error, cancellation,
 * or expiry (15 minutes).
 *
 * [handleCallback] is **suspend** — the caller MUST await it before treating the callback as
 * complete. The redirect activity finishes only after the token exchange has been confirmed.
 */
class DerivOAuthClient(
    context: Context,
    private val scope: CoroutineScope
) {
    private val tokenStore = SecureTokenStore(context)
    private val appContext = context.applicationContext

    private val authEndpoint = "https://auth.deriv.com/oauth2/auth"
    private val tokenEndpoint = "https://auth.deriv.com/oauth2/token"
    private val appId = BuildConfig.DERIV_APP_ID
    /**
     * OAuth client id (Deriv app id registered for the OAuth flow).
     *
     * NOTE: this is NOT necessarily [appId]. Deriv treats "client_id" as "the app id of the app
     * you registered", and this project registers two:
     *  - `elvenoauth` — the OAuth app, used here as the client_id
     *  - `elven`      — the trading app, used for the WebSocket + Deriv-App-ID header
     *
     * The [appId] fallback below is only correct when a single Deriv app serves both roles.
     * When they differ, DERIV_OAUTH_CLIENT_ID MUST be set explicitly — otherwise the token
     * request is sent with the trading app's id and Deriv rejects it.
     */
    private val clientId: String
        get() = BuildConfig.DERIV_OAUTH_CLIENT_ID.takeIf { !isUnset(it) } ?: appId
    private val redirectUri = BuildConfig.DERIV_OAUTH_REDIRECT_URI
    private val scopes = BuildConfig.DERIV_OAUTH_SCOPES

    companion object {
        /**
         * Build-safe placeholder the Secrets plugin writes into BuildConfig when a credential
         * has not been provided yet (an empty .env value would generate illegal Java). Any value
         * equal to this sentinel is treated as "not configured".
         */
        const val UNSET = "not-configured"

        /** Logcat tag. Every auth failure and success is reported under this tag. */
        const val TAG = "DerivOAuth"

        internal fun isUnset(value: String?): Boolean =
            value.isNullOrBlank() || value.equals(UNSET, ignoreCase = true)

        /** Singleton accessor — same instance everywhere. Survives activity recreation. */
        @Volatile
        private var INSTANCE: DerivOAuthClient? = null

        fun share(context: Context, scope: CoroutineScope): DerivOAuthClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DerivOAuthClient(context.applicationContext, scope).also { INSTANCE = it }
            }
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _authState = MutableStateFlow(initialAuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * Single funnel for every authentication failure.
     *
     * Failures must reach logcat. Previously each site set `AuthenticationError` directly, so the
     * reason existed only in the UI and was invisible in a device log — which made sign-in bugs
     * undiagnosable without someone reading the screen aloud. Routing them all through here means
     * `adb logcat -s DerivOAuth` shows the exact reason.
     *
     * [cause] is logged as a stack trace where one exists; no token or code is ever logged.
     */
    private fun fail(message: String, cause: Throwable? = null) {
        if (cause != null) Log.w(TAG, message, cause) else Log.w(TAG, message)
        _authState.value = AuthState.AuthenticationError(message)
    }

    /** Log a successful sign-in, for symmetry with [fail] when tracing a login. */
    private fun succeed(user: DerivUser) {
        Log.i(TAG, "Signed in; stable key resolved (id length=${user.id.length})")
        _authState.value = AuthState.Authenticated(user)
    }

    private fun initialAuthState(): AuthState =
        if (hasToken()) AuthState.Authenticated(
            DerivUser(
                id = tokenStore.stableKey()?.takeIf { it.isNotBlank() } ?: tokenStore.userId().orEmpty(),
                openId = tokenStore.openId()
            )
        )
        else AuthState.Unauthenticated

    /** True when a registered OAuth client is configured for this app. */
    fun isOAuthConfigured(): Boolean = !isUnset(clientId)

    private fun hasToken() = !tokenStore.accessToken().isNullOrBlank()

    /**
     * The access token to use for authorized Deriv calls: the per-user OAuth token when present,
     * otherwise the embedded dev PAT (dev-only). Blank when neither is available.
     */
    fun accessToken(): String? =
        if (hasToken()) tokenStore.accessToken()
        else BuildConfig.DERIV_API_TOKEN.takeIf { !isUnset(it) }

    /**
     * The OIDC ID token from the last Deriv token exchange.
     *
     * Required by FIDScript's `POST /runtime/oauth/id_token` to establish the cross-device
     * session. Present only when the `openid` scope was granted (DERIV_OAUTH_SCOPES).
     * Returns null when Deriv did not issue one — FIDScript sync is then unavailable, but
     * Deriv login and local trading are unaffected.
     */
    fun idToken(): String? = tokenStore.idToken()

    fun currentUser(): DerivUser? = (authState.value as? AuthState.Authenticated)?.user

    /**
     * True when a login is in progress and a callback is expected.
     * Recovers across process death via [SecureTokenStore.getPendingAuth].
     */
    fun hasPendingAuth(): Boolean = tokenStore.getPendingAuth() != null

    /**
     * Re-derive the auth state from persisted tokens and pending auth.
     * Call on app resume to restore state after process death.
     *
     * If a pending auth has expired, it is cleared.
     */
    fun rehydrate() {
        // Clear expired pending auth first
        tokenStore.getPendingAuth()
        _authState.value = initialAuthState()
    }

    /**
     * Open Deriv's authorization page in the system browser.
     * Pending auth state is persisted to SecureTokenStore so it survives process death.
     */
    fun startLogin(activity: Activity) {
        if (!isOAuthConfigured()) {
            // Dev fallback: no OAuth registered; use the embedded PAT and mark authenticated.
            // ⚠️  This is NOT safe for production multi-user — every user gets the same dev identity.
            succeed(DerivUser(id = "dev", displayName = "Developer (PAT fallback)"))
            return
        }
        val verifier = Pkce.newVerifier()
        val challenge = Pkce.challengeFor(verifier)
        val state = Pkce.newNonce()
        val now = System.currentTimeMillis()

        // Persist BEFORE launching browser — survives process death
        tokenStore.savePendingAuth(verifier, state, now)

        _authState.value = AuthState.Authenticating

        val url = Uri.parse(authEndpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        activity.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Handle the OAuth redirect. Validates state, then exchanges the code for tokens.
     *
     * This is a **suspend** function. The caller MUST await the result before assuming
     * the callback has been processed. The token exchange is complete when this returns.
     *
     * @return true if session establishment has begun (caller can wait); false on
     *         immediate error (no session was started).
     */
    suspend fun handleCallback(data: Uri): Boolean {
        if (!isOAuthConfigured()) return hasToken()

        // Check for OAuth error from Deriv
        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val desc = data.getQueryParameter("error_description") ?: error
            clearPendingAuth()
            fail(desc)
            return false
        }

        val code = data.getQueryParameter("code")
        val returnedState = data.getQueryParameter("state")

        // Recover persisted pending auth (survives process death)
        val pending = tokenStore.getPendingAuth()
        when {
            pending == null -> {
                fail("No pending authorization found (session expired or already used)")
                return false
            }
            code.isNullOrBlank() -> {
                fail("No authorization code in callback")
                clearPendingAuth()
                return false
            }
            returnedState != pending.state -> {
                fail("Mismatched OAuth state — possible CSRF attack")
                clearPendingAuth()
                return false
            }
        }

        // Exchange code for tokens (suspend — must complete before activity finishes)
        val success = exchangeCode(code, pending.verifier)
        if (!success) {
            return false
        }

        // Auth is now established; clear pending state
        clearPendingAuth()
        return true
    }

    /**
     * Exchange the authorization code for tokens using the PKCE verifier.
     * Updates [authState] to Authenticated on success, or AuthenticationError on failure.
     *
     * @return true if tokens were successfully stored and auth state is now Authenticated
     */
    private suspend fun exchangeCode(code: String, verifier: String): Boolean {
        _authState.value = AuthState.Reconnecting

        return withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("code_verifier", verifier)
                .add("client_id", clientId)
                .add("redirect_uri", redirectUri)
                .build()

            val request = Request.Builder()
                .url(tokenEndpoint)
                .post(form)
                .build()

            try {
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val err = json.optString("error", "")
                    val errDesc = json.optString("error_description", "")

                    if (resp.code !in 200..299 || err.isNotBlank()) {
                        val msg = if (errDesc.isNotBlank()) "${err}: ${errDesc}"
                                   else if (err.isNotBlank()) err
                                   else "HTTP ${resp.code}"
                        fail("Token exchange failed: ${msg}")
                        return@use false
                    }

                    val accessToken = json.getString("access_token")
                    val expiresIn = json.optLong("expires_in", 0L)
                    val expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000L else 0L
                    val refreshToken = json.optString("refresh_token", "").ifBlank { null }

                    // The OIDC ID token. Present only when the `openid` scope was granted
                    // (DERIV_OAUTH_SCOPES). This is what FIDScript's /runtime/oauth/id_token
                    // verifies to establish the cross-device session. Absent is not fatal —
                    // Deriv login and local trading still work without it.
                    val idToken = json.optString("id_token", "").ifBlank { null }

                    // Fetch user identity from the OIDC userinfo endpoint.
                    // Deriv's openid-configuration declares claims_supported: ["sub"], so `sub`
                    // is the authoritative user identifier. Optionally includes email, name, etc.
                    val userInfo = fetchUserInfo(accessToken)
                    if (userInfo == null) {
                        fail("Token exchange succeeded but userinfo endpoint returned no identity")
                        return@use false
                    }

                    val userId = userInfo.optString("sub", "").ifBlank {
                        fail("Token exchange succeeded but userinfo response missing 'sub' claim")
                        return@use false
                    }
                    val email = userInfo.optString("email", "").ifBlank { null }
                    val name = userInfo.optString("name", "").ifBlank { null }
                    // openid scope may also return open_id
                    val openId = userInfo.optString("open_id", "").ifBlank { null }

                    // Persisting is deliberately NOT inside the outer try. If it fails, Deriv
                    // already issued valid tokens and the userinfo call already succeeded — the
                    // fault is local storage, and saying "token exchange error" sends the user
                    // hunting for a Deriv problem that does not exist.
                    try {
                        tokenStore.saveTokens(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            userId = userId,
                            openId = openId,
                            expiresAtEpochMs = expiresAt,
                            idToken = idToken
                        )
                    } catch (e: Exception) {
                        fail(
                            "Signed in with Deriv, but could not save the session on this " +
                                "device: ${e.message}",
                            e
                        )
                        return@use false
                    }

                    // `id` MUST equal stableKey. Downstream, local writes scope rows by
                    // DerivUser.stableKey while local reads scope them by DerivUser.id. If those
                    // two strings ever differ (Deriv returning both `sub` and `open_id`), trades
                    // are written under one key and queried under another, and history reads back
                    // permanently empty. Keeping them identical removes the whole class of bug.
                    val stableKey = openId?.takeIf { it.isNotBlank() } ?: userId

                    succeed(DerivUser(id = stableKey, openId = openId, displayName = name ?: email))
                    return@use true
                }
            } catch (e: Exception) {
                fail("Token exchange error: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Refresh the access token when it has expired.
     * Only supported if Deriv issues refresh tokens in the token response.
     */
    suspend fun refresh() {
        val rt = tokenStore.refreshToken()
        if (rt.isNullOrBlank() || !isOAuthConfigured()) return
        _authState.value = AuthState.Reconnecting

        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", rt)
                .add("client_id", clientId)
                .build()

            val request = Request.Builder()
                .url(tokenEndpoint)
                .post(form)
                .build()

            try {
                http.newCall(request).execute().use { resp ->
                    val json = JSONObject(resp.body?.string().orEmpty())
                    if (resp.code in 200..299 && json.has("access_token")) {
                        val expiresIn = json.optLong("expires_in", 0L)
                        val expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000L else 0L
                        tokenStore.saveTokens(
                            accessToken = json.getString("access_token"),
                            refreshToken = json.optString("refresh_token", rt),
                            userId = tokenStore.userId(),
                            openId = tokenStore.openId(),
                            expiresAtEpochMs = expiresAt,
                            // Prefer a freshly-issued ID token; otherwise keep the existing one
                            // so the FIDScript session survives an access-token refresh.
                            idToken = json.optString("id_token", "").ifBlank { tokenStore.idToken() }
                        )
                        // MUST use stableKey(), not userId(): the whole data layer is scoped by
                        // DerivUser.id, and `id` is defined as stableKey everywhere else. Reading
                        // userId() here made a refresh silently switch the key whenever Deriv
                        // returns both `sub` and `open_id`, leaving the user staring at empty
                        // history and settings that had "vanished".
                        succeed(
                            DerivUser(
                                id = tokenStore.stableKey()?.takeIf { it.isNotBlank() }
                                    ?: tokenStore.userId().orEmpty(),
                                openId = tokenStore.openId()
                            )
                        )
                    } else {
                        // Refresh token invalid — clear session
                        tokenStore.clear()
                        _authState.value = AuthState.SessionExpired
                    }
                }
            } catch (e: Exception) {
                fail("Refresh failed: ${e.message}", e)
            }
        }
    }

    /**
     * Clear the local session. Pending auth is also cleared.
     * Note: This does NOT revoke the Deriv OAuth token (Deriv may not support revocation).
     */
    fun logout() {
        clearPendingAuth()
        tokenStore.clear()
        _authState.value = AuthState.Unauthenticated
    }

    /**
     * Call the OIDC userinfo endpoint to retrieve the authenticated user's identity claims.
     * @param accessToken The bearer token obtained from the token exchange.
     * @return A JSONObject of userinfo claims, or null on failure.
     */
    private fun fetchUserInfo(accessToken: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("https://auth.deriv.com/userinfo")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            http.newCall(request).execute().use { resp ->
                if (resp.code in 200..299) {
                    JSONObject(resp.body?.string().orEmpty())
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun clearPendingAuth() {
        tokenStore.clearPendingAuth()
    }
}
