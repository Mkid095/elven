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
import java.util.concurrent.ConcurrentHashMap
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
 * ## Multiple accounts
 *
 * The app can hold up to [AccountSlotRegistry.MAX_ACCOUNTS] Deriv logins at once. Each is a
 * **slot**: its own encrypted token store, its own identity, and — because the data layer is
 * scoped by that identity — its own trade history.
 *
 * A slot's id is the Deriv **account's stable key**, so signing in to the same Deriv account again
 * lands on the same slot and therefore on the same history. Signing in to a different Deriv
 * account creates a different slot, and its trades are filed separately — which is what makes
 * "the account number identifies the trader" true rather than aspirational.
 *
 * Only one slot is *active* at a time. The active slot's token is the one the Deriv client uses,
 * so switching slots moves the money, the balances and the history together.
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
    private val registry = AccountSlotRegistry(context.applicationContext)
    private val appContext = context.applicationContext

    /**
     * The token stores, one per slot, cached so a slot's encrypted blob is not re-opened on every
     * token read.
     */
    private val stores = ConcurrentHashMap<String, SecureTokenStore>()

    /** The store for a slot. */
    private fun storeFor(slotId: String): SecureTokenStore = stores.getOrPut(slotId) {
        SecureTokenStore(appContext, AccountSlotRegistry.prefsNameFor(slotId))
    }

    /**
     * The active slot's id.
     *
     * Empty when there is no slot at all — signed out. Every token read goes through this, so
     * switching slots switches the credentials in one place.
     */
    private val _activeSlotId = MutableStateFlow(registry.activeSlotId().orEmpty())
    val activeSlotId: StateFlow<String> = _activeSlotId.asStateFlow()

    /** Every signed-in slot, for the account manager. */
    private val _slots = MutableStateFlow(registry.slots())
    val slots: StateFlow<List<DerivSlot>> = _slots.asStateFlow()

    /** The store backing the active slot. */
    private val tokenStore: SecureTokenStore
        get() = storeFor(_activeSlotId.value)

    // Runs before `_authState` is initialised below, which is deliberate: the auth state is
    // derived from the active slot's tokens, so the slot has to be known first.
    init {
        restoreSlots()
    }

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

    /**
     * The scopes to actually request.
     *
     * `openid` is what makes Deriv issue an ID token, and the ID token is what FIDScript needs to
     * establish the cross-device session. But Deriv **rejects the entire authorization request**
     * when the app asks for a scope its registration does not allow — the live error is
     * *"The OAuth 2.0 Client is not allowed to request scope 'openid'"* — so asking for it before
     * the Deriv dashboard allows it would leave the user unable to sign in at all.
     *
     * So the app asks for the full set, and falls back to trading-only if Deriv refuses. The
     * fallback is remembered, so the rejection is paid for once rather than on every sign-in.
     */
    private fun requestedScopes(): String {
        if (registry.scopeFallbackActive()) return scopesWithoutOpenid
        return scopes
    }

    /** [scopes] with `openid` removed. */
    private val scopesWithoutOpenid: String by lazy {
        scopes.split(' ', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "openid" }
            .joinToString(" ")
    }

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

    /**
     * The auth state derived from what is on disk.
     *
     * An **expired** token is reported as [AuthState.SessionExpired] rather than as an
     * authenticated session, and that is not a nicety. Deriv issues no refresh tokens to
     * third-party apps — its own documentation says so — so an access token is good for 30 days
     * and then it is simply dead. Reporting it as still-signed-in means every Deriv call fails
     * with a 401 that the user never sees explained, and the app looks broken rather than
     * expired. Reporting it as expired sends the user to the one screen that can fix it.
     */
    private fun initialAuthState(): AuthState {
        if (!hasToken()) return AuthState.Unauthenticated
        if (tokenStore.isExpired()) return AuthState.SessionExpired
        return AuthState.Authenticated(
            DerivUser(
                id = tokenStore.stableKey()?.takeIf { it.isNotBlank() } ?: tokenStore.userId().orEmpty(),
                openId = tokenStore.openId()
            )
        )
    }

    /**
     * Brings the slot list and the active slot in line with what is on disk.
     *
     * Called at construction, so an app that was signed in before the multi-account change — or
     * whose slots were written by an earlier version — still comes up with its account attached
     * rather than looking signed out.
     */
    private fun restoreSlots() {
        val known = registry.slots()
        if (known.isEmpty()) {
            // An upgrade from the single-account version: the tokens live in the default slot's
            // file, so adopt that slot rather than pretending the user was never signed in.
            val legacy = storeFor(AccountSlotRegistry.DEFAULT_SLOT_ID)
            val legacyKey = legacy.stableKey()?.takeIf { it.isNotBlank() }
                ?: legacy.userId()?.takeIf { it.isNotBlank() }
            if (legacyKey != null) {
                registry.addSlot(
                    DerivSlot(
                        id = legacyKey,
                        label = legacyKey,
                        accountNumber = ""
                    )
                )
                registry.setActive(legacyKey)
            }
        }
        refreshSlots()
        _activeSlotId.value = registry.activeSlotId().orEmpty()
    }

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
     *
     * [slotId] is the slot the login is for. It defaults to the active slot, which is the
     * "sign in again as this account" case; a new slot is created when the user adds an account.
     * The slot is remembered across the browser round trip so the redirect lands on the right
     * credentials even if the user switched accounts in the meantime.
     */
    fun startLogin(activity: Activity, slotId: String = _activeSlotId.value) {
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
        storeFor(slotId).savePendingAuth(verifier, state, now)
        registry.setPendingSlot(slotId)

        _authState.value = AuthState.Authenticating

        val url = Uri.parse(authEndpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", requestedScopes())
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        activity.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Starts a login for a brand-new account.
     *
     * The slot id is provisional — the real one is the Deriv account's stable key, which is only
     * known once the token exchange has run. The provisional id carries the pending login across
     * the browser round trip; the slot is then rewritten under its real id.
     *
     * Refuses when the account cap has been reached, so the user is told rather than silently
     * getting a fourth account that cannot be signed out of properly.
     */
    fun startAddAccount(activity: Activity): Boolean {
        if (registry.slots().size >= AccountSlotRegistry.MAX_ACCOUNTS) return false
        startLogin(activity, slotId = provisionalSlotId())
        return true
    }

    /** A temporary slot id for a login that has not yet learned which account it belongs to. */
    private fun provisionalSlotId(): String = "pending_${System.currentTimeMillis()}"

    /**
     * Makes [slotId] the active account.
     *
     * Switching moves the credentials, so the balances, the trading socket and the history all
     * follow. Returns false when the slot is unknown — the caller must not leave the app pointing
     * at an account that does not exist.
     */
    fun switchTo(slotId: String): Boolean {
        if (!registry.setActive(slotId)) return false
        _activeSlotId.value = slotId
        _authState.value = initialAuthState()
        refreshSlots()
        return true
    }

    /**
     * Signs out of one account, leaving the others signed in.
     *
     * The account's tokens are erased and its slot is forgotten. The trade history stays on the
     * device: it belongs to the account, and the user may sign back in and expect to find it.
     */
    fun signOutSlot(slotId: String) {
        storeFor(slotId).clear()
        registry.removeSlot(slotId)
        refreshSlots()
        // If the signed-out account was active, fall back to whichever is left.
        if (_activeSlotId.value == slotId) {
            val next = registry.activeSlotId().orEmpty()
            _activeSlotId.value = next
            _authState.value = initialAuthState()
        }
    }

    /** Records the Deriv account number for a slot, so the account list can show it. */
    fun noteAccountNumber(slotId: String, accountNumber: String) {
        val slot = registry.slots().firstOrNull { it.id == slotId } ?: return
        if (slot.accountNumber == accountNumber) return
        registry.updateSlot(slot.copy(accountNumber = accountNumber))
        refreshSlots()
    }

    /** Re-reads the slot list from the registry and republishes it. */
    fun refreshSlots() {
        _slots.value = registry.slots()
    }

    /**
     * Signs out of **every** account on this device: every slot's encrypted tokens, the slot
     * metadata, and the app's auth state.
     *
     * [logout] only signs out the active slot, because the normal "switch account" flow keeps
     * the others. The full sign-out is different: the person has left their TickEdge account,
     * and leaving credentials on the device would let the next person sign in and trade —
     * with the outgoing user's identities behind them — so everything goes.
     */
    fun logoutAll() {
        registry.slots().forEach { slot -> storeFor(slot.id).clear() }
        tokenStore.clearPendingAuth()
        registry.clearAll()
        _activeSlotId.value = ""
        clearPendingAuth()
        registry.clearScopeFallback()
        refreshSlots()
        _authState.value = AuthState.Unauthenticated
    }

    /**
     * The slot a login is currently in progress for, or null.
     *
     * Public because a failed authorization has to be retried against the same account — the
     * redirect URL carries no account information, so the slot is the only thing that says which
     * login the browser round trip belonged to.
     */
    fun pendingSlotId(): String? = registry.pendingSlotId()


    /**
     * Handle the OAuth redirect. Validates state, then exchanges the code for tokens.
     *
     * This is a **suspend** function. The caller MUST await the result before assuming
     * the callback has been processed. The token exchange is complete when this returns.
     *
     * @return true if session establishment has begun (caller can wait); false on
     *         immediate error (no session was started).
     */
    suspend fun handleCallback(data: Uri): Boolean =
        handleCallbackDetailed(data) == CallbackOutcome.AUTHENTICATED

    /**
     * What the redirect callback produced.
     *
     * [SCOPE_REJECTED] is the one that is not simply "no": it means Deriv refused the scopes the
     * app asked for, and the caller should try again with fewer. It is a separate outcome rather
     * than a plain failure because the remedy is different — retry, not give up.
     */
    enum class CallbackOutcome {
        /** Signed in. */
        AUTHENTICATED,

        /** The callback could not be used. The reason is in the auth state. */
        FAILED,

        /** Deriv does not allow the scopes that were requested. */
        SCOPE_REJECTED
    }

    /**
     * Handles the redirect and reports what happened.
     *
     * See [handleCallback] for the suspend contract — this must be awaited before the redirect
     * activity finishes.
     */
    suspend fun handleCallbackDetailed(data: Uri): CallbackOutcome {
        if (!isOAuthConfigured()) {
            return if (hasToken()) CallbackOutcome.AUTHENTICATED else CallbackOutcome.FAILED
        }

        // The slot the login was started for. Read before the error branch clears it, because a
        // scope rejection has to be retried against the same account.
        val slotId = registry.pendingSlotId() ?: _activeSlotId.value

        // Check for OAuth error from Deriv
        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val desc = data.getQueryParameter("error_description") ?: error
            clearPendingAuth()
            // A scope rejection is recoverable: the app can ask for less and try again. Anything
            // else is a real failure and is reported to the user.
            if (error.equals("invalid_scope", ignoreCase = true)) {
                Log.w(TAG, "Deriv rejected the requested scopes: $desc")
                registry.setScopeFallbackUsed()
                // The retry has to land on the same account, so the slot is put back.
                registry.setPendingSlot(slotId)
                return CallbackOutcome.SCOPE_REJECTED
            }
            fail(desc)
            return CallbackOutcome.FAILED
        }

        val code = data.getQueryParameter("code")
        val returnedState = data.getQueryParameter("state")

        val slotStore = storeFor(slotId)

        // Recover persisted pending auth (survives process death)
        val pending = slotStore.getPendingAuth()
        when {
            pending == null -> {
                fail("No pending authorization found (session expired or already used)")
                return CallbackOutcome.FAILED
            }
            code.isNullOrBlank() -> {
                fail("No authorization code in callback")
                clearPendingAuth()
                return CallbackOutcome.FAILED
            }
            returnedState != pending.state -> {
                fail("Mismatched OAuth state — possible CSRF attack")
                clearPendingAuth()
                return CallbackOutcome.FAILED
            }
        }

        // Exchange code for tokens (suspend — must complete before activity finishes)
        val success = exchangeCode(code, pending.verifier, slotId, slotStore)
        if (!success) {
            return CallbackOutcome.FAILED
        }

        // Auth is now established; clear pending state
        clearPendingAuth()
        return CallbackOutcome.AUTHENTICATED
    }

    /**
     * Exchange the authorization code for tokens using the PKCE verifier.
     * Updates [authState] to Authenticated on success, or AuthenticationError on failure.
     *
     * @return true if tokens were successfully stored and auth state is now Authenticated
     */
    private suspend fun exchangeCode(
        code: String,
        verifier: String,
        provisionalSlotId: String,
        provisionalStore: SecureTokenStore
    ): Boolean {
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
                    //
                    // The tokens go into the store for the account's *real* identity, not the
                    // provisional slot the login started in. The stable key is only known now, and
                    // filing the slot under it is what makes signing in to the same Deriv account
                    // again land on the same slot — and therefore on the same trade history.
                    val stableKey = openId?.takeIf { it.isNotBlank() } ?: userId
                    val realStore = storeFor(stableKey)
                    try {
                        realStore.saveTokens(
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

                    // Register the slot under the account's real identity. The provisional slot is
                    // dropped: it carried no history, and leaving it behind would show the user a
                    // blank account they cannot tell apart from the real one.
                    registry.removeSlot(provisionalSlotId)
                    registry.addSlot(
                        DerivSlot(
                            id = stableKey,
                            label = name ?: email.orEmpty(),
                            accountNumber = ""
                        )
                    )
                    registry.setActive(stableKey)
                    _activeSlotId.value = stableKey
                    refreshSlots()
                    // The provisional store held only the pending verifier, which is now spent.
                    if (provisionalSlotId != stableKey) provisionalStore.clear()

                    // `id` MUST equal stableKey. Downstream, local writes scope rows by
                    // DerivUser.stableKey while local reads scope them by DerivUser.id. If those
                    // two strings ever differ (Deriv returning both `sub` and `open_id`), trades
                    // are written under one key and queried under another, and history reads back
                    // permanently empty. Keeping them identical removes the whole class of bug.
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
     *
     * **Dead path, kept only so the shape of a refresh is on record.** Deriv does not issue
     * refresh tokens to third-party apps — its OAuth reference states it explicitly, and the
     * token response carries only `access_token`, `expires_in` and `token_type`. The stored
     * refresh token is therefore always blank and this returns immediately.
     *
     * The real remedy for an expired token is to run the authorization flow again, which is what
     * [AuthState.SessionExpired] leads the user to do.
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
     * Clear the local session for the **active account**. Pending auth is also cleared.
     * Note: This does NOT revoke the Deriv OAuth token (Deriv may not support revocation).
     *
     * Only the active account is signed out. The other accounts stay signed in — signing out of
     * one account must not quietly sign the user out of the others.
     *
     * The reduced-scope fallback is forgotten too, so the next sign-in asks for the full scope
     * set again. That is the moment to retry: the user has just been told to enable `openid` on
     * the Deriv dashboard, and signing out and back in is how they would do it.
     */
    fun logout() {
        val slotId = _activeSlotId.value
        clearPendingAuth()
        registry.clearScopeFallback()
        if (slotId.isNotBlank()) {
            signOutSlot(slotId)
            return
        }
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
        // The pending verifier lives in whichever slot the login was started for, which is not
        // necessarily the active one — the user can switch accounts while the browser is open.
        val pendingSlot = registry.pendingSlotId()
        if (pendingSlot != null) storeFor(pendingSlot).clearPendingAuth()
        else tokenStore.clearPendingAuth()
        registry.clearPendingSlot()
    }
}
