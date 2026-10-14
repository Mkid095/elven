package com.tickedge.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tickedge.auth.AuthState
import com.tickedge.auth.DerivOAuthClient
import com.tickedge.BuildConfig
import com.tickedge.data.deriv.DerivAccount
import com.tickedge.data.deriv.DerivAccountKind
import com.tickedge.data.deriv.DerivClient
import com.tickedge.data.instant.FidscriptSession
import com.tickedge.data.instant.InstantDbClient
import com.tickedge.data.local.AppDatabase
import com.tickedge.data.local.AnalysisEventEntity
import com.tickedge.data.local.LastTradeRow
import com.tickedge.data.local.SessionSummary
import com.tickedge.data.local.SimulationPositionEntity
import com.tickedge.data.local.SimulationSessionEntity
import com.tickedge.data.local.UserProfileEntity
import com.tickedge.data.local.UserSettingsEntity
import com.tickedge.data.model.AccountType
import com.tickedge.data.model.AnalysisMetric
import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.DigitSelectionMode
import com.tickedge.data.model.MarketState
import com.tickedge.data.model.SystemConfig
import com.tickedge.data.model.TradeDurationUnit
import com.tickedge.data.model.TradeSettings
import com.tickedge.data.websocket.DerivWebSocketService
import com.tickedge.domain.DigitAnalysisEngine
import com.tickedge.domain.TradingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Placeholder user id used before anyone is signed in. Also the id the v4->v5 migration
 * attributes legacy rows to, so anonymous history stays visible after the upgrade.
 */
private const val ANONYMOUS_USER_ID = "default_trader"

/** Sentinel for "no session open". Never a real row id, which SQLite starts at 1. */
private const val NO_SESSION = 0L

/**
 * Smallest analysis window, per spec. Note a window below [MIN_ANALYSIS_SAMPLE] can never
 * satisfy a threshold, because the minimum-sample guard rejects the evaluation first — the UI
 * says so rather than silently forbidding the value.
 */
private const val MIN_ANALYSIS_WINDOW = 1

/** Largest analysis window; matches the retained-history cap in [DerivWebSocketService]. */
private const val MAX_ANALYSIS_WINDOW = 1000

/** Window used before any user has saved settings, and after sign-out resets. */
private const val DEFAULT_ANALYSIS_WINDOW = 100

/**
 * Ticks required before a threshold is considered meaningful.
 *
 * Below this the window is too short for a percentage to mean anything, and a single tick swinging
 * the result by 10 points would otherwise fire entries on noise.
 */
private const val MIN_ANALYSIS_SAMPLE = 10

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val webSocketService = DerivWebSocketService(viewModelScope)
    private val oauth = DerivOAuthClient.share(application, viewModelScope)
    private val deriv = DerivClient(BuildConfig.DERIV_APP_ID, { oauth.accessToken() }, viewModelScope)

    /**
     * Stable user identity; derived from the authenticated Deriv user or the legacy default.
     * Uses [DerivUser.stableKey] (open_id if available, otherwise id) to match the
     * user identifier stored in SecureTokenStore.
     */
    private val currentUserId: String
        get() = oauth.currentUser()?.stableKey ?: ANONYMOUS_USER_ID

    // Resolve the user per write, not once at construction — see TradingEngine's userIdProvider.
    /**
     * Id of the session positions are currently being written to.
     *
     * Held in a `var` rather than a StateFlow because [tradingEngine] only needs a synchronous
     * read at write time; the user-facing copy is [activeSessionId].
     */
    @Volatile
    private var currentSessionId: Long = NO_SESSION

    private val tradingEngine = TradingEngine(deriv, db, viewModelScope, { currentUserId }, { currentSessionId })

    /**
     * FIDScript cross-device sync.
     *
     * This is additive: Room remains the single source of truth and every write path is local.
     * The FIDScript session is established from the Deriv ID token and used only to mirror
     * settings and trade history. Failure here never blocks local use — [syncState] surfaces the
     * reason and the app carries on.
     */
    private val fidscriptSession = FidscriptSession(application)
    private val fidscript = InstantDbClient(
        appId = BuildConfig.INSTANT_APP_ID,
        apiBase = BuildConfig.INSTANT_API_BASE,
        session = fidscriptSession
    )

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** FIDScript cross-device sync status. */
    sealed interface SyncState {
        /** No FIDScript session yet (not signed in, or `openid` scope not granted). */
        data object Idle : SyncState
        data object Connecting : SyncState
        data class Ready(val userId: String) : SyncState
        data class Failed(val reason: String) : SyncState
    }

    // Markets & connection
    val marketsState: StateFlow<Map<String, MarketState>> = webSocketService.marketsState
    val overallConnection: StateFlow<com.tickedge.data.model.ConnectionStatus> = webSocketService.overallConnection

    // Real Deriv accounts (balances + ids come from the API, never seeded)
    val accounts = deriv.accounts
    val activeAccountKind = deriv.activeKind
    val authStatus = deriv.authStatus

    // Deriv OAuth / multi-user authentication
    val authState: StateFlow<AuthState> = oauth.authState

    fun launchDerivAuth(activity: android.app.Activity) {
        oauth.startLogin(activity)
    }

    /**
     * Process an OAuth redirect. Suspend so the caller can await the token exchange — the
     * previous non-suspend version returned before its coroutine ran, so the result was always
     * `false`. [DerivOAuthRedirectActivity] drives the exchange directly; this is for any other
     * caller that routes callbacks through the ViewModel.
     */
    suspend fun handleOAuthRedirect(uri: android.net.Uri): Boolean {
        val ok = oauth.handleCallback(uri)
        if (ok) {
            deriv.loadAccounts()
            connectFidscript()
        }
        return ok
    }

    /**
     * Establish the FIDScript session from the Deriv ID token, then push the current local
     * settings so a fresh device has something to read.
     *
     * Safe to call repeatedly. A failure is recorded in [syncState] and otherwise ignored —
     * local Room data and Deriv trading are unaffected.
     */
    fun connectFidscript() {
        viewModelScope.launch {
            if (_syncState.value is SyncState.Connecting) return@launch
            _syncState.value = SyncState.Connecting

            val idToken = oauth.idToken()
            if (idToken.isNullOrBlank()) {
                _syncState.value = SyncState.Idle
                return@launch
            }

            fidscriptSession.signIn(idToken)
                .onSuccess { session ->
                    _syncState.value = SyncState.Ready(session.userId)
                    pushSettingsToCloud()
                }
                .onFailure { error ->
                    _syncState.value =
                        SyncState.Failed(error.message ?: "FIDScript sign-in failed")
                }
        }
    }

    /**
     * Mirror the current settings to FIDScript.
     *
     * Note the `userId` written is FIDScript's `auth.id` — **not** the Deriv app id. Writing the
     * wrong value produces a row the user can never read back, because row permissions compare
     * against the FIDScript identity.
     */
    private suspend fun pushSettingsToCloud() {
        val fidUserId = fidscriptSession.userId() ?: return
        val settings = _tradeSettings.value
        val config = _systemConfig.value

        fidscript.upsertUserSettings(
            userId = fidUserId,
            fields = mapOf(
                "targetDigit" to settings.targetDigit,
                "recoveryDigit" to settings.recoveryDigit,
                "recoveryDirection" to settings.recoveryDirection,
                "stakeAmount" to settings.stakeAmount,
                "takeProfit" to settings.takeProfit,
                "stopLoss" to settings.stopLoss,
                "maxDailyLoss" to config.maxDailyLoss,
                "maxMartingaleSteps" to _maxMartingaleSteps.value,
                "realArmed" to _realArmed.value,
                "requirePerOrderConfirm" to _requirePerOrderConfirm.value,
                "contractType" to "Differs",
                "updatedAt" to System.currentTimeMillis()
            )
        ).onFailure { error ->
            _syncState.value = SyncState.Failed(error.message ?: "Settings sync failed")
        }
    }

    fun derivLogout() {
        tradingEngine.stopTrading()
        viewModelScope.launch { endSession() }
        oauth.logout()
        // Drop the FIDScript session too — its refresh token grants read/write on this user's
        // rows, so it must not outlive the Deriv sign-in on this device.
        fidscriptSession.signOut()
        _syncState.value = SyncState.Idle
        deriv.dispose()

        // Clear the previous user's settings and the session they were trading in.
        //
        // Settings are per-user in the database, and loadPersistedSettings() pulls the next
        // signer's own values — but that load is asynchronous. Without this reset the outgoing
        // user's stake, thresholds and live-trading arming stay visible to whoever signs in next,
        // and if they hit Start before the load lands, their first trade is sized from someone
        // else's configuration.
        _tradeSettings.value = TradeSettings()
        _systemConfig.value = SystemConfig()
        _realArmed.value = false
        _requirePerOrderConfirm.value = true
        _maxMartingaleSteps.value = 3
        currentSessionId = NO_SESSION
        _activeSessionId.value = null
        _activeSession.value = null
        // Back to a neutral window rather than the outgoing user's, for the same reason.
        webSocketService.setWindowSize(DEFAULT_ANALYSIS_WINDOW)
    }

    /** Re-read the persisted OAuth session (called on app resume) and reload accounts if signed in. */
    fun refreshAuthState() {
        oauth.rehydrate()
        if (oauth.authState.value is AuthState.Authenticated) {
            viewModelScope.launch { deriv.loadAccounts() }
        }
    }

    /** The real Deriv account currently in use (derived from accounts + active kind). */
    val activeAccount: StateFlow<DerivAccount?> =
        combine(deriv.accounts, deriv.activeKind) { list, kind ->
            list.firstOrNull { it.kind == kind }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Selected market for detailed analysis
    private val _selectedMarketSymbol = MutableStateFlow("R_25")
    val selectedMarketSymbol: StateFlow<String> = _selectedMarketSymbol.asStateFlow()

    // Account switcher (Demo / Live)
    private val _activeAccountType = MutableStateFlow(AccountType.DEMO)
    val activeAccountType: StateFlow<AccountType> = _activeAccountType.asStateFlow()

    // Stats view toggle
    private val _statsPeriod = MutableStateFlow("Today")
    val statsPeriod: StateFlow<String> = _statsPeriod.asStateFlow()

    // Profile (no seed; the balances used by the UI come from deriv.accounts)
    val userProfile: StateFlow<UserProfileEntity> =
        authState.flatMapLatest { state ->
            val uid = (state as? AuthState.Authenticated)?.user?.id ?: ANONYMOUS_USER_ID
            db.userProfileDao().getProfile(uid).map { it ?: UserProfileEntity(id = uid) }
        // `id` is the primary key and has no default; the anonymous fallback id is the
        // only sensible placeholder before an authenticated profile has loaded.
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserProfileEntity(id = ANONYMOUS_USER_ID))

    // Settings
    private val _tradeSettings = MutableStateFlow(TradeSettings())
    val tradeSettings: StateFlow<TradeSettings> = _tradeSettings.asStateFlow()
    private val _systemConfig = MutableStateFlow(SystemConfig())
    val systemConfig: StateFlow<SystemConfig> = _systemConfig.asStateFlow()

    // Real-execution safety controls
    private val _realArmed = MutableStateFlow(false)
    val realArmed: StateFlow<Boolean> = _realArmed.asStateFlow()
    private val _requirePerOrderConfirm = MutableStateFlow(true)
    val requirePerOrderConfirm: StateFlow<Boolean> = _requirePerOrderConfirm.asStateFlow()
    private val _maxMartingaleSteps = MutableStateFlow(3)
    val maxMartingaleSteps: StateFlow<Int> = _maxMartingaleSteps.asStateFlow()

    // Trading engine state (now real orders)
    val simulatorState = tradingEngine.simState
    val activeTrade = tradingEngine.activeTrade
    val nextTrade = tradingEngine.nextTrade
    val consecutiveLosses = tradingEngine.consecutiveLosses
    val lastFinishedTrade = tradingEngine.lastFinishedTrade
    val recentTradeOutcome = tradingEngine.recentTradeOutcome
    val dailyLoss = tradingEngine.dailyLossAccumulated
    val pendingConfirmation = tradingEngine.pendingConfirmation

    // Room flows (user-scoped via flatMapLatest on authState)
    val pastPositions: StateFlow<List<com.tickedge.data.local.SimulationPositionEntity>> =
        authState.flatMapLatest { state ->
            val uid = (state as? AuthState.Authenticated)?.user?.id ?: ANONYMOUS_USER_ID
            db.simulationDao().getPositionsForUser(uid)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val analysisEvents: StateFlow<List<AnalysisEventEntity>> =
        authState.flatMapLatest { state ->
            val uid = (state as? AuthState.Authenticated)?.user?.id ?: ANONYMOUS_USER_ID
            db.analysisEventDao().getEventsForUser(uid)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---------------------------------------------------------------- trading sessions

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    private val _activeSession = MutableStateFlow<SimulationSessionEntity?>(null)
    val activeSession: StateFlow<SimulationSessionEntity?> = _activeSession.asStateFlow()

    /**
     * Live results for the running session, recomputed from confirmed trades.
     *
     * Derived from the positions table rather than accumulated in memory, so it cannot disagree
     * with the trades that justify it and it survives a process death.
     */
    val sessionSummary: StateFlow<SessionSummary?> = _activeSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else db.simulationDao().getSessionSummary(currentUserId, id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The most recent confirmed trade in the running session, for the "last trade" readout. */
    val lastConfirmedTrade: StateFlow<LastTradeRow?> = _activeSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else db.simulationDao().getLastConfirmedTrade(currentUserId, id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Positions belonging to the running session only, for session-scoped history. */
    val sessionPositions: StateFlow<List<SimulationPositionEntity>> = _activeSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else db.simulationDao().getPositionsForSession(currentUserId, id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Open a session for this run, or reuse the one already in progress.
     *
     * Reuse matters because Start can be pressed again after a stop that did not close cleanly;
     * opening a second session would split one run's trades across two result sets.
     */
    private suspend fun ensureSessionStarted() {
        val existing = db.simulationDao().getLatestSession(currentUserId).first()
        if (existing != null && existing.status == "ACTIVE") {
            currentSessionId = existing.id
            _activeSessionId.value = existing.id
            _activeSession.value = existing
            return
        }
        val account = deriv.activeAccount
        val opened = db.simulationDao().insertSession(
            SimulationSessionEntity(
                userId = currentUserId,
                accountId = account?.id.orEmpty(),
                isReal = account?.kind == DerivAccountKind.REAL,
                strategy = "Differs",
                targetDigit = _tradeSettings.value.targetDigit,
                status = "ACTIVE"
            )
        )
        currentSessionId = opened
        _activeSessionId.value = opened
    }

    /** Close the running session and clear the current id so the next Start opens a fresh one. */
    private suspend fun endSession() {
        val id = currentSessionId
        if (id == NO_SESSION) return
        val summary = db.simulationDao().getSessionSummary(currentUserId, id).first()
        db.simulationDao().closeSession(
            sessionId = id,
            status = "COMPLETED",
            endedAt = System.currentTimeMillis(),
            totalTrades = summary?.completedTrades ?: 0,
            winCount = summary?.winCount ?: 0,
            lossCount = summary?.lossCount ?: 0
        )
        currentSessionId = NO_SESSION
        _activeSessionId.value = null
        _activeSession.value = null
    }

    // Navigation
    private val _currentScreen = MutableStateFlow("home")
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    private val _inspectedDigit = MutableStateFlow<Int?>(2)
    val inspectedDigit: StateFlow<Int?> = _inspectedDigit.asStateFlow()

    init {
        webSocketService.start()
        viewModelScope.launch {
            deriv.loadAccounts()
            ensureProfile()
            loadPersistedSettings()
        }
        observeAuthForProfileAndSync()
        observeAnalysisThresholds()
    }

    /**
     * Guarantee a `profiles` row exists for the signed-in user.
     *
     * Every child entity (settings, analysis events, sessions, positions) declares a foreign key
     * to `profiles.id`, and Room turns foreign keys ON. Without a parent row, an insert throws
     * SQLiteConstraintException inside a coroutine — which is swallowed, so writes appear to
     * succeed and the data is simply lost. The migration only seeds the legacy
     * `default_trader` profile, so this must run for every authenticated user.
     */
    private suspend fun ensureProfile() {
        val userId = currentUserId
        runCatching {
            db.userProfileDao().insertOrUpdateProfile(
                UserProfileEntity(
                    id = userId,
                    displayName = oauth.currentUser()?.displayName.orEmpty()
                )
            )
        }.onFailure { error ->
            android.util.Log.w("MainViewModel", "Could not ensure profile for $userId", error)
        }
    }

    /**
     * When the signed-in identity changes, make sure its profile row exists and open the
     * FIDScript session. Covers cold start, fresh login, and account switching.
     */
    private fun observeAuthForProfileAndSync() {
        viewModelScope.launch {
            authState.collect { state ->
                if (state is AuthState.Authenticated) {
                    ensureProfile()
                    loadPersistedSettings()
                    connectFidscript()
                }
            }
        }
    }

    private suspend fun loadPersistedSettings() {
        val e = db.userSettingsDao().getSettings(currentUserId).first()
        e?.let {
            _tradeSettings.value = toTradeSettings(it)
            _systemConfig.value = toSystemConfig(it)
            _realArmed.value = it.realArmed
            _requirePerOrderConfirm.value = it.requirePerOrderConfirm
            _maxMartingaleSteps.value = it.maxMartingaleSteps
            // The window lived only in the service before, so it reset to 30 on every launch.
            // Applying it here makes the persisted setting the one actually in force.
            webSocketService.setWindowSize(_tradeSettings.value.analysisWindowSize)
        }
    }

    private fun observeAnalysisThresholds() {
        viewModelScope.launch {
            marketsState.collect { markets ->
                val currentSettings = _tradeSettings.value
                markets.values.forEach { state ->
                    val digitFreq = state.digitFrequencies.firstOrNull { it.digit == currentSettings.targetDigit }
                    if (digitFreq != null && state.sampleSize >= MIN_ANALYSIS_SAMPLE) {
                        // Same metric resolution the trading engine uses, so a logged event and a
                        // placed trade can never disagree about what was measured.
                        val observedPercent = DigitAnalysisEngine.observedPercentFor(
                            frequencies = state.digitFrequencies,
                            targetDigit = currentSettings.targetDigit,
                            metric = currentSettings.analysisMetric
                        )
                        val met = DigitAnalysisEngine.evaluateCondition(
                            observedPercent = observedPercent,
                            thresholdPercent = currentSettings.thresholdPercent,
                            operator = currentSettings.comparisonOperator,
                            sampleSize = state.sampleSize,
                            minimumSampleSize = MIN_ANALYSIS_SAMPLE
                        )
                        if (met) {
                            recordAnalysisEvent(
                                symbol = state.symbol,
                                targetDigit = currentSettings.targetDigit,
                                windowSize = state.configuredWindowSize,
                                sampleSize = state.sampleSize,
                                occurrenceCount = digitFreq.count,
                                observedPercent = observedPercent,
                                metric = currentSettings.analysisMetric,
                                operator = currentSettings.comparisonOperator,
                                thresholdPercent = currentSettings.thresholdPercent
                            )
                        }
                    }
                }
            }
        }
    }

    private fun recordAnalysisEvent(
        symbol: String,
        targetDigit: Int,
        windowSize: Int,
        sampleSize: Int,
        occurrenceCount: Int,
        observedPercent: Double,
        metric: AnalysisMetric,
        operator: ComparisonOperator,
        thresholdPercent: Double
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            db.analysisEventDao().insertEvent(
                AnalysisEventEntity(
                    userId = currentUserId,
                    marketSymbol = symbol,
                    targetDigit = targetDigit,
                    windowSize = windowSize,
                    sampleSize = sampleSize,
                    occurrenceCount = occurrenceCount,
                    observedPercent = observedPercent,
                    analysisMetric = metric.symbol,
                    comparisonOperator = operator.symbol,
                    thresholdPercent = thresholdPercent,
                    conditionMet = true,
                    eventTimestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun activeAccount(): DerivAccount? = deriv.activeAccount

    fun switchAccountType(type: AccountType) {
        _activeAccountType.value = type
        viewModelScope.launch {
            deriv.setAccount(type.toDerivKind())
            db.userProfileDao().updateActiveAccount(currentUserId, type.name)
        }
    }

    fun switchStatsPeriod(period: String) { _statsPeriod.value = period }
    fun selectMarket(symbol: String) { _selectedMarketSymbol.value = symbol }
    fun setInspectedDigit(digit: Int?) { _inspectedDigit.value = digit }
    /**
     * Change the analysis window. Mirrored into [tradeSettings] so the Analysis screen's +/- control
     * and the Trade Settings field cannot drift apart, and persisted so it survives a restart.
     */
    fun setWindowSize(size: Int) {
        val bounded = size.coerceIn(MIN_ANALYSIS_WINDOW, MAX_ANALYSIS_WINDOW)
        webSocketService.setWindowSize(bounded)
        if (_tradeSettings.value.analysisWindowSize != bounded) {
            _tradeSettings.value = _tradeSettings.value.copy(analysisWindowSize = bounded)
            persistSettings()
        }
    }

    fun updateTradeSettings(settings: TradeSettings) {
        _tradeSettings.value = settings
        persistSettings()
    }

    fun updateSystemConfig(config: SystemConfig) {
        _systemConfig.value = config
        persistSettings()
    }

    fun setRealArmed(v: Boolean) {
        _realArmed.value = v
        persistSettings()
    }

    fun setRequirePerOrderConfirm(v: Boolean) {
        _requirePerOrderConfirm.value = v
        persistSettings()
    }

    fun setMaxMartingaleSteps(v: Int) {
        _maxMartingaleSteps.value = v.coerceIn(0, 5)
        persistSettings()
    }

    private fun persistSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val s = _tradeSettings.value
            val c = _systemConfig.value
            db.userSettingsDao().saveSettings(
                UserSettingsEntity(
                    id = 1L,
                    userId = currentUserId,
                    windowSize = s.analysisWindowSize,
                    selectedDigit = s.targetDigit,
                    recoveryDigit = s.recoveryDigit,
                    recoveryDirection = s.recoveryDirection,
                    comparisonOperator = s.comparisonOperator.symbol,
                    thresholdPercent = s.thresholdPercent,
                    analysisMetric = s.analysisMetric.symbol,
                    digitSelectionMode = s.digitSelectionMode.symbol,
                    minimumSampleSize = MIN_ANALYSIS_SAMPLE,
                    stakeAmount = s.stakeAmount,
                    takeProfit = s.takeProfit,
                    stopLoss = s.stopLoss,
                    durationUnit = s.durationUnit.label,
                    durationValue = s.durationValue,
                    martingaleEnabled = s.martingaleEnabled,
                    tradeType = "Differs",
                    randomMarkets = c.randomMarkets,
                    maxTradesPerMarket = c.maxTradesPerMarket,
                    maxDailyLoss = c.maxDailyLoss,
                    stopTradingOnLimit = c.stopTradingOnLimit,
                    realArmed = _realArmed.value,
                    requirePerOrderConfirm = _requirePerOrderConfirm.value,
                    maxMartingaleSteps = _maxMartingaleSteps.value
                )
            )
        }
    }

    fun navigateTo(screen: String) { _currentScreen.value = screen }

    fun startTrading() {
        // The engine persists positions itself, so both its foreign-key parent and the session
        // must exist before the first trade. The engine start is therefore INSIDE the coroutine:
        // launching the setup separately and starting the engine synchronously raced, so the first
        // trade could be written before the session row existed and land under NO_SESSION, making it
        // invisible to every session-scoped query.
        viewModelScope.launch {
            ensureProfile()
            ensureSessionStarted()
            tradingEngine.startTrading(
                settings = _tradeSettings.value,
                config = _systemConfig.value,
                getMarketState = { symbol -> marketsState.value[symbol] },
                safety = TradingEngine.TradingSafety(
                    activeKind = { _activeAccountType.value.toDerivKind() },
                    isRealArmed = { _realArmed.value },
                    requirePerOrderConfirm = { _requirePerOrderConfirm.value },
                    maxMartingaleSteps = { _maxMartingaleSteps.value }
                )
            )
        }
    }

    fun stopTrading() {
        tradingEngine.stopTrading()
        viewModelScope.launch { endSession() }
    }
    fun resolveConfirmation(approved: Boolean) { tradingEngine.resolvePendingConfirmation(approved) }
    fun dismissOutcomeBanner() { tradingEngine.dismissOutcomeBanner() }

    /**
     * Delete trade and analysis history for the signed-in user on the **currently selected
     * account** only.
     *
     * Positions are scoped to the account because demo and real results are not interchangeable —
     * a clear that wiped both would destroy the real account's record along with the demo one.
     * Analysis events carry no account column (they are per-market observations), so they are
     * cleared for the user as a whole and the confirmation dialog says so.
     */
    fun clearHistory() {
        val accountId = deriv.activeAccount?.id.orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            if (accountId.isBlank()) {
                // No account resolved: refuse rather than falling back to a user-wide delete,
                // which would remove more than the user was told would be removed.
                android.util.Log.w("MainViewModel", "clearHistory ignored: no active account id")
            } else {
                db.simulationDao().clearPositionsForUserAndAccount(currentUserId, accountId)
            }
            db.analysisEventDao().clearEventsForUser(currentUserId)
            tradingEngine.resetDailyLoss()
        }
    }

    fun refreshAccounts() {
        viewModelScope.launch { deriv.refreshBalances() }
    }

    override fun onCleared() {
        super.onCleared()
        webSocketService.disconnect()
        tradingEngine.stopTrading()
        deriv.dispose()
    }

    private fun AccountType.toDerivKind(): DerivAccountKind =
        if (this == AccountType.DEMO) DerivAccountKind.DEMO else DerivAccountKind.REAL

    private fun toTradeSettings(e: UserSettingsEntity): TradeSettings = TradeSettings(
        targetDigit = e.selectedDigit,
        recoveryDigit = e.recoveryDigit,
        recoveryDirection = e.recoveryDirection,
        comparisonOperator = ComparisonOperator.values()
            .firstOrNull { it.symbol == e.comparisonOperator } ?: ComparisonOperator.GREATER_THAN,
        thresholdPercent = e.thresholdPercent,
        analysisMetric = AnalysisMetric.values()
            .firstOrNull { it.symbol == e.analysisMetric } ?: AnalysisMetric.DIFFERS_PERCENT,
        digitSelectionMode = DigitSelectionMode.values()
            .firstOrNull { it.symbol == e.digitSelectionMode } ?: DigitSelectionMode.FIXED,
        analysisWindowSize = e.windowSize.coerceIn(MIN_ANALYSIS_WINDOW, MAX_ANALYSIS_WINDOW),
        stakeAmount = e.stakeAmount,
        takeProfit = e.takeProfit,
        stopLoss = e.stopLoss,
        durationUnit = TradeDurationUnit.values()
            .firstOrNull { it.label.equals(e.durationUnit, ignoreCase = true) } ?: TradeDurationUnit.TICKS,
        durationValue = e.durationValue,
        martingaleEnabled = e.martingaleEnabled
    )

    private fun toSystemConfig(e: UserSettingsEntity): SystemConfig = SystemConfig(
        randomMarkets = e.randomMarkets,
        maxTradesPerMarket = e.maxTradesPerMarket,
        maxDailyLoss = e.maxDailyLoss,
        stopTradingOnLimit = e.stopTradingOnLimit
    )
}
