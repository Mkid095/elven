package com.tickedge.data.deriv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Facade over the real Deriv V1 options API: owns the account list, the active account kind, and a
 * connected per-account trading socket for that account.
 *
 * Demo is the default active account; switching kind reconnects the trading socket.
 */
class DerivClient(
    appId: String,
    private val tokenProvider: () -> String?,
    private val scope: CoroutineScope
) {
    private val rest = DerivRestClient(appId, tokenProvider)

    private val _accounts = MutableStateFlow<List<DerivAccount>>(emptyList())
    val accounts: StateFlow<List<DerivAccount>> = _accounts.asStateFlow()

    private val _activeKind = MutableStateFlow(DerivAccountKind.DEMO)
    val activeKind: StateFlow<DerivAccountKind> = _activeKind.asStateFlow()

    private val _authStatus = MutableStateFlow(AuthStatus.NONE)
    val authStatus: StateFlow<AuthStatus> = _authStatus.asStateFlow()

    private val _trading = MutableStateFlow<DerivTradingClient?>(null)
    val trading: StateFlow<DerivTradingClient?> = _trading.asStateFlow()

    enum class AuthStatus { NONE, LOADING, OK, ERROR }

    val activeAccount: DerivAccount?
        get() = _accounts.value.firstOrNull { it.kind == _activeKind.value }

    /** Load the real accounts (and their balances). Call on app start and on demand. */
    suspend fun loadAccounts() {
        _authStatus.value = AuthStatus.LOADING
        try {
            _accounts.value = rest.fetchAccounts()
            _authStatus.value = AuthStatus.OK
        } catch (e: Exception) {
            _authStatus.value = AuthStatus.ERROR
        }
    }

    /** Refresh balances only (cheap re-fetch after a settlement). */
    suspend fun refreshBalances() {
        try {
            _accounts.value = rest.fetchAccounts()
        } catch (_: Exception) {
            // keep the last known balances on a transient failure
        }
    }

    /** Switch active account kind and reconnect the trading socket for it. */
    suspend fun setAccount(kind: DerivAccountKind) {
        if (kind == _activeKind.value) return
        _activeKind.value = kind
        reconnect()
    }

    /** Ensure a trading socket for the active account is connected; returns it (or null). */
    /**
     * Returns a trading client that is genuinely connected, or null.
     *
     * Previously this returned whatever `reconnect()` produced without checking it had opened, so
     * the caller received a client whose socket had not completed its handshake — and the first
     * request failed with an exception the caller did not expect. The result is now verified, and
     * the reason is logged so a failure is diagnosable rather than silent.
     */
    suspend fun ensureTrading(): DerivTradingClient? {
        _trading.value?.let { if (it.isConnected()) return it }
        reconnect()
        val client = _trading.value
        if (client == null || !client.isConnected()) {
            android.util.Log.w(
                "DerivClient",
                "Trading socket unavailable (account=${activeAccount?.id}, " +
                    "connected=${client?.isConnected()})"
            )
            return null
        }
        return client
    }

    private suspend fun reconnect() {
        _trading.value?.close()
        _trading.value = null
        val account = activeAccount ?: return
        try {
            val url = withContext(Dispatchers.IO) { rest.fetchTradingWsUrl(account.id) }
            val t = DerivTradingClient(url)
            t.connect()
            _trading.value = t
        } catch (e: Exception) {
            _trading.value = null
        }
    }

    fun dispose() {
        _trading.value?.close()
        _trading.value = null
    }
}
