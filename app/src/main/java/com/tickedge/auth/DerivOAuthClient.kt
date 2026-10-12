package com.tickedge.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tickedge.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 */
class DerivOAuthClient(
    context: Context,
    private val scope: CoroutineScope
) {
    private val tokenStore = SecureTokenStore(context)

    private val authEndpoint = "https://auth.deriv.com/oauth2/auth"
    private val tokenEndpoint = "https://auth.deriv.com/oauth2/token"
    private val appId = BuildConfig.DERIV_APP_ID
    private val clientId = BuildConfig.DERIV_OAUTH_CLIENT_ID
    private val redirectUri = BuildConfig.DERIV_OAUTH_REDIRECT_URI
    private val scopes = BuildConfig.DERIV_OAUTH_SCOPES

    companion object {
        /**
         * Build-safe placeholder the Secrets plugin writes into BuildConfig when a credential
         * has not been provided yet (an empty .env value would generate illegal Java). Any value
         * equal to this sentinel is treated as "not configured".
         */
        const val UNSET = "not-configured"

        internal fun isUnset(value: String?): Boolean =
            value.isNullOrBlank() || value.equals(UNSET, ignoreCase = true)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var pendingVerifier = ""
    @Volatile
    private var pendingState = ""

    private val _authState = MutableStateFlow(initialAuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private fun initialAuthState(): AuthState =
        if (hasToken()) AuthState.Authenticated(DerivUser(id = tokenStore.userId().orEmpty(), openId = tokenStore.openId()))
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

    fun currentUser(): DerivUser? = (authState.value as? AuthState.Authenticated)?.user

    /** Open Deriv's authorization page in the system browser. */
    fun startLogin(activity: Activity) {
        if (!isOAuthConfigured()) {
            // Dev fallback: no OAuth registered; use the embedded PAT and mark authenticated.
            _authState.value = AuthState.Authenticated(DerivUser(id = "dev", displayName = "Developer (PAT fallback)"))
            return
        }
        val verifier = Pkce.newVerifier()
        val challenge = Pkce.challengeFor(verifier)
        val state = Pkce.newNonce()
        pendingVerifier = verifier
        pendingState = state
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
     * Handle the OAuth redirect (the [data] intent data). Validates state, then exchanges the code.
     * Returns whether a session was or is being established.
     */
    fun handleCallback(data: Uri): Boolean {
        if (!isOAuthConfigured()) return hasToken()
        val code = data.getQueryParameter("code")
        val returnedState = data.getQueryParameter("state")
        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            _authState.value = AuthState.AuthenticationError(error)
            return false
        }
        if (code.isNullOrBlank() || returnedState != pendingState) {
            _authState.value = AuthState.AuthenticationError("Invalid or mismatched OAuth callback")
            return false
        }
        scope.launch { exchangeCode(code) }
        return true
    }

    private suspend fun exchangeCode(code: String) {
        _authState.value = AuthState.Reconnecting
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("code_verifier", pendingVerifier)
                .add("client_id", clientId)
                .add("redirect_uri", redirectUri)
                .build()
            val request = Request.Builder().url(tokenEndpoint).post(form).build()
            http.newCall(request).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                val err = json.optString("error", "")
                if (resp.code !in 200..299 || err.isNotBlank()) {
                    _authState.value = AuthState.AuthenticationError("OAuth: ${err.ifBlank { "HTTP ${resp.code}" }}")
                    return@use
                }
                val expiresIn = json.optLong("expires_in", 0L)
                val expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000L else 0L
                val openId = json.optString("open_id", "").ifBlank { null }
                val userId = json.optString("id_token_subject", "").ifBlank { json.optString("sub", "") }.ifBlank { openId.orEmpty() }
                tokenStore.saveTokens(json.getString("access_token"), json.optString("refresh_token", "").ifBlank { null }, userId, openId, expiresAt)
                pendingVerifier = ""
                pendingState = ""
                _authState.value = AuthState.Authenticated(DerivUser(id = userId, openId = openId))
            }
        }
    }

    /** Refresh the access token when it has expired (if Deriv issues refresh tokens). */
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
            val request = Request.Builder().url(tokenEndpoint).post(form).build()
            try {
                http.newCall(request).execute().use { resp ->
                    val json = JSONObject(resp.body?.string().orEmpty())
                    if (resp.code in 200..299 && json.has("access_token")) {
                        val expiresIn = json.optLong("expires_in", 0L)
                        val expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000L else 0L
                        tokenStore.saveTokens(
                            json.getString("access_token"),
                            json.optString("refresh_token", rt),
                            tokenStore.userId(),
                            tokenStore.openId(),
                            expiresAt
                        )
                        _authState.value = AuthState.Authenticated(DerivUser(id = tokenStore.userId().orEmpty(), openId = tokenStore.openId()))
                    } else {
                        tokenStore.clear()
                        _authState.value = AuthState.SessionExpired
                    }
                }
            } catch (e: Exception) {
                _authState.value = AuthState.AuthenticationError("Refresh failed: ${e.message}")
            }
        }
    }

    /** Clear the local session (sensitive in-memory + encrypted tokens). */
    fun logout() {
        tokenStore.clear()
        pendingVerifier = ""
        pendingState = ""
        _authState.value = AuthState.Unauthenticated
    }

    /** Re-derive the auth state from the persisted token store (call on app resume). */
    fun rehydrate() {
        _authState.value = initialAuthState()
    }
}
