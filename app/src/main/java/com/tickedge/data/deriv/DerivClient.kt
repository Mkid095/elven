package com.tickedge.data.deriv

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Facade over the real Deriv V1 options API: owns the account list, the active account kind, and a
 * connected per-account trading socket for that account.
 *
 * Demo is the default active account. A socket is held open per account so both balances stream
 * live; the selected account's socket is the one orders are placed on.
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

    /**
     * One open socket per account, demo and real alike.
     *
     * Each is bound to a single account's OTP URL and carries that account's own balance
     * subscription, so both balances can be live at once. The selected account's socket is also the
     * one used to trade — they are the same object, so there is never a second connection for it.
     */
    private val sockets = ConcurrentHashMap<String, DerivTradingClient>()

    /** Balance collectors, one per socket; cancelled together when that socket is closed. */
    private val balanceCollectors = ConcurrentHashMap<String, Job>()

    /** Account ids whose balance is arriving by push rather than by poll. */
    private val _liveAccounts = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Whether the selected account's balance is arriving live, or is only as fresh as the last poll.
     *
     * Derived rather than hand-maintained, because a flag that has to be set correctly on every
     * connect, disconnect and switch is exactly the kind of flag that goes stale — and a stale
     * "Live" label is a lie told to the user about their own money. This recomputes from the facts
     * every time either changes.
     */
    val balanceIsLive: StateFlow<Boolean> =
        combine(_liveAccounts, _activeKind, _accounts) { live, kind, accounts ->
            accounts.firstOrNull { it.kind == kind }?.id in live
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /** True while a manual refresh is in flight, so the UI can spin rather than look unresponsive. */
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    enum class AuthStatus { NONE, LOADING, OK, ERROR }

    val activeAccount: DerivAccount?
        get() = _accounts.value.firstOrNull { it.kind == _activeKind.value }

    /** Load the real accounts (and their balances). Call on app start and on demand. */
    suspend fun loadAccounts() {
        _authStatus.value = AuthStatus.LOADING
        try {
            _accounts.value = rest.fetchAccounts()
            _authStatus.value = AuthStatus.OK
            // Open a socket per account so demo and real are both live from the outset, not just
            // whichever one happens to be selected.
            openAllSockets()
        } catch (e: Exception) {
            _authStatus.value = AuthStatus.ERROR
        }
    }

    /**
     * Re-fetches balances from REST, without clobbering a live push, and drops liveness for any
     * socket that has died.
     *
     * The periodic safety net. Both accounts hold a socket, but a socket can die — backgrounded,
     * network change, reconnect — and nothing else notices: the account would sit in [_liveAccounts]
     * forever, reporting "Live" for a stream that had stopped.
     *
     * A connected socket is left alone for the ordering reason given in [syncNow]; an account whose
     * socket is gone reverts to being refreshed here, which is what makes a drop self-heal.
     */
    suspend fun refreshBalances() {
        // Prune liveness first, so a dead socket cannot keep claiming to be live even if the
        // network call below fails.
        val stillLive = _liveAccounts.value.filterTo(mutableSetOf()) { accountId ->
            sockets[accountId]?.isConnected() == true
        }
        if (stillLive != _liveAccounts.value) _liveAccounts.value = stillLive

        try {
            val fresh = rest.fetchAccounts()
            if (fresh.isEmpty()) return
            val current = _accounts.value
            // Nothing loaded yet (or the first load failed): take the fetch wholesale rather than
            // mapping over an empty list and staying empty forever.
            if (current.isEmpty()) {
                _accounts.value = fresh
                return
            }
            _accounts.value = current.map { account ->
                if (account.id in stillLive) account
                else fresh.firstOrNull { it.id == account.id } ?: account
            }
        } catch (_: Exception) {
            // keep the last known balances on a transient failure
        }
    }

    /**
     * Manual sync: re-open any dropped socket, then re-read every balance.
     *
     * What the sync button does. Re-opening the sockets first is the point: tapping it repairs a
     * connection that has gone away, rather than just re-reading a balance already known to be stale
     * for exactly that reason. The socket's own figure is then preferred over REST for any account
     * that is live, because the push is ordered and the REST snapshot is not.
     */
    suspend fun syncNow() {
        _isSyncing.value = true
        try {
            _accounts.value.forEach { account ->
                if (sockets[account.id]?.isConnected() != true) ensureSocket(account)
            }
            publishTradingPointer()
            // The REST read cannot see a live account's balance, so pull each socket's own figure
            // rather than relying on the network round trip alone.
            sockets.forEach { (accountId, client) ->
                client.balance.value?.let { pushed ->
                    _accounts.value = _accounts.value.map {
                        if (it.id == accountId) it.copy(balance = pushed) else it
                    }
                }
            }
            refreshBalances()
        } finally {
            _isSyncing.value = false
        }
    }

    /** Switch active account kind. Both accounts keep their sockets, so no reconnect is needed. */
    suspend fun setAccount(kind: DerivAccountKind) {
        if (kind == _activeKind.value) return
        _activeKind.value = kind
        // The socket for the newly selected account already exists — both are kept open — so this is
        // a pointer move, not a reconnect. Rebuilding it would drop a working balance stream for no
        // reason. If that socket is *not* connected, ensureTrading() rebuilds it on the next order.
        publishTradingPointer()
    }

    /**
     * Returns a connected trading socket for the active account, or null.
     *
     * Previously this returned whatever the connect attempt produced without checking it opened, so
     * the caller received a client whose socket had not completed its handshake — and the first
     * request failed with an exception the caller did not expect. The result is now verified, and
     * the reason is logged so a failure is diagnosable rather than silent.
     */
    suspend fun ensureTrading(): DerivTradingClient? {
        val account = activeAccount ?: return null
        val client = ensureSocket(account)
        publishTradingPointer()
        if (client == null || !client.isConnected()) {
            Log.w(
                "DerivClient",
                "Trading socket unavailable (account=${account.id}, connected=${client?.isConnected()})"
            )
            return null
        }
        return client
    }

    /**
     * The socket for [account], opening one if needed.
     *
     * **Every** account gets one, not only the selected one. Holding both open is what makes both
     * balances real-time: the subscription is per-socket, so an account with no socket can only ever
     * be as fresh as the last poll. The selected account's socket is the same object used for
     * trading, so switching accounts costs nothing and never interrupts a balance stream.
     */
    private suspend fun ensureSocket(account: DerivAccount): DerivTradingClient? {
        sockets[account.id]?.let { existing ->
            if (existing.isConnected()) return existing
            closeSocket(account.id)
            // Its stream has stopped, so it no longer gets to claim to be live.
            _liveAccounts.value = _liveAccounts.value - account.id
        }
        return try {
            val url = withContext(Dispatchers.IO) { rest.fetchTradingWsUrl(account.id) }
            val client = DerivTradingClient(url)
            client.connect()
            sockets[account.id] = client
            observeLiveBalance(client, account.id)
            client
        } catch (e: Exception) {
            Log.w("DerivClient", "socket connect failed for ${account.id}: ${e.message}")
            null
        }
    }

    private fun closeSocket(accountId: String) {
        sockets.remove(accountId)?.close()
    }

    /**
     * Mirrors a socket's pushed balance into the account list the UI renders.
     *
     * The trading socket pushes a balance on every change, so the displayed figure tracks the
     * account without polling. This replaces a design where the balance only changed at app start
     * and after the engine explicitly asked for a refresh — which meant a stake leaving the
     * account was invisible until the trade finished.
     *
     * Keyed by [accountId] so a push from one account's socket can never overwrite another's.
     */
    private fun observeLiveBalance(client: DerivTradingClient, accountId: String) {
        balanceCollectors.remove(accountId)?.cancel()
        balanceCollectors[accountId] = scope.launch {
            client.balance.collect { balance ->
                if (balance == null) return@collect
                // A socket that has delivered a figure is the authority for its own account, so a
                // REST snapshot must not overwrite it (see refreshBalances).
                _liveAccounts.value = _liveAccounts.value + accountId
                _accounts.value = _accounts.value.map { account ->
                    if (account.id == accountId) account.copy(balance = balance) else account
                }
            }
        }
    }

    /** Opens a socket for every account so demo and real are both live, not just the selected one. */
    suspend fun openAllSockets() {
        _accounts.value.forEach { account ->
            if (sockets[account.id]?.isConnected() != true) ensureSocket(account)
        }
        publishTradingPointer()
    }

    /** Keeps `_trading` pointing at the selected account's socket. */
    private fun publishTradingPointer() {
        val account = activeAccount
        _trading.value = account?.let { sockets[it.id] }
    }

    fun dispose() {
        balanceCollectors.values.forEach { it.cancel() }
        balanceCollectors.clear()
        sockets.values.forEach { it.close() }
        sockets.clear()
        _liveAccounts.value = emptySet()
        _isSyncing.value = false
        _trading.value = null
    }

    /**
     * Forgets the accounts and closes their sockets.
     *
     * Called when the signed-in Deriv account changes. The account list belongs to the login that
     * produced it, so keeping the old one would show the previous account's balances under the
     * new account's name — and leave a trading socket open on an account the user has left.
     */
    suspend fun resetForAccountSwitch() {
        dispose()
        _accounts.value = emptyList()
        _activeKind.value = DerivAccountKind.DEMO
    }
}
