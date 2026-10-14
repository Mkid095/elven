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
import com.tickedge.data.local.UserProfileEntity
import com.tickedge.data.local.UserSettingsEntity
import com.tickedge.data.model.AccountType
import com.tickedge.data.model.ComparisonOperator
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Placeholder user id used before anyone is signed in. Also the id the v4->v5 migration
 * attributes legacy rows to, so anonymous history stays visible after the upgrade.
 */
private const val ANONYMOUS_USER_ID = "default_trader"

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
    private val tradingEngine = TradingEngine(deriv, db, viewModelScope) { currentUserId }

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
        oauth.logout()
        // Drop the FIDScript session too — its refresh token grants read/write on this user's
        // rows, so it must not outlive the Deriv sign-in on this device.
        fidscriptSession.signOut()
        _syncState.value = SyncState.Idle
        deriv.dispose()
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
        }
    }

    private fun observeAnalysisThresholds() {
        viewModelScope.launch {
            marketsState.collect { markets ->
                val currentSettings = _tradeSettings.value
                markets.values.forEach { state ->
                    val digitFreq = state.digitFrequencies.firstOrNull { it.digit == currentSettings.targetDigit }
                    if (digitFreq != null && state.sampleSize >= 10) {
                        val met = DigitAnalysisEngine.evaluateCondition(
                            observedPercent = digitFreq.percentage,
                            thresholdPercent = currentSettings.thresholdPercent,
                            operator = currentSettings.comparisonOperator,
                            sampleSize = state.sampleSize,
                            minimumSampleSize = 10
                        )
                        if (met) {
                            recordAnalysisEvent(
                                symbol = state.symbol,
                                targetDigit = currentSettings.targetDigit,
                                windowSize = state.configuredWindowSize,
                                sampleSize = state.sampleSize,
                                occurrenceCount = digitFreq.count,
                                observedPercent = digitFreq.percentage,
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
    fun setWindowSize(size: Int) { webSocketService.setWindowSize(size) }

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
                    windowSize = 30,
                    selectedDigit = s.targetDigit,
                    recoveryDigit = s.recoveryDigit,
                    recoveryDirection = s.recoveryDirection,
                    comparisonOperator = s.comparisonOperator.symbol,
                    thresholdPercent = s.thresholdPercent,
                    minimumSampleSize = 10,
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
        // The engine persists positions itself, so its foreign-key parent must exist first.
        viewModelScope.launch { ensureProfile() }
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

    fun stopTrading() { tradingEngine.stopTrading() }
    fun resolveConfirmation(approved: Boolean) { tradingEngine.resolvePendingConfirmation(approved) }
    fun dismissOutcomeBanner() { tradingEngine.dismissOutcomeBanner() }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            db.simulationDao().clearPositionsForUser(currentUserId)
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
