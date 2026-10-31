package com.tickedge.ui.viewmodel

import android.app.Application
import android.app.Activity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tickedge.auth.AccountSlotRegistry
import com.tickedge.auth.AuthState
import com.tickedge.auth.DerivOAuthClient
import com.tickedge.auth.DerivSlot
import com.tickedge.BuildConfig
import com.tickedge.data.deriv.DerivAccount
import com.tickedge.data.deriv.DerivAccountKind
import com.tickedge.data.deriv.DerivClient
import com.tickedge.data.instant.FidscriptAttrs
import com.tickedge.data.instant.FidscriptSession
import com.tickedge.data.instant.InstantDbClient
import com.tickedge.data.local.AppDatabase
import com.tickedge.data.local.AnalysisEventEntity
import com.tickedge.data.local.LastTradeRow
import com.tickedge.data.local.SessionSummary
import com.tickedge.data.local.SimulationPositionEntity
import com.tickedge.data.local.SimulationSessionEntity
import com.tickedge.data.local.StrategySettingsEntity
import com.tickedge.data.local.UserProfileEntity
import com.tickedge.data.local.UserSettingsEntity
import com.tickedge.data.model.AccountType
import com.tickedge.data.model.AnalysisMetric
import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.DigitSelectionMode
import com.tickedge.data.model.EvenOddMode
import com.tickedge.data.model.MarketState
import com.tickedge.data.model.SimulatedTrade
import com.tickedge.data.model.SystemConfig
import com.tickedge.data.model.TradeDurationUnit
import com.tickedge.data.model.TradeSettings
import com.tickedge.data.websocket.DerivWebSocketService
import com.tickedge.domain.ActiveTradeState
import com.tickedge.domain.DigitAnalysisEngine
import com.tickedge.domain.NextTradePreview
import com.tickedge.domain.RecoveryCalculator
import com.tickedge.domain.ScanState
import com.tickedge.domain.SimulatorState
import com.tickedge.domain.StopNotice
import com.tickedge.domain.PARITY_EVEN_SENTINEL
import com.tickedge.domain.StrategyCatalog
import com.tickedge.domain.StrategyDefinition
import com.tickedge.domain.StrategyRunState
import com.tickedge.domain.TradingEngine
import com.tickedge.domain.TradingRuntime
import com.tickedge.domain.TradingService
import com.tickedge.domain.resolveParityClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import java.util.UUID

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
 * Quiet period before a settings change is mirrored to FIDScript.
 *
 * Long enough to collapse a burst of keystrokes into one request, short enough that the change is
 * already in the cloud by the time the user leaves the screen.
 */
private const val CLOUD_PUSH_DEBOUNCE_MS = 800L

/** Trades uploaded per drain. Bounded so one sync cannot stall the UI thread on a long backlog. */
private const val TRADE_UPLOAD_BATCH = 50

/**
 * How often balances are re-fetched from REST as a backstop to the socket's live push.
 *
 * The selected account's balance arrives by push and is already current, so this mainly covers the
 * other account and any time the socket is down.
 */
private const val BALANCE_REFRESH_MS = 30_000L

/**
 * Ticks required before a threshold is considered meaningful.
 *
 * Below this the window is too short for a percentage to mean anything, and a single tick swinging
 * the result by 10 points would otherwise fire entries on noise.
 */
private const val MIN_ANALYSIS_SAMPLE = 10

/**
 * Spare balance required on top of a stake before an account is considered able to trade it.
 *
 * Deriv reserves the stake and settles fees against it, so a balance sitting exactly on the stake
 * is refused even though the arithmetic looks sufficient.
 */
private const val STAKE_HEADROOM = 0.01

class MainViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * The trading engine, the sockets, the OAuth client and the session — owned by the
     * application, not by this ViewModel.
     *
     * The engine used to be created here, which meant its coroutines ran in `viewModelScope` and
     * died with the Activity — so backgrounding the app stopped the bot, and swiping it away from
     * recents cancelled the loop mid-trade, leaving a contract the user had paid for unrecorded.
     * The same was true of the OAuth client, the Deriv client and the market-data socket: all
     * three were built against `viewModelScope`, so they were all cancelled with it. They now live
     * in the runtime, and the foreground service keeps the process alive, so a run survives the
     * screen.
     */
    private val runtime = TradingRuntime.get(application)

    private val db get() = runtime.db
    private val oauth get() = runtime.oauth
    private val deriv get() = runtime.deriv
    private val webSocketService get() = runtime.webSocketService

    /**
     * The strategy the user is running.
     *
     * Declared here, above everything that reads it, because the engine lookup below reads it
     * during this class's own initialisation. A property declared further down would still be
     * null at that point, and the first read would crash.
     */
    private val _selectedStrategyId = MutableStateFlow(StrategyCatalog.DEFAULT.id)

    /**
     * The engine for the strategy in use.
     *
     * One engine per strategy, reached through the runtime so there is exactly one of each. Two
     * strategies can therefore trade at the same time, each with its own rotation, cool-offs and
     * losing streak.
     */
    private val tradingEngine: TradingEngine get() = runtime.engineFor(_selectedStrategyId.value)

    // ---------------------------------------------------------------- Deriv accounts

    /**
     * The Deriv accounts this device is signed in to.
     *
     * Up to three. Each has its own credentials and its own trade history, because the data layer
     * is scoped by the account's identity.
     */
    val derivSlots: StateFlow<List<DerivSlot>> = oauth.slots

    /** The account currently in use. */
    val activeSlotId: StateFlow<String> = oauth.activeSlotId

    /**
     * Switches to a different Deriv account.
     *
     * Trading is stopped first. An account switch moves the credentials out from under the engine,
     * and a bot still placing orders against the previous account's balance while the app shows
     * the new account is exactly the wrong thing to happen.
     */
    fun switchDerivAccount(slotId: String) {
        if (slotId == oauth.activeSlotId.value) return
        runtime.stopAllEngines()
        viewModelScope.launch {
            if (!oauth.switchTo(slotId)) {
                android.util.Log.w("MainViewModel", "Unknown account slot $slotId — ignoring")
                return@launch
            }
            // The accounts and sockets belong to the login that produced them.
            deriv.resetForAccountSwitch()
            deriv.loadAccounts()
            // The cloud session is the person's, not the Deriv account's — switching Deriv
            // accounts must not sign them out of their TickEdge account. What changes is whose
            // trades get filed: the new account's, under the same person's cloud record.
            // The identity moved with the credentials, so the profile, settings, history and
            // strategy all have to be re-read for the account now in use.
            latchUserIdentity()
            restoreCloudSession()
        }
    }

    /**
     * Starts a sign-in for an additional Deriv account.
     *
     * The browser round trip happens outside the app, so the account is not attached until the
     * redirect comes back — at which point the OAuth client registers it and makes it active.
     */
    fun addDerivAccount(activity: Activity): Boolean {
        if (!oauth.startAddAccount(activity)) {
            android.util.Log.w(
                "MainViewModel",
                "Refusing to add a fourth Deriv account — the limit is " +
                    "${AccountSlotRegistry.MAX_ACCOUNTS}"
            )
            return false
        }
        return true
    }

    /** Signs out of one Deriv account, leaving the others alone. */
    fun signOutDerivAccount(slotId: String) {
        if (slotId == oauth.activeSlotId.value) runtime.stopAllEngines()
        oauth.signOutSlot(slotId)
        viewModelScope.launch {
            deriv.resetForAccountSwitch()
            deriv.loadAccounts()
            latchUserIdentity()
        }
    }

    /**
     * Stable user identity; derived from the authenticated Deriv user or the legacy default.
     * Uses [DerivUser.stableKey] (open_id if available, otherwise id) to match the
     * user identifier stored in SecureTokenStore.
     *
     * Latched rather than re-derived on every access. `DerivOAuthClient.rehydrate()` runs on
     * every `onResume` and rewrites the auth state, so reading the user through the auth state at
     * the moment of a write meant one unlucky trade could be filed under `ANONYMOUS_USER_ID` while
     * every read was scoped to the real id — the row was written, and no query has ever matched it
     * again. Reads and writes now resolve identity from the same latched value, which only ever
     * moves to a real id and never back to anonymous, so a momentary auth blip cannot orphan data.
     */
    private val _userId = MutableStateFlow(ANONYMOUS_USER_ID)
    private val currentUserId: String
        get() = _userId.value

    /**
     * Keeps the runtime's idea of the user in step with the ViewModel's.
     *
     * The engine reads the user id when it writes a position, and it does so from the runtime —
     * which outlives this ViewModel. Writing it in one place only would mean the engine filed
     * trades under whichever key it last saw, which is exactly how rows end up orphaned.
     */
    private fun syncRuntimeIdentity() {
        runtime.userId = _userId.value
    }

    /**
     * The same latched key, as a flow, for every read.
     *
     * Reads and writes now resolve identity from one place. They used to resolve it two ways —
     * writes re-read the auth state through a getter, reads took a value from the auth state flow —
     * and the two could disagree. When they did, a trade was written under one key and queried
     * under another: stored correctly, and permanently invisible. Declared here rather than beside
     * the other Room flows because the flows above it read it during their own initialisation.
     */
    val userId: StateFlow<String> = _userId.asStateFlow()

    // Resolve the user per write, not once at construction — see TradingEngine's userIdProvider.
    /**
     * Id of the session positions are currently being written to.
     *
     * Held in the [TradingRuntime] rather than here: the engine outlives this ViewModel, so the id
     * has to live where the engine can still read it after the Activity is gone.
     */
    private val currentSessionId: Long
        get() = runtime.sessionIdFor(_selectedStrategyId.value)

    /** True while a Start is opening its session. Guards the double-press that would open two. */
    @Volatile
    private var startingRun = false

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

    private val _syncState = MutableStateFlow<SyncState>(
        SyncState.Unavailable("Not signed in yet.")
    )

    /** Pending debounced cloud mirror of the settings; see [scheduleCloudPush]. */
    private var cloudPushJob: Job? = null

    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** FIDScript cross-device sync status. */
    sealed interface SyncState {
        /**
         * Signed in with Deriv, but FIDScript cannot be reached — [reason] says why.
         *
         * Previously a silent `Idle`, which is what let this go unnoticed for so long: with no
         * `id_token` the session was never established, every upload returned early, and the UI
         * showed the same unremarkable "not connected" as it did before sign-in. A user watching for
         * their history to follow them to a new phone was given no signal that it never would.
         */
        data class Unavailable(val reason: String) : SyncState

        /** Exchanging the Deriv id token for a FIDScript session. */
        data object Connecting : SyncState

        /** Session established; settings and history are moving. */
        data object Syncing : SyncState

        /** Signed in and idle — everything on this device is up to date. */
        data class Ready(val userId: String) : SyncState

        /** Attempted and failed. [reason] is the error from the server. */
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
            restoreCloudSession()
        }
        return ok
    }

    /**
     * Whether the TickEdge account (the cross-device identity) is signed in.
     *
     * Deliberately separate from [authState]: Deriv signed in but the TickEdge account not is
     * the state where the user can trade locally and the numbers simply do not follow them to
     * another phone. A single "is signed in" flag would hide that.
     */
    private val _cloudSignedIn = MutableStateFlow(fidscriptSession.isSignedIn())
    val cloudSignedIn: StateFlow<Boolean> = _cloudSignedIn.asStateFlow()

    /** The email the TickEdge account was created with, or null when it is not signed in. */
    private val _cloudEmail = MutableStateFlow(fidscriptSession.email())
    val cloudEmail: StateFlow<String?> = _cloudEmail.asStateFlow()

    /**
     * True while the TickEdge account still owes the platform its registration — a first name,
     * a last name, a username.
     *
     * Set when the account was just created and the device holds no stored registration, and
     * when a stored session came back without one (a second phone signing into a first-created
     * account). Cleared by [saveCloudProfile]. The registration step in the sign-in screen is
     * visible exactly while this is true.
     */
    private val _cloudProfileNeeded =
        MutableStateFlow(fidscriptSession.isSignedIn() && fidscriptSession.hasProfile() != true)
    val cloudProfileNeeded: StateFlow<Boolean> = _cloudProfileNeeded.asStateFlow()

    /**
     * Saves the registration and mirrors it to the cloud so the next device knows who this
     * account is. The cloud write is best-effort: a failed push must not block a sign-in that
     * already succeeded, so the flag clears regardless and the next cloud touch retries.
     */
    fun saveCloudProfile(firstName: String, lastName: String, username: String) {
        val first = firstName.trim()
        val last = lastName.trim()
        val user = username.trim()
        fidscriptSession.storeProfile(first, last, user)
        _cloudProfileNeeded.value = false

        // The profile seeds the display name, but only when the user has not chosen one
        // themselves: an explicit name already on the profile row must not be overwritten.
        viewModelScope.launch {
            if (first.isNotBlank() && userProfile.value.displayName.isBlank()) {
                db.userProfileDao().updateDisplayName(currentUserId, "$first $last".trim())
            }
        }

        val fidUserId = fidscriptSession.userId() ?: return
        val entityId = UUID.nameUUIDFromBytes("tickedge:user:$fidUserId".toByteArray()).toString()
        val email = fidscriptSession.email().orEmpty()
        viewModelScope.launch {
            fidscript.upsertTickedgeUser(
                fidUserId = fidUserId,
                entityId = entityId,
                fields = mapOf(
                    "firstName" to first,
                    "lastName" to last,
                    "username" to user,
                    "email" to email,
                    "createdAt" to System.currentTimeMillis()
                )
            ).onFailure { error ->
                android.util.Log.w("MainViewModel", "Cloud profile write failed; local copy kept", error)
            }
        }
    }

    /**
     * Sends the sign-in code for the TickEdge account to [email].
     *
     * The first sign-in from an email creates the account; every later one finds it. That is
     * the whole cross-device story, which is why the sign-in screen says "create your free
     * account" rather than "log in".
     */
    fun sendCloudMagicCode(email: String) {
        val trimmed = email.trim()
        if (trimmed.isBlank() || !trimmed.contains('@')) {
            _syncState.value = SyncState.Failed("Enter a valid email address.")
            return
        }
        viewModelScope.launch {
            fidscriptSession.sendMagicCode(trimmed)
                .onSuccess { _syncState.value = SyncState.Connecting }
                .onFailure { error ->
                    _syncState.value = SyncState.Failed(error.message ?: "Could not send the code.")
                }
        }
    }

    /**
     * Verifies the code and links this device to the TickEdge account.
     *
     * On success the device has that account's cloud session, and the settings and trade history
     * under it are pulled and pushed, so this phone converges on everything the account has done
     * on any device.
     */
    fun verifyCloudMagicCode(email: String, code: String) {
        viewModelScope.launch {
            _syncState.value = SyncState.Connecting
            fidscriptSession.verifyMagicCode(email.trim(), code)
                .onSuccess { session ->
                    _cloudSignedIn.value = true
                    _cloudEmail.value = session.email
                    // The registration step is owed on a freshly created account that this
                    // device has not filled in yet; a returning device with a stored profile
                    // skips straight to the convergence below.
                    _cloudProfileNeeded.value = session.created && fidscriptSession.hasProfile() != true
                    _syncState.value = SyncState.Syncing
                    syncSettingsFromCloud(session.userId)
                    drainTradeHistoryToCloud(session.userId)
                    pullTradeHistoryFromCloud(session.userId)
                    _syncState.value = SyncState.Ready(session.userId)
                }
                .onFailure { error ->
                    _syncState.value = SyncState.Failed(error.message ?: "Sign-in failed.")
                }
        }
    }

    /**
     * The create-account path: verify the code, and register the person's details with the
     * account that was just created (or found).
     *
     * The details are what the platform knows about the person — the sign-in screen asks for
     * them once, at creation, and they are what makes the next phone say who is signed in
     * rather than a bare email.
     */
    fun signUpCloudAccount(
        firstName: String,
        lastName: String,
        username: String,
        email: String,
        code: String
    ) {
        viewModelScope.launch {
            _syncState.value = SyncState.Connecting
            fidscriptSession.verifyMagicCode(email.trim(), code)
                .onSuccess { session ->
                    _cloudSignedIn.value = true
                    _cloudEmail.value = session.email
                    saveCloudProfile(firstName, lastName, username)
                    _syncState.value = SyncState.Syncing
                    syncSettingsFromCloud(session.userId)
                    drainTradeHistoryToCloud(session.userId)
                    pullTradeHistoryFromCloud(session.userId)
                    _syncState.value = SyncState.Ready(session.userId)
                }
                .onFailure { error ->
                    _syncState.value = SyncState.Failed(error.message ?: "Sign-in failed.")
                }
        }
    }

    /**
     * Brings a stored cloud session back to life after a restart or a fresh install.
     *
     * The session is encrypted on the device, so "fresh install" here means the user
     * re-installed the app and the encrypted file survived — the same user, and the history
     * pulls back. When nothing is stored, the cloud simply waits for the first sign-in rather
     * than failing: a device that has never signed in is not a broken sync, it is just not one
     * yet.
     */
    private fun restoreCloudSession() {
        val stored = fidscriptSession.session()
        if (stored == null) {
            if (!_cloudSignedIn.value) {
                _syncState.value = SyncState.Unavailable(
                    "Sign in with your email to keep your settings and trade history on this " +
                        "device and any other."
                )
            }
            return
        }
        _cloudSignedIn.value = true
        _cloudEmail.value = stored.email
        _cloudProfileNeeded.value = fidscriptSession.hasProfile() != true
        viewModelScope.launch {
            _syncState.value = SyncState.Syncing
            syncSettingsFromCloud(stored.userId)
            drainTradeHistoryToCloud(stored.userId)
            pullTradeHistoryFromCloud(stored.userId)
            _syncState.value = SyncState.Ready(stored.userId)
        }
    }

    /**
     * Signs out of the TickEdge account on this device.
     *
     * The Deriv sign-in is left in place: unlinking the cloud must not log the user out of
     * their trading account. The local Room history stays as well — it is theirs — but it no
     * longer follows them.
     */
    fun signOutCloud() {
        fidscriptSession.signOut()
        _cloudSignedIn.value = false
        _cloudEmail.value = null
        _syncState.value = SyncState.Unavailable("Sign in with your email to sync across devices.")
    }

    /**
     * Mirror the settings to FIDScript shortly after they change.
     *
     * Debounced because the Trade Settings screen calls `updateTradeSettings` on every keystroke,
     * and each call would otherwise be a round trip. Latest-wins: the pending push is cancelled and
     * rescheduled, and the coroutine reads the state flows when it finally runs rather than
     * capturing it at schedule time — so what lands is the last thing the user typed, not the
     * first keystroke of a burst.
     *
     * A no-op when there is no FIDScript session. Local-only remains correct in that case, and the
     * next sign-in pushes.
     */
    private fun scheduleCloudPush() {
        cloudPushJob?.cancel()
        cloudPushJob = viewModelScope.launch {
            delay(CLOUD_PUSH_DEBOUNCE_MS)
            if (fidscript.isReady()) pushSettingsToCloud()
        }
    }

    private suspend fun pushSettingsToCloud() {
        val fidUserId = fidscriptSession.userId() ?: return
        // The cloud attribute set predates the split and only carries Differs fields, so the
        // Differs blob is the one that mirrors. The Even/Odd blob is device-local for now — a
        // cloud restore leaves it at defaults on that device.
        val settings = _strategySettings.value[StrategyCatalog.DEFAULT.id] ?: TradeSettings()
        val config = _systemConfig.value

        fidscript.upsertUserSettings(
            userId = fidUserId,
            // One stable row per user. The previous random-per-call id meant every save added a new
            // row, so the namespace accumulated settings nobody could tell apart or read back.
            entityId = InstantDbClient.settingsEntityId(fidUserId),
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
                // The entry condition, the metric it is measured against, the digit rule and the
                // tick window. These are the values the Trade Settings screen edits, and they had
                // no attribute to write to until the schema gained them.
                "thresholdPercent" to settings.thresholdPercent,
                "comparisonOperator" to settings.comparisonOperator.symbol,
                "recoveryThresholdPercent" to settings.recoveryThresholdPercent,
                "recoveryComparisonOperator" to settings.recoveryComparisonOperator.symbol,
                "analysisMetric" to settings.analysisMetric.symbol,
                "digitSelectionMode" to settings.digitSelectionMode.symbol,
                "windowSize" to settings.analysisWindowSize,
                "durationUnit" to settings.durationUnit.label,
                "durationValue" to settings.durationValue,
                "martingaleEnabled" to settings.martingaleEnabled,
                "randomMarkets" to config.randomMarkets,
                "maxTradesPerMarket" to config.maxTradesPerMarket,
                "stopTradingOnLimit" to config.stopTradingOnLimit,
                "minSampleSize" to MIN_ANALYSIS_SAMPLE,
                "updatedAt" to System.currentTimeMillis()
            )
        ).onFailure { error ->
            _syncState.value = SyncState.Failed(error.message ?: "Settings sync failed")
        }
    }

    /**
     * Reconcile the cloud settings row with the local one, newest edit winning.
     *
     * Both sides carry an `updatedAt`, so the conflict is decided by time rather than by which
     * device asked last. That distinction matters: a blind "cloud wins" would discard settings the
     * user edited while signed out, and a blind "local wins" would discard the edit made on another
     * device — the entire reason for having a cloud copy.
     *
     * The cloud row is applied as a **partial overlay** onto the local one. A field the remote row
     * has never held keeps its local value instead of snapping to a default, so a row written by an
     * older build of the app cannot silently reset settings it knows nothing about.
     */
    private suspend fun syncSettingsFromCloud(fidUserId: String) {
        val entityId = InstantDbClient.settingsEntityId(fidUserId)
        val remote = fidscript.query(FidscriptAttrs.NS_USER_SETTINGS)
            .onFailure { error ->
                // A failed read must not overwrite local settings, and must not report the session
                // as broken either: the session is established and writes still work, so flipping
                // to Failed here would show "cloud sync failed" while the next edit saves normally.
                // The state stays Ready and this read is simply skipped.
                android.util.Log.w("MainViewModel", "Settings read failed; keeping local values", error)
                return
            }
            .getOrDefault(emptyList())
            .filter { it.entityId == entityId }
            .associate { it.attrId to it.value }

        if (remote.isEmpty()) {
            // First run on this cloud account: publish what the device already has.
            pushSettingsToCloud()
            return
        }

        val local = db.userSettingsDao().getSettings(currentUserId).first()
        val remoteUpdatedAt = (remote[FidscriptAttrs.USER_SETTINGS_UPDATEDAT] as? Number)?.toLong() ?: 0L
        val localUpdatedAt = local?.updatedAt ?: 0L

        if (remoteUpdatedAt <= localUpdatedAt) {
            pushSettingsToCloud()
            return
        }

        // A row with no readable `updatedAt` cannot be dated, so it cannot win a comparison. Push local
        // instead of returning: leaving it alone would leave the cloud stale with no indication
        // that anything was expected.
        val merged = applyRemoteSettings(local, remote) ?: run {
            pushSettingsToCloud()
            return
        }
        db.userSettingsDao().saveSettings(merged)
        // The cloud row only carries Differs fields, so it updates the Differs strategy's blob.
        // The Even/Odd blob stays as it was on this device.
        _strategySettings.update { map ->
            map + (StrategyCatalog.DEFAULT.id to toTradeSettings(merged))
        }
        _systemConfig.value = toSystemConfig(merged)
        _realArmed.value = merged.realArmed
        _requirePerOrderConfirm.value = merged.requirePerOrderConfirm
        _maxMartingaleSteps.value = merged.maxMartingaleSteps
        // The window has to reach the socket too, not just the screen, or the analysis would
        // silently keep running over the old tick count. The socket serves whichever strategy
        // is on screen, so it takes the active strategy's window, not the Differs blob's.
        webSocketService.setWindowSize(activeSettings.analysisWindowSize)
    }

    /**
     * Overlay the values present in the cloud row onto the local settings.
     *
     * Every read is defensive and falls back to the local value: these arrive as JSON scalars whose
     * type depends on what was written, so a malformed or missing entry must leave that one field
     * alone rather than throw and abandon the whole restore. Returns null only when the row holds
     * nothing usable at all, which is treated as "no remote settings" by the caller.
     */
    private fun applyRemoteSettings(
        local: UserSettingsEntity?,
        remote: Map<String, Any?>
    ): UserSettingsEntity? {
        fun str(attr: String, fallback: String): String = remote[attr]?.toString() ?: fallback
        fun int(attr: String, fallback: Int): Int =
            (remote[attr] as? Number)?.toInt()
                ?: remote[attr]?.toString()?.toDoubleOrNull()?.toInt()
                ?: fallback
        fun dbl(attr: String, fallback: Double): Double =
            (remote[attr] as? Number)?.toDouble()
                ?: remote[attr]?.toString()?.toDoubleOrNull()
                ?: fallback
        fun bool(attr: String, fallback: Boolean): Boolean =
            (remote[attr] as? Boolean)
                ?: remote[attr]?.toString()?.toBooleanStrictOrNull()
                ?: fallback

        val updatedAt = (remote[FidscriptAttrs.USER_SETTINGS_UPDATEDAT] as? Number)?.toLong()
            ?: remote[FidscriptAttrs.USER_SETTINGS_UPDATEDAT]?.toString()?.toDoubleOrNull()?.toLong()
        if (updatedAt == null) return null

        val base = local ?: UserSettingsEntity(userId = currentUserId)
        return base.copy(
            id = 1L,
            userId = currentUserId,
            updatedAt = updatedAt,
            windowSize = int(
                FidscriptAttrs.USER_SETTINGS_WINDOWSIZE,
                base.windowSize
            ).coerceIn(MIN_ANALYSIS_WINDOW, MAX_ANALYSIS_WINDOW),
            selectedDigit = int(FidscriptAttrs.USER_SETTINGS_TARGETDIGIT, base.selectedDigit),
            recoveryDigit = int(FidscriptAttrs.USER_SETTINGS_RECOVERYDIGIT, base.recoveryDigit),
            recoveryDirection = str(
                FidscriptAttrs.USER_SETTINGS_RECOVERYDIRECTION,
                base.recoveryDirection
            ),
            comparisonOperator = str(
                FidscriptAttrs.USER_SETTINGS_COMPARISONOPERATOR,
                base.comparisonOperator
            ),
            thresholdPercent = dbl(
                FidscriptAttrs.USER_SETTINGS_THRESHOLDPERCENT,
                base.thresholdPercent
            ),
            recoveryComparisonOperator = str(
                FidscriptAttrs.USER_SETTINGS_RECOVERYCOMPARISONOPERATOR,
                base.recoveryComparisonOperator
            ),
            recoveryThresholdPercent = dbl(
                FidscriptAttrs.USER_SETTINGS_RECOVERYTHRESHOLD,
                base.recoveryThresholdPercent
            ),
            analysisMetric = str(FidscriptAttrs.USER_SETTINGS_ANALYSISMETRIC, base.analysisMetric),
            digitSelectionMode = str(
                FidscriptAttrs.USER_SETTINGS_DIGITSELECTIONMODE,
                base.digitSelectionMode
            ),
            minimumSampleSize = int(FidscriptAttrs.USER_SETTINGS_MINSAMPLESIZE, base.minimumSampleSize),
            stakeAmount = dbl(FidscriptAttrs.USER_SETTINGS_BASESTAKE, base.stakeAmount),
            takeProfit = dbl(FidscriptAttrs.USER_SETTINGS_TAKEPROFIT, base.takeProfit),
            stopLoss = dbl(FidscriptAttrs.USER_SETTINGS_STOPLOSS, base.stopLoss),
            durationUnit = str(FidscriptAttrs.USER_SETTINGS_DURATIONUNIT, base.durationUnit),
            durationValue = int(FidscriptAttrs.USER_SETTINGS_DURATIONVALUE, base.durationValue),
            martingaleEnabled = bool(
                FidscriptAttrs.USER_SETTINGS_MARTINGALEENABLED,
                base.martingaleEnabled
            ),
            randomMarkets = bool(FidscriptAttrs.USER_SETTINGS_RANDOMMARKETS, base.randomMarkets),
            maxTradesPerMarket = int(
                FidscriptAttrs.USER_SETTINGS_MAXTRADESPERMARKET,
                base.maxTradesPerMarket
            ),
            maxDailyLoss = dbl(FidscriptAttrs.USER_SETTINGS_MAXDAILYLOSS, base.maxDailyLoss),
            stopTradingOnLimit = bool(
                FidscriptAttrs.USER_SETTINGS_STOPTRADINGONLIMIT,
                base.stopTradingOnLimit
            ),
            realArmed = bool(FidscriptAttrs.USER_SETTINGS_REALARMED, base.realArmed),
            requirePerOrderConfirm = bool(
                FidscriptAttrs.USER_SETTINGS_REQUIREPERORDERCONFIRM,
                base.requirePerOrderConfirm
            ),
            maxMartingaleSteps = int(
                FidscriptAttrs.USER_SETTINGS_MAXMARTINGALESTEPS,
                base.maxMartingaleSteps
            )
        )
    }

    /**
     * Upload settled trades that have not reached the cloud yet.
     *
     * An outbox rather than an upload-at-the-moment, because FIDScript may not be ready when a
     * trade settles — the session depends on an `openid` token that can be missing or expired. A
     * trade uploaded only when the socket happened to be live would be lost otherwise. Rows are
     * marked synced afterwards and skipped on every later run, so this is safe to call often.
     */
    private suspend fun drainTradeHistoryToCloud(fidUserId: String) {
        val pending = db.simulationDao().getUnsyncedPositions(currentUserId, TRADE_UPLOAD_BATCH)
        if (pending.isEmpty()) return

        val syncedIds = mutableListOf<Long>()
        for (position in pending) {
            // A trade with no contract id cannot be keyed deterministically, so it would
            // duplicate on every run. Skip it rather than pollute the cloud history.
            val contractId = position.contractId
            if (contractId.isBlank()) continue

            val result = fidscript.upsertTrade(
                entityId = InstantDbClient.tradeEntityId(contractId),
                fields = mapOf(
                    "userId" to fidUserId,
                    "market" to position.marketSymbol,
                    "digit" to position.targetDigit.toString(),
                    "stake" to position.simulatedStake.toString(),
                    "net" to position.simulatedNetResult.toString(),
                    "outcome" to position.outcome,
                    "isReal" to position.isReal.toString(),
                    "contractId" to contractId,
                    "accountId" to position.accountId,
                    "note" to "",
                    "currency" to "USD",
                    "createdAt" to position.settledAt.toString(),
                    "openedAt" to position.openedAt.toString(),
                    "askPrice" to position.askPrice.toString(),
                    "payout" to position.simulatedPayout.toString(),
                    "contractType" to position.scenarioType,
                    // The recovery snapshot. Sent unconditionally — including the zeros for an
                    // ordinary entry — so a restored trade can say "this was rung 0" rather than
                    // leaving the field absent and the detail screen guessing.
                    "recoveryStep" to position.recoveryStep.toString(),
                    "outstandingBefore" to position.outstandingBefore.toString(),
                    // Absent on records taken before the live quote existed, and `upsertTrade` drops
                    // nulls, so the restored row reads "not recorded" rather than "0% return".
                    "payoutRatio" to position.payoutRatio?.toString()
                )
            )
            // Stop at the first failure rather than pressing on: a failure here is usually the
            // session or the network, and continuing would just burn the batch.
            if (result.isFailure) return
            syncedIds += position.id
        }

        if (syncedIds.isNotEmpty()) {
            db.simulationDao().markSynced(syncedIds)
        }
    }

    /**
     * Restore trade history this device does not have.
     *
     * The direction that makes a reinstall recoverable. Keyed on `contractId`, which is what makes
     * it repeatable: a trade already present locally is skipped, so pulling on every sign-in cannot
     * inflate the history, and a device that traded offline still converges on the same set.
     *
     * Imported rows carry `sessionId = NO_SESSION` because FIDScript has no concept of a session.
     * `simulation_positions` has no foreign key on `sessionId`, so this is safe, and they still
     * appear in `pastPositions` — which is what Trade History and the P&L tab read.
     */
    private suspend fun pullTradeHistoryFromCloud(fidUserId: String) {
        val rows = fidscript.query(FidscriptAttrs.NS_TRADE_HISTORY)
            .onFailure { return }
            .getOrDefault(emptyList())
            .groupBy { it.entityId }

        rows.values.forEach { entityRows ->
            val byAttr = entityRows.associate { it.attrId to it.value }
            val contractId = byAttr[FidscriptAttrs.TRADE_HISTORY_CONTRACTID]?.toString().orEmpty()
            if (contractId.isBlank()) return@forEach
            if (db.simulationDao().getPositionByContractId(currentUserId, contractId) != null) {
                return@forEach
            }

            fun num(attr: String): Double = when (val v = byAttr[attr]) {
                is Number -> v.toDouble()
                null -> 0.0
                else -> v.toString().toDoubleOrNull() ?: 0.0
            }

            val settledAt = when (val v = byAttr[FidscriptAttrs.TRADE_HISTORY_CREATEDAT]) {
                is Number -> v.toLong()
                null -> System.currentTimeMillis()
                else -> v.toString().toDoubleOrNull()?.toLong() ?: System.currentTimeMillis()
            }

            val scenarioType =
                byAttr[FidscriptAttrs.TRADE_HISTORY_CONTRACTTYPE]?.toString() ?: "Differs (Main)"

            db.simulationDao().insertPosition(
                SimulationPositionEntity(
                    userId = currentUserId,
                    sessionId = NO_SESSION,
                    marketSymbol = byAttr[FidscriptAttrs.TRADE_HISTORY_MARKET]?.toString().orEmpty(),
                    marketName = byAttr[FidscriptAttrs.TRADE_HISTORY_MARKET]?.toString().orEmpty(),
                    scenarioType = scenarioType,
                    // Imported rows predate the strategy id on the cloud; derive it the same way
                    // the 15→16 migration backfilled local rows, so they stay filterable by
                    // strategy in the history.
                    strategyId = if (scenarioType.contains("Even")) "even_odd" else "differs_with_recovery",
                    simulatedStake = num(FidscriptAttrs.TRADE_HISTORY_STAKE),
                    simulatedPayout = num(FidscriptAttrs.TRADE_HISTORY_PAYOUT),
                    simulatedNetResult = num(FidscriptAttrs.TRADE_HISTORY_NET),
                    outcome = byAttr[FidscriptAttrs.TRADE_HISTORY_OUTCOME]?.toString() ?: "PENDING",
                    targetDigit = byAttr[FidscriptAttrs.TRADE_HISTORY_DIGIT]?.toString()
                        ?.toDoubleOrNull()?.toInt() ?: 0,
                    entryQuote = 0.0,
                    exitQuote = 0.0,
                    exitDigit = -1,
                    openedAt = settledAt,
                    settledAt = settledAt,
                    accountId = byAttr[FidscriptAttrs.TRADE_HISTORY_ACCOUNTID]?.toString().orEmpty(),
                    contractId = contractId,
                    isReal = byAttr[FidscriptAttrs.TRADE_HISTORY_ISREAL]?.toString()
                        ?.toBooleanStrictOrNull() ?: false,
                    // Already on the cloud by definition, so it must not be queued for upload.
                    syncedToCloud = true,
                    // Restored verbatim. These are snapshots of what the ladder knew when the trade
                    // was placed; the local ladder has moved on since, so nothing here may be
                    // recomputed from current settings. A field the cloud row never had stays null
                    // or zero and the detail screen says so.
                    askPrice = num(FidscriptAttrs.TRADE_HISTORY_ASKPRICE),
                    payoutRatio = byAttr[FidscriptAttrs.TRADE_HISTORY_PAYOUTRATIO]
                        ?.toString()?.toDoubleOrNull(),
                    recoveryStep = num(FidscriptAttrs.TRADE_HISTORY_RECOVERYSTEP).toInt(),
                    outstandingBefore = num(FidscriptAttrs.TRADE_HISTORY_OUTSTANDINGBEFORE)
                )
            )
        }
    }

    fun derivLogout() {
        // Every strategy, not just the one on screen. Signing out has to leave nothing running
        // behind it — a strategy still trading under an account the user has just signed out of
        // is the worst possible state to be in.
        runtime.stopAllEngines()
        // Close the open session with its totals BEFORE the identity is dropped, so the run that
        // just ended is complete on the history page. `endSession` only closes the currently
        // adopted one; this closes every strategy's open session under the user who is leaving.
        val outgoingUser = currentUserId
        viewModelScope.launch {
            runtime.closeAllOpenSessionsForUser(outgoingUser)
            endSession()
        }
        // Sign out of every Deriv account, not just the active one. The previous behaviour
        // (`oauth.logout()`) left the other slots signed in: the device then belonged to no one,
        // yet it still held working credentials for up to three Deriv logins — and the app kept
        // saying "authenticated" until the next start. The person has left; nothing of theirs
        // stays behind.
        oauth.logoutAll()
        // The TickEdge account is separate from the Deriv account, but it is still tied to the
        // person who just signed out: its refresh token grants read/write on their rows, so it
        // must not outlive their sign-in on this device. The local Room history stays — it is
        // theirs — it just no longer syncs until they sign back in.
        fidscriptSession.signOut()
        _cloudSignedIn.value = false
        _cloudEmail.value = null
        _syncState.value = SyncState.Unavailable("Signed out. Sign in to sync to your account.")
        deriv.dispose()
        // Back to a neutral identity rather than the outgoing user's, so a trade that slips in
        // before the next sign-in does not file itself under a user who is no longer here.
        _userId.value = ANONYMOUS_USER_ID
        syncRuntimeIdentity()

        // Clear the previous user's settings and the session they were trading in.
        //
        // Settings are per-user in the database, and loadPersistedSettings() pulls the next
        // signer's own values — but that load is asynchronous. Without this reset the outgoing
        // user's stake, thresholds and live-trading arming stay visible to whoever signs in next,
        // and if they hit Start before the load lands, their first trade is sized from someone
        // else's configuration.
        _strategySettings.value = emptyMap()
        _systemConfig.value = SystemConfig()
        _realArmed.value = false
        _requirePerOrderConfirm.value = false
        _maxMartingaleSteps.value = 3
        runtime.releaseAllSessions()
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
        userId.flatMapLatest { uid ->
            // The latched key, so this reads the row `ensureProfile` actually wrote. It used to
            // resolve the user a second way, and missed the row it had itself just created.
            db.userProfileDao().getProfile(uid).map { it ?: UserProfileEntity(id = uid) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserProfileEntity(id = ANONYMOUS_USER_ID))

    // Settings
    //
    // One complete settings blob per strategy, keyed by strategy id. The Differs and Even/Odd
    // strategies used to share one [TradeSettings] instance, so editing one strategy's stake or
    // threshold silently re-tuned the other. Each strategy now owns its own blob — including its
    // own stake, duration and stops — and [tradeSettings] below is the *active* strategy's,
    // so the screens keep a single-flow signature without straddling strategies.
    private val _strategySettings: MutableStateFlow<Map<String, TradeSettings>> =
        MutableStateFlow(emptyMap())
    val strategySettings: StateFlow<Map<String, TradeSettings>> = _strategySettings.asStateFlow()
    val tradeSettings: StateFlow<TradeSettings> = combine(
        _selectedStrategyId,
        _strategySettings
    ) { id, map -> map[id] ?: TradeSettings() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TradeSettings())

    /** The active strategy's settings blob; the strategy's defaults until one has been loaded. */
    private val activeSettings: TradeSettings
        get() = _strategySettings.value[_selectedStrategyId.value] ?: TradeSettings()

    private fun activeStrategyId(): String = _selectedStrategyId.value

    /** Write a blob into the active strategy's slot of the map. */
    private fun setStrategySettings(blob: TradeSettings) {
        _strategySettings.update { it + (activeStrategyId() to blob) }
    }
    private val _systemConfig = MutableStateFlow(SystemConfig())
    val systemConfig: StateFlow<SystemConfig> = _systemConfig.asStateFlow()

    // Real-execution safety controls
    private val _realArmed = MutableStateFlow(false)
    val realArmed: StateFlow<Boolean> = _realArmed.asStateFlow()
    private val _requirePerOrderConfirm = MutableStateFlow(false)
    val requirePerOrderConfirm: StateFlow<Boolean> = _requirePerOrderConfirm.asStateFlow()
    private val _maxMartingaleSteps = MutableStateFlow(3)
    val maxMartingaleSteps: StateFlow<Int> = _maxMartingaleSteps.asStateFlow()

    // Trading engine state (now real orders)
    //
    // Each of these follows the strategy in use. They used to point straight at one engine's
    // StateFlow, which meant the app showed whichever strategy happened to be loaded at the time
    // the ViewModel was created — and switching strategies changed nothing on screen. Flattening
    // over the strategy id makes the switch real: the page now shows the strategy the user picked.
    val simulatorState: StateFlow<SimulatorState> = _selectedStrategyId
        .flatMapLatest { id -> runtime.engineFor(id).simState }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SimulatorState.IDLE)
    val activeTrade: StateFlow<ActiveTradeState?> = _selectedStrategyId
        .flatMapLatest { id -> runtime.engineFor(id).activeTrade }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val nextTrade: StateFlow<NextTradePreview> = _selectedStrategyId
        .flatMapLatest { id -> runtime.engineFor(id).nextTrade }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NextTradePreview())
    val consecutiveLosses: StateFlow<Int> = _selectedStrategyId
        .flatMapLatest { id -> runtime.engineFor(id).consecutiveLosses }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val lastFinishedTrade: StateFlow<SimulatedTrade?> = _selectedStrategyId
        .flatMapLatest { id -> runtime.engineFor(id).lastFinishedTrade }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val recentTradeOutcome: StateFlow<SimulatedTrade?> = _selectedStrategyId
        .flatMapLatest { id -> runtime.engineFor(id).recentTradeOutcome }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val dailyLoss: StateFlow<Double> = _selectedStrategyId
        .flatMapLatest { id -> runtime.engineFor(id).dailyLossAccumulated }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    val pendingConfirmation: StateFlow<NextTradePreview?> = _selectedStrategyId
        .flatMapLatest { id -> runtime.engineFor(id).pendingConfirmation }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * True while the selected account's balance is arriving by push from its trading socket.
     *
     * Drives the "Live" label on the balance card, so the display distinguishes a figure that
     * updates the instant money moves from one that is only as fresh as the last poll.
     */
    val isBalanceLive = deriv.balanceIsLive

    /**
     * What the engine is currently evaluating while scanning.
     *
     * Surfaced so a bot that is searching and finding nothing is visibly searching, rather than
     * looking identical to one that has stopped.
     */
    val scanState: StateFlow<ScanState> = _selectedStrategyId
        .flatMapLatest { id -> runtime.engineFor(id).scanState }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScanState.Idle)

    /**
     * Why automated trading halted, when it halted itself.
     *
     * Null while running and after a user-initiated Stop. The Live Trading page turns this into a
     * banner, because a bot that silently stops on a risk limit is indistinguishable from one that
     * has crashed.
     */
    val stopNotice: StateFlow<StopNotice?> = _selectedStrategyId
        .flatMapLatest { id -> runtime.engineFor(id).stopNotice }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun dismissStopNotice() = tradingEngine.dismissStopNotice()

    // Room flows (user-scoped via flatMapLatest on the latched user id)
    val pastPositions: StateFlow<List<com.tickedge.data.local.SimulationPositionEntity>> =
        userId.flatMapLatest { id ->
            db.simulationDao().getPositionsForUser(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val analysisEvents: StateFlow<List<AnalysisEventEntity>> =
        userId.flatMapLatest { id ->
            db.analysisEventDao().getEventsForUser(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The account's lifetime figures, over every settled trade ever recorded under this user.
     *
     * "Total profit made" and "total lost" are the sums over *settled* trades only — a pending or
     * failed contract is neither a win nor a loss, and counting it would overstate both figures.
     *
     * The Settings page is where they live, because the requirement is "since they started using
     * the application" — the whole record, not the session or the day.
     */
    data class LifetimeStats(
        val totalProfit: Double = 0.0,
        val totalLoss: Double = 0.0,
        val netPnl: Double = 0.0,
        val settledTrades: Int = 0
    )

    val lifetimeStats: StateFlow<LifetimeStats> = pastPositions
        .map { positions ->
            val settled = positions.filter {
                it.outcome == "WON" || it.outcome == "LOST"
            }
            LifetimeStats(
                totalProfit = settled.filter { it.simulatedNetResult > 0 }
                    .sumOf { it.simulatedNetResult },
                totalLoss = -settled.filter { it.simulatedNetResult < 0 }
                    .sumOf { it.simulatedNetResult },
                netPnl = settled.sumOf { it.simulatedNetResult },
                settledTrades = settled.size
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LifetimeStats())

    // ---------------------------------------------------------------- trading sessions

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    /**
     * Every session this user has run, newest first.
     *
     * Scoped by user rather than by "current session" because the Sessions tab is history: it has to
     * list the runs that have already finished, not only the one in progress. That distinction was
     * the whole reason the tab could not show sessions — it was reading the trade list, which is
     * never empty once a trade exists, and rendering it as a histogram.
     */
    val sessions: StateFlow<List<SimulationSessionEntity>> = userId
        .flatMapLatest { id -> db.simulationDao().getSessionsForUser(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * History scoped to the account currently in use.
     *
     * The History page reads these, not the user-wide ones. Switching to the live account has to
     * switch the history with it: a user who has traded only on demo must see an empty live
     * history, not a demo record wearing a live label, and vice versa. The two are never blended,
     * because a blended total is a claim about an account that was never actually traded.
     *
     * `pastPositions`/`sessions` stay user-wide deliberately — Home's all-time figures are the
     * user's own record across everything they have done, and narrowing those would silently
     * rewrite history the moment they switch accounts.
     */
    private val accountScope: StateFlow<Pair<String, Boolean>> =
        combine(userId, _activeAccountType) { id, type -> id to (type == AccountType.LIVE) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ANONYMOUS_USER_ID to false)

    val accountPositions: StateFlow<List<com.tickedge.data.local.SimulationPositionEntity>> =
        accountScope.flatMapLatest { (id, isReal) ->
            db.simulationDao().getPositionsForAccount(id, isReal)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accountSessions: StateFlow<List<SimulationSessionEntity>> =
        accountScope.flatMapLatest { (id, isReal) ->
            db.simulationDao().getSessionsForAccount(id, isReal)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    /**
     * Opens the session for a run, closing any that a previous run left open.
     *
     * This used to *reuse* an ACTIVE session if it found one. That is what made separate runs
     * vanish into one another: a run that never stopped — the app was killed, crashed, or was
     * force-stopped, so the engine's own close never ran — leaves its session marked ACTIVE
     * forever, and the next Start adopted it. Three unrelated runs then shared a single session
     * row, so the Sessions tab showed one run where the user had run three, and its totals spanned
     * all of them.
     *
     * A session is the lifetime of one run, so every Start gets a new one. Any session still open
     * at this point is by definition not the one we are about to use, so it is closed first, with
     * its own SQL-computed totals — which is the honest ending for a run that was interrupted.
     */
    private suspend fun ensureSessionStarted() {
        val account = deriv.activeAccount
        val isReal = account?.kind == DerivAccountKind.REAL
        val strategyId = _selectedStrategyId.value

        db.simulationDao().getStaleActiveSessions(currentUserId, isReal).forEach { stale ->
            runtime.writeSessionTotals(stale)
            android.util.Log.w(
                "MainViewModel",
                "Closed session #${stale.id}, left open by a run that never stopped. Its trades " +
                    "stay with it."
            )
        }

        val session = SimulationSessionEntity(
            userId = currentUserId,
            accountId = account?.id.orEmpty(),
            isReal = isReal,
            strategy = StrategyCatalog.find(strategyId).name,
            strategyId = strategyId,
            targetDigit = activeSettings.targetDigit,
            status = "ACTIVE",
            // Read from the live account so the session's own figures reconcile against the balance
            // the user can see. Left at the 0.0 default before this, which is why every session
            // results card opened and closed on $0.00.
            initialSimulatedBalance = account?.balance ?: 0.0,
            currentSimulatedBalance = account?.balance ?: 0.0
        )
        val opened = db.simulationDao().insertSession(session)
        // Published to the runtime as well, because that is where the engine reads the session id
        // from when it stamps a position. The engine outlives this ViewModel, so it cannot read it
        // from here.
        runtime.openSession(strategyId, session, opened)
        _activeSessionId.value = opened
        _activeSession.value = session.copy(id = opened)
    }

    /**
     * Adopt the most recent session on launch, so history and totals are there immediately.
     *
     * `_activeSessionId` used to stay null until Start was pressed, and every session-scoped flow
     * returns empty for a null id. The result was that relaunching the app showed an empty Trade
     * History and no P&L even though the previous run's rows were sitting in Room — which reads as
     * "the trades were never saved" rather than "the query was scoped to something that did not
     * exist yet".
     *
     * The latest session is adopted whether it is ACTIVE or COMPLETED: an ACTIVE one is what
     * [ensureSessionStarted] would reuse anyway, and a COMPLETED one is the run the user just
     * finished and expects to still be able to read.
     */
    private suspend fun restoreSessionState() {
        // Cleared first, unconditionally. This also runs on a sign-in, so a previous user's adopted
        // session must not survive into the next one: leaving it published would show one account's
        // totals under another account's name.
        runtime.releaseAllSessions()
        _activeSessionId.value = null
        _activeSession.value = null

        // Scoped to the account in use. Adopting the newest session across *both* accounts put one
        // account's run and totals under the other's name as soon as the user switched.
        val isReal = _activeAccountType.value == AccountType.LIVE
        val latest = db.simulationDao().getLatestSessionForAccount(currentUserId, isReal) ?: return
        // Adopted for *display* only. The runtime's session id stays unset, because this row is
        // the run the user already finished — the next run opens a new one, and a position written
        // against a completed session would not appear in its totals.
        _activeSessionId.value = latest.id
        _activeSession.value = latest
    }

    /** Close the running session and clear the current id so the next Start opens a fresh one. */
    private suspend fun endSession() {
        val id = currentSessionId
        if (id == NO_SESSION) return
        // The runtime owns the closing, because the runtime owns the session. It writes the
        // figures from SQL over that session's own settled trades — never from a running counter
        // in the engine, which misses anything settled while the app was backgrounded and dies
        // with the process.
        runtime.closeSession(_selectedStrategyId.value)

        // Re-read the row so the published session carries the figures just written, then keep it
        // published rather than clearing it.
        //
        // Nulling these two flows is what made the History page look like the app had wiped
        // everything: `sessionSummary` and `lastConfirmedTrade` are keyed on `_activeSessionId`, so
        // clearing it sent both to null and the results card fell back to "No session yet" with a
        // $0.00 P&L — immediately after a run that had just produced real results. The finished
        // session stays on screen until the next Start opens a new one.
        _activeSessionId.value = id
        _activeSession.value = db.simulationDao().getSessionById(id)
    }

    // Navigation
    private val _currentScreen = MutableStateFlow("strategies")
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    /**
     * The strategy the user is running.
     *
     * Persisted, because a returning user should land on the strategy they chose rather than
     * being asked again — the choice is a preference, and re-asking on every launch is the kind of
     * thing that trains people to click through the screen that matters.
     *
     * The id itself is declared near the top of the class, because the engine lookup reads it
     * during initialisation.
     */
    val selectedStrategyId: StateFlow<String> = _selectedStrategyId.asStateFlow()

    val selectedStrategy: StateFlow<StrategyDefinition> = _selectedStrategyId
        .map { StrategyCatalog.find(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StrategyCatalog.DEFAULT)

    /** Every strategy on offer, for the picker. */
    val strategies: List<StrategyDefinition> = StrategyCatalog.ALL

    /**
     * Chooses a strategy and moves the app into it.
     *
     * The screen is moved to that strategy's own default route, because the previous strategy's
     * screen may not exist in the new one — a strategy without an Analysis tab would otherwise
     * leave the app showing an Analysis screen it does not offer.
     */
    fun selectStrategy(strategy: StrategyDefinition) {
        _selectedStrategyId.value = strategy.id
        // A digit highlighted in one strategy's analysis table has no target in the other's
        // (the Differs engine may be drawing random digits), so the switch lands with no row
        // claimed.
        _inspectedDigit.value = null
        _currentScreen.value = strategy.defaultRoute
        // Persisted so the app opens on this strategy next time rather than asking again.
        persistSettings()
        android.util.Log.i("MainViewModel", "Selected strategy ${strategy.id}")
    }

    /** Opens the strategy list, for switching or for choosing for the first time. */
    fun openStrategyPicker() {
        _currentScreen.value = "strategies"
    }


    /**
     * Which digit's row the Analysis table highlights, or null when the user has not chosen one.
     *
     * Seeded null rather than at the default target digit: in random mode — the default — the
     * engine draws a fresh digit per trade and targets no particular one, so a highlighted digit
     * out of the box claimed a target that does not exist.
     */
    private val _inspectedDigit = MutableStateFlow<Int?>(null)
    val inspectedDigit: StateFlow<Int?> = _inspectedDigit.asStateFlow()

    init {
        // Resolved synchronously, before anything below touches the database. `restoreSessionState`,
        // `loadPersistedSettings` and `ensureProfile` all run in the coroutine just below and all
        // scope their writes by the current user id — resolving it after they start would file the
        // restored session and the loaded settings under the anonymous key on every cold start.
        (oauth.authState.value as? AuthState.Authenticated)?.user?.stableKey
            ?.takeIf { it.isNotBlank() }
            ?.let {
                _userId.value = it
                // The runtime outlives this ViewModel and holds its own copy of the id, which is
                // what the engine stamps on every trade it writes. Setting only the ViewModel's
                // value meant the engine kept filing trades under "default_trader" for the whole
                // process — stored correctly, invisible to every query that is scoped to the
                // real user. This is exactly how a phone ends up with a full trade record that no
                // screen can see.
                syncRuntimeIdentity()
            }
        webSocketService.start()
        viewModelScope.launch {
            deriv.loadAccounts()
            ensureProfile()
            // Before the settings, so the history and totals are populated by the time the first
            // frame that shows them is composed.
            restoreSessionState()
            loadPersistedSettings()
        }
        observeAuthForProfileAndSync()
        latchUserIdentity()
        observeAnalysisThresholds()
        keepBalancesFresh()
        observeTradesForCloudSync()
    }

    /**
     * Latch the signed-in identity, and never let it fall back to anonymous.
     *
     * `rehydrate()` rewrites the auth state on every resume, so the auth state is a *rendering* of
     * the sign-in, not the sign-in itself. Reading identity off it at the moment of a write meant
     * a single transient `Unauthenticated` could file one trade under `ANONYMOUS_USER_ID` while
     * every read stayed scoped to the real id — written, stored, and never queryable again. This
     * only ever moves to a resolved id, so history cannot be orphaned by an auth blip.
     *
     * The second half is the repair for rows already stranded that way: trades and sessions sitting
     * under the anonymous or legacy key belong to whoever is signed in now, and are moved onto the
     * current key exactly once. Without it the fix only stops new trades being lost — everything
     * already orphaned would stay invisible.
     */
    private fun latchUserIdentity() {
        viewModelScope.launch {
            authState.collect { state ->
                val resolved = (state as? AuthState.Authenticated)?.user?.stableKey
                if (resolved.isNullOrBlank() || resolved == _userId.value) return@collect
                val previous = _userId.value
                _userId.value = resolved
                syncRuntimeIdentity()
                if (previous == ANONYMOUS_USER_ID) reclaimOrphanedHistory(resolved)
            }
        }
    }

    /**
     * Move history stranded under a previous key onto [userId].
     *
     * Only ever claims rows that belong to a different, now-unused identity, and only when the
     * target key has no history of its own — so this cannot merge two real users' results, and it
     * cannot overwrite anything the current user already has.
     */
    private suspend fun reclaimOrphanedHistory(userId: String) {
        runCatching {
            val strandedPositions = db.simulationDao().countPositionsForUser(ANONYMOUS_USER_ID)
            val strandedSessions = db.simulationDao().countSessionsForUser(ANONYMOUS_USER_ID)
            if (strandedPositions == 0 && strandedSessions == 0) return

            val existing = db.simulationDao().countPositionsForUser(userId)
            if (existing > 0) {
                android.util.Log.w(
                    "MainViewModel",
                    "Found $strandedPositions trades under the anonymous key but this user " +
                        "already has $existing of their own; leaving the stranded rows alone."
                )
                return
            }
            db.simulationDao().reassignUser(ANONYMOUS_USER_ID, userId)
            android.util.Log.w(
                "MainViewModel",
                "Reclaimed $strandedPositions trades and $strandedSessions sessions that had " +
                    "been written under the anonymous key."
            )
        }.onFailure { error ->
            android.util.Log.w("MainViewModel", "Could not reclaim stranded history", error)
        }
    }

    /**
     * Mirror settled trades upward as they land.     *
     * Driven off the positions flow rather than a hook in the trading engine, so the upload is
     * idempotent and the engine needs no knowledge of the cloud. The flow re-emits on every insert;
     * each pass only picks up rows still flagged unsynced, so re-emitting costs one cheap query.
     */
    private fun observeTradesForCloudSync() {
        viewModelScope.launch {
            // No `drop(1)`. The first emission is the backlog already in Room — trades taken before
            // this launch, or before the app was ever signed in — and those are exactly the ones
            // need uploading. Dropping it would leave them stranded until some later insert happened
            // to wake the collector. Harmless to re-check on every emission: the drain only selects
            // rows still flagged unsynced, so a repeated pass costs one indexed query.
            pastPositions.collect { flushTradeHistory() }
        }
    }

    /**
     * Keeps both account balances current for as long as the ViewModel is alive.
     *
     * The active account's balance is pushed by its trading socket, which covers the common case
     * exactly — it moves the instant money does. This loop covers what a push cannot: the account
     * that is not selected (it has no socket), and any period when the socket is disconnected,
     * whether backgrounded or reconnecting.
     *
     * The interval is a deliberate compromise. Poll hard enough that a figure on screen is never
     * more than one interval stale, and slowly enough not to hammer the API for a number that is
     * usually already live — [DerivClient.refreshBalances] leaves the active account alone while
     * its socket is up, so most ticks of this loop are a single account list fetch.
     */
    private fun keepBalancesFresh() {
        viewModelScope.launch {
            while (isActive) {
                delay(BALANCE_REFRESH_MS)
                deriv.refreshBalances()
            }
        }
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
        val uid = currentUserId
        runCatching {
            db.userProfileDao().insertOrUpdateProfile(
                UserProfileEntity(
                    id = uid,
                    displayName = oauth.currentUser()?.displayName.orEmpty()
                )
            )
        }.onFailure { error ->
            android.util.Log.w("MainViewModel", "Could not ensure profile for $uid", error)
        }
    }

    /**
     * Sets the trader's display name.
     *
     * Needed because Deriv does not always return one — an account can sign in with a blank
     * name, and the app then shows a bare account number everywhere the name would go. The
     * name is stored on the profile row, and `UPDATE` only, so it can never take the trade
     * and session history that hangs off that row.
     */
    fun saveProfileDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            db.userProfileDao().updateDisplayName(currentUserId, trimmed)
        }
    }

    /**
     * When the signed-in identity changes, make sure its profile row exists, adopt that user's
     * session, load their settings, and bring the cloud session back. Covers cold start, fresh
     * login, and account switching.
     *
     * [restoreSessionState] runs here as well as in `init` because the two orderings race: `init`
     * fires before the stored identity is necessarily resolved, and on a fresh sign-in it never
     * runs at all. Without this, signing in after install would leave `_activeSessionId` null, and
     * every session-scoped query would return nothing — the empty-history symptom again, now on
     * exactly the path that was supposed to be fixed.
     */
    private fun observeAuthForProfileAndSync() {
        viewModelScope.launch {
            authState.collect { state ->
                if (state is AuthState.Authenticated) {
                    ensureProfile()
                    restoreSessionState()
                    loadPersistedSettings()
                    // If the person signed in already on this device (from a previous Deriv
                    // login), their TickEdge cloud session comes back with them.
                    restoreCloudSession()
                    // The strategy list is the first thing a signed-in user sees. It is where the
                    // app says what it can do, and where the live-account warning lives, so it is
                    // the right place to land on rather than the trading screen.
                    _currentScreen.value = "strategies"
                }
            }
        }
    }

    private suspend fun loadPersistedSettings() {
        val e = db.userSettingsDao().getSettings(currentUserId).first()
        if (e != null) {
            // The per-strategy blobs, one row per strategy. Read straight rather than re-deriving
            // from the legacy row: since the split, each strategy owns its own knobs.
            _strategySettings.update {
                it + (StrategyCatalog.DEFAULT.id to toTradeSettings(e)) +
                    (StrategyCatalog.EVEN_ODD.id to toTradeSettings(e))
            }
            _systemConfig.value = toSystemConfig(e)
            _realArmed.value = e.realArmed
            _requirePerOrderConfirm.value = e.requirePerOrderConfirm
            _maxMartingaleSteps.value = e.maxMartingaleSteps
            // The live-risk acknowledgement is the person's own decision, so it comes back with
            // their settings: a restart must not re-arm live silently, and must not re-ask
            // someone who already answered.
            _liveRiskAcknowledged.value = e.liveRiskAcknowledged
            // The strategy the user last ran. `find` falls back to the default when the stored id
            // names a strategy that no longer exists, so a renamed or removed strategy leaves the
            // user on a working one rather than on nothing.
            _selectedStrategyId.value = StrategyCatalog.find(e.selectedStrategyId).id
        }

        // The real source is the per-strategy table, seeded from the legacy row by the 15→16
        // migration. Any strategy without a row yet (first save on this device after the split,
        // or a legacy user whose profile predates the migration's guard) falls back to the
        // legacy mapping, which is exactly what the migration did.
        val rows = db.strategySettingsDao().getForUser(currentUserId)
        val legacyBlobs = if (e != null) {
            StrategyCatalog.ALL.associate { def -> def.id to toTradeSettings(e) }
        } else {
            emptyMap()
        }
        _strategySettings.value = StrategyCatalog.ALL.associate { def ->
            val row = rows.firstOrNull { it.strategyId == def.id }
            def.id to (row?.let(::fromStrategySettings) ?: legacyBlobs[def.id] ?: TradeSettings())
        }

        // Applied outside the null check, and that is the fix. This used to sit inside `e?.let`,
        // so on a fresh install — or on any user id with no row yet, which includes every first
        // sign-in — the socket was never told a window and kept its own fallback while the settings
        // screen, the database and the cloud all reported a different one. The analysis then ran
        // over whatever the service happened to be holding, which is how a configured window could
        // read as 30 on screen.
        webSocketService.setWindowSize(activeSettings.analysisWindowSize)
    }

    private fun observeAnalysisThresholds() {
        viewModelScope.launch {
            marketsState.collect { markets ->
                val strategyId = activeStrategyId()
                val currentSettings = activeSettings
                val evenOdd = strategyId == StrategyCatalog.EVEN_ODD.id
                markets.values.forEach { state ->
                    if (evenOdd) {
                        observeParityAnalysis(state, currentSettings)
                    } else {
                        observeDiffersAnalysis(state, currentSettings)
                    }
                }
            }
        }
    }

    /**
     * The Even/Odd entry read: which class the strategy would trade on this market's window,
     * and whether that class' parity percentage clears the entry condition.
     */
    private fun observeParityAnalysis(
        state: MarketState,
        currentSettings: TradeSettings
    ) {
        if (state.sampleSize < MIN_ANALYSIS_SAMPLE) return
        val classSentinel = resolveParityClass(
            state.digitFrequencies,
            currentSettings.evenOddMode,
            currentSettings.evenOddOperator
        )
        val observedPercent = DigitAnalysisEngine.parityPercentFor(
            state.digitFrequencies,
            classSentinel == PARITY_EVEN_SENTINEL
        )
        val met = DigitAnalysisEngine.evaluateCondition(
            observedPercent = observedPercent,
            thresholdPercent = currentSettings.evenOddThreshold,
            operator = currentSettings.evenOddOperator,
            sampleSize = state.sampleSize,
            minimumSampleSize = MIN_ANALYSIS_SAMPLE
        )
        if (met) {
            val evenCount = state.digitFrequencies.firstOrNull { it.digit % 2 == 0 }?.count ?: 0
            val oddCount = state.digitFrequencies.firstOrNull { it.digit % 2 == 1 }?.count ?: 0
            recordAnalysisEvent(
                symbol = state.symbol,
                targetDigit = classSentinel,
                windowSize = state.configuredWindowSize,
                sampleSize = state.sampleSize,
                occurrenceCount = if (classSentinel == PARITY_EVEN_SENTINEL) evenCount else oddCount,
                observedPercent = observedPercent,
                metric = AnalysisMetric.EVEN_ODD_PERCENT,
                operator = currentSettings.evenOddOperator,
                thresholdPercent = currentSettings.evenOddThreshold
            )
        }
    }

    /** The Differs entry read: P(≠ target digit) against the entry threshold. */
    private fun observeDiffersAnalysis(
        state: MarketState,
        currentSettings: TradeSettings
    ) {
        val digitFreq = state.digitFrequencies.firstOrNull { it.digit == currentSettings.targetDigit }
        if (digitFreq != null && state.sampleSize >= MIN_ANALYSIS_SAMPLE) {
            // Deliberately the Differs condition only. This is a market-analysis feed, not a trade
            // log, and it always measures P(≠ digit) — the metric the Analysis screen
            // shows. Reading `conditionFor(isRecovery = true)` here would compare a
            // ~50%-scale Over/Under reading against the recovery threshold while still
            // labelling the row `differs_pct`, which is exactly the disagreement this
            // separation exists to prevent. Recovery decisions are logged by the engine
            // at the point the trade is placed.
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
        // Moving to the live account is the one switch that spends real money, so it always
        // goes through the confirmation dialog — every time, not just the first. A user who
        // switches demo→live→demo→live has re-entered the risk each time, and each re-entry
        // is a fresh decision. Moving back to demo has no risk to confirm, so it is immediate.
        if (type == AccountType.LIVE && _activeAccountType.value == AccountType.DEMO) {
            _liveRiskDialog.value = true
            return
        }
        applyAccountType(type)
    }

    /**
     * Points the app at an account and re-scopes everything that reads account-scoped history.
     *
     * The re-scope is the part that was missing. Switching accounts moved the money but left the
     * History page showing the *other* account's trades and sessions, complete with that account's
     * demo/live badges — so the user switched to live and kept looking at a demo record. `restoreSessionState`
     * also re-reads the session header, so the P&L above the tabs switches with the lists below it.
     */
    private fun applyAccountType(type: AccountType) {
        if (_activeAccountType.value == type) return
        _activeAccountType.value = type
        viewModelScope.launch {
            deriv.setAccount(type.toDerivKind())
            db.userProfileDao().updateActiveAccount(currentUserId, type.name)
            restoreSessionState()
        }
    }

    fun switchStatsPeriod(period: String) { _statsPeriod.value = period }
    fun selectMarket(symbol: String) { _selectedMarketSymbol.value = symbol }
    fun setInspectedDigit(digit: Int?) { _inspectedDigit.value = digit }
    /**
     * Change the analysis window for the strategy on screen. Mirrored into that strategy's
     * settings blob so the Analysis screen's +/- control and the Trade Settings field cannot
     * drift apart, and persisted so it survives a restart. The socket takes the active
     * strategy's window; a second strategy running at the same time re-evaluates on its next
     * sweep with the new window — the UI only trades one at a time.
     */
    fun setWindowSize(size: Int) {
        val bounded = size.coerceIn(MIN_ANALYSIS_WINDOW, MAX_ANALYSIS_WINDOW)
        webSocketService.setWindowSize(bounded)
        if (activeSettings.analysisWindowSize != bounded) {
            setStrategySettings(activeSettings.copy(analysisWindowSize = bounded))
            persistSettings()
        }
    }

    fun updateTradeSettings(settings: TradeSettings) {
        val previous = activeSettings
        setStrategySettings(settings)
        // The Trade Settings screen edits the tick window, but only ever persisted it — it never
        // told the socket. So the value was saved, shown and synced while the analysis kept running
        // over the previous window until the next cold start, and the two screens disagreed about
        // the same setting. Applied on change, alongside the dedicated setWindowSize() above.
        if (settings.analysisWindowSize != previous.analysisWindowSize) {
            webSocketService.setWindowSize(settings.analysisWindowSize)
        }
        persistSettings()
    }

    fun updateSystemConfig(config: SystemConfig) {
        _systemConfig.value = config
        persistSettings()
    }

    /**
     * Arm or disarm live trading — **and** move the active account with it.
     *
     * These used to be one switch and two effects, and only the first was implemented: flipping the
     * toggle changed a flag while the active account stayed on Demo. The engine's gate then refused
     * the order, so the control appeared completely inert — arming it did nothing and disarming it
     * did nothing, because the flag was never the thing being tested in the first place. The user
     * has to be able to say "live money" and have the account actually be live.
     *
     * The coupling is deliberate and is the point of the control: arming real money while trading
     * the demo account could only ever mean the user believed they were on live when they were not,
     * or vice versa. One control, one meaning — no ambiguous combination.
     *
     * If the account being switched to does not exist, the toggle is rolled back rather than left
     * in a state that disagrees with where the money is.
     */
    fun setRealArmed(v: Boolean) {
        if (!v) {
            _realArmed.value = false
            persistSettings()
            viewModelScope.launch { applyAccountType(AccountType.DEMO) }
            return
        }
        // Arming live is a one-time acknowledgement, not a per-order one. A dialog on every order
        // is not a safety control — a user who has already accepted the risk learns to dismiss it
        // without reading it, so every later order pays the cost and none of them get the
        // protection. Confirming once, at the moment the account changes, is the same information
        // arriving somewhere it can still be read.
        if (!liveRiskAcknowledged.value) {
            _liveRiskDialog.value = true
            return
        }
        armLive()
    }

    /** Called when the user accepts the one-time live-account warning. */
    fun acceptLiveRisk() {
        _liveRiskDialog.value = false
        _liveRiskAcknowledged.value = true
        armLive()
    }

    /** Called when the user declines. Nothing is armed and the account is left on demo. */
    fun declineLiveRisk() {
        _liveRiskDialog.value = false
    }

    private fun armLive() {
        _realArmed.value = true
        persistSettings()
        viewModelScope.launch {
            if (deriv.accounts.value.none { it.kind == DerivAccountKind.REAL }) {
                // No real account on this Deriv login. Leave the user on demo rather than in a
                // state where "live armed" is set but the money is somewhere else.
                _realArmed.value = false
                persistSettings()
                return@launch
            }
            applyAccountType(AccountType.LIVE)
        }
    }

    /**
     * What to tell the user about their balance *before* they start trading, or null when there is
     * nothing to say.
     *
     * Checked up front rather than at order time because the discovery otherwise arrives as a
     * failed trade. Three cases, in the order they matter:
     *
     *  - not enough to place even the base stake. Nothing will trade at all.
     *  - enough for the base stake but not for the ladder. This is the one that bites: the first
     *    trade goes in fine and the bot looks healthy, and then a recovery rung needs several times
     *    the balance and the ladder cannot continue to chase the loss it just took. Saying so
     *    before Start is the difference between a decision and a surprise.
     *  - enough for the ladder as configured.
     */
    val preflightWarning: StateFlow<String?> = combine(
        activeAccount,
        tradeSettings,
        _maxMartingaleSteps
    ) { account, settings, maxSteps ->
        val balance = account?.balance ?: return@combine null
        val stake = settings.stakeAmount
        val isParity = _selectedStrategyId.value == StrategyCatalog.EVEN_ODD.id
        // The parity ladder sizes on a flat coin-flip probability — its rungs have no barrier
        // digit, so an Over/Under win probability here would under- or over-size the stakes.
        val winProbability = if (isParity) {
            RecoveryCalculator.evenOddWinProbability()
        } else {
            RecoveryCalculator.overUnderWinProbability(settings.recoveryDigit, true)
        }
        val base = RecoveryCalculator.payoutRatioFor(winProbability)

        // The largest rung the ladder could ask for, at the settings' own target.
        val worstCase = if (settings.martingaleEnabled && maxSteps > 0) {
            RecoveryCalculator.plan(
                baseStake = stake,
                lossesToRecover = stake,
                winProbability = winProbability,
                maxSteps = maxSteps,
                maxTotalRisk = Double.MAX_VALUE,
                livePayoutRatio = base
            ).steps.maxOfOrNull { it.stake } ?: stake
        } else {
            stake
        }

        when {
            balance < stake + STAKE_HEADROOM ->
                "Not enough balance to start. The first trade needs " +
                    "${money(stake)} but the account holds ${money(balance)}."

            worstCase > balance + STAKE_HEADROOM ->
                "Not enough balance for the recovery ladder. Starting costs ${money(stake)}, " +
                    "which you have, but a recovery rung can reach ${money(worstCase)} and the " +
                    "account holds ${money(balance)}. A losing streak would strand the loss " +
                    "unrecovered."

            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun money(amount: Double): String = String.format(java.util.Locale.US, "$%.2f", amount)

    /** True once the user has accepted that live trading risks real money, for this app session. */
    private val _liveRiskAcknowledged = MutableStateFlow(false)
    val liveRiskAcknowledged: StateFlow<Boolean> = _liveRiskAcknowledged.asStateFlow()

    /** Non-null while the one-time live-account warning is on screen. */
    private val _liveRiskDialog = MutableStateFlow(false)
    val liveRiskDialog: StateFlow<Boolean> = _liveRiskDialog.asStateFlow()

    fun dismissLiveRiskDialog() {
        _liveRiskDialog.value = false
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
        // The cloud copy used to be written only at sign-in, so every settings edit made after
        // that was device-local forever and a reinstall lost it. Mirroring here is what makes the
        // remote copy real.
        scheduleCloudPush()

        viewModelScope.launch(Dispatchers.IO) {
            // One complete blob per strategy: editing a strategy's stake can never re-tune the
            // other one any more.
            _strategySettings.value.forEach { (strategyId, blob) ->
                db.strategySettingsDao().upsert(toStrategySettingsEntity(strategyId, blob))
            }
            val differsBlob = _strategySettings.value[StrategyCatalog.DEFAULT.id] ?: TradeSettings()
            val parityBlob = _strategySettings.value[StrategyCatalog.EVEN_ODD.id] ?: TradeSettings()
            val c = _systemConfig.value
            // The legacy row still holds the globally shared settings. Its trade-knob columns are
            // written from the Differs blob for compatibility with older cloud reads, but they are
            // no longer the source of any strategy's settings — the `strategy_settings` rows are.
            db.userSettingsDao().saveSettings(
                UserSettingsEntity(
                    id = 1L,
                    userId = currentUserId,
                    windowSize = differsBlob.analysisWindowSize,
                    selectedDigit = differsBlob.targetDigit,
                    recoveryDigit = differsBlob.recoveryDigit,
                    recoveryDirection = differsBlob.recoveryDirection,
                    comparisonOperator = differsBlob.comparisonOperator.symbol,
                    thresholdPercent = differsBlob.thresholdPercent,
                    recoveryComparisonOperator = differsBlob.recoveryComparisonOperator.symbol,
                    recoveryThresholdPercent = differsBlob.recoveryThresholdPercent,
                    analysisMetric = differsBlob.analysisMetric.symbol,
                    digitSelectionMode = differsBlob.digitSelectionMode.symbol,
                    minimumSampleSize = MIN_ANALYSIS_SAMPLE,
                    stakeAmount = differsBlob.stakeAmount,
                    takeProfit = differsBlob.takeProfit,
                    stopLoss = differsBlob.stopLoss,
                    durationUnit = differsBlob.durationUnit.label,
                    durationValue = differsBlob.durationValue,
                    martingaleEnabled = differsBlob.martingaleEnabled,
                    tradeType = "Differs",
                    randomMarkets = c.randomMarkets,
                    maxTradesPerMarket = c.maxTradesPerMarket,
                    maxDailyLoss = c.maxDailyLoss,
                    stopTradingOnLimit = c.stopTradingOnLimit,
                    realArmed = _realArmed.value,
                    requirePerOrderConfirm = _requirePerOrderConfirm.value,
                    maxMartingaleSteps = _maxMartingaleSteps.value,
                    selectedStrategyId = _selectedStrategyId.value,
                    liveRiskAcknowledged = _liveRiskAcknowledged.value,
                    evenOddMode = parityBlob.evenOddMode.symbol,
                    evenOddOperator = parityBlob.evenOddOperator.symbol,
                    evenOddThreshold = parityBlob.evenOddThreshold
                )
            )
        }
    }

    fun navigateTo(screen: String) {
        // A route the current strategy does not offer is not reachable. Landing on one would show
        // a screen belonging to a strategy the user has left. `trade_settings` is the exception:
        // it is a full page that replaces the Trade route (not a bottom-bar tab), so the strategy
        // owns it even though it never lists it as a menu item.
        if (screen != "strategies" && screen != "trade_settings" &&
            !StrategyCatalog.ownsRoute(selectedStrategy.value, screen)
        ) {
            _currentScreen.value = selectedStrategy.value.defaultRoute
            return
        }
        _currentScreen.value = screen
    }

    fun startTrading() {
        // A double Start would open two sessions. `ensureSessionStarted` closes any ACTIVE session
        // it finds before opening a new one, so the second press would close the session the first
        // had just opened and orphan it — the trades of the live run would then be written under a
        // session marked closed. One run, one session.
        if (startingRun) return
        startingRun = true

        // The engine persists positions itself, so both its foreign-key parent and the session
        // must exist before the first trade. The engine start is therefore INSIDE the coroutine:
        // launching the setup separately and starting the engine synchronously raced, so the first
        // trade could be written before the session row existed and land under NO_SESSION, making it
        // invisible to every session-scoped query.
        viewModelScope.launch {
            try {
                ensureProfile()
                // Session bookkeeping must never gate trading. This runs inside the same coroutine as
                // startTrading(), so an unhandled throw here would kill the coroutine and the engine
                // would never start — the UI would look like Start was pressed while nothing traded.
                // Positions written without a session still land in the user-scoped history.
                runCatching { ensureSessionStarted() }.onFailure { error ->
                    android.util.Log.e("MainViewModel", "Could not open a trading session; continuing without one", error)
                }
                // The foreground service starts *before* the engine, not after. Starting it later
                // would leave a window where the OS could freeze the app with a contract already
                // open — and a frozen app does not see the settlement.
                TradingService.start(getApplication())
                tradingEngine.startTrading(
                    settings = activeSettings,
                    config = _systemConfig.value,
                    getMarketState = { symbol -> marketsState.value[symbol] },
                    safety = TradingEngine.TradingSafety(
                        activeKind = { _activeAccountType.value.toDerivKind() },
                        isRealArmed = { _realArmed.value },
                        requirePerOrderConfirm = { _requirePerOrderConfirm.value }
                    )
                )
            } finally {
                startingRun = false
            }
        }
    }

    /**
     * Stop trading.
     *
     * The session is closed by the engine's `onRunEnded`, not here. Closing it from both places
     * used to race the open contract: pressing Stop with a contract in flight deferred the run, and
     * this second call closed the session immediately — so the trade that settled a moment later
     * was written against an already-ended session and its results were missing from the totals.
     */
    fun stopTrading() {
        tradingEngine.stopTrading()
    }

    /**
     * Stops every strategy that is running, not just the one on screen.
     *
     * The Stop button on the Trade page stops the strategy the user is looking at, because that is
     * the one they can see. This is the other thing: everything, at once.
     */
    fun stopAllTrading() {
        runtime.stopAllEngines()
    }

    /**
     * Every strategy's live figures.
     *
     * Read from the runtime rather than kept here, because the notification and the strategy list
     * have to describe the same run — and the notification outlives this ViewModel.
     */
    val runStates: StateFlow<Map<String, StrategyRunState>> = runtime.runStates
    fun resolveConfirmation(approved: Boolean) { tradingEngine.resolvePendingConfirmation(approved) }
    fun dismissOutcomeBanner() { tradingEngine.dismissOutcomeBanner() }

    /**
     * Upload any trades still waiting to reach the cloud.
     *
     * Called after a trade settles so history is mirrored as it happens, rather than sitting in the
     * outbox until the next sign-in. A no-op without a FIDScript session, in which case the rows
     * stay flagged and go up whenever a session is next established.
     */
    fun flushTradeHistory() {
        val fidUserId = fidscriptSession.userId() ?: return
        if (!fidscript.isReady()) return
        viewModelScope.launch(Dispatchers.IO) { drainTradeHistoryToCloud(fidUserId) }
    }

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
            var removed = emptyList<SimulationPositionEntity>()
            if (accountId.isBlank()) {
                // No account resolved: refuse rather than falling back to a user-wide delete,
                // which would remove more than the user was told would be removed.
                android.util.Log.w("MainViewModel", "clearHistory ignored: no active account id")
            } else {
                // Collected before the delete so the same rows can be removed from the cloud too.
                // Leaving them there would let the next sync restore exactly what was just cleared.
                removed = db.simulationDao()
                    .getPositionsForUserAndAccount(currentUserId, accountId)
                db.simulationDao().clearPositionsForUserAndAccount(currentUserId, accountId)
            }
            db.analysisEventDao().clearEventsForUser(currentUserId)
            tradingEngine.resetDailyLoss()
            deleteTradesFromCloud(removed)
        }
    }

    /**
     * Remove cleared trades from the cloud.
     *
     * Without this, clearing history is cosmetic: the rows are gone locally but the next pull
     * brings them straight back. Best-effort — a failure here leaves stale rows in the cloud, which
     * is recoverable, whereas blocking the local clear would leave the user unable to clear at all.
     */
    private suspend fun deleteTradesFromCloud(positions: List<SimulationPositionEntity>) {
        if (!fidscript.isReady() || positions.isEmpty()) return
        positions.forEach { position ->
            if (position.contractId.isNotBlank()) {
                fidscript.deleteEntity(InstantDbClient.tradeEntityId(position.contractId))
            }
        }
    }

    fun refreshAccounts() {
        viewModelScope.launch { deriv.refreshBalances() }
    }

    /** True while a manual sync is running, so the button can spin instead of looking inert. */
    val isSyncing = deriv.isSyncing

    /**
     * Manual sync, bound to the sync control on the home and trade screens.
     *
     * Re-opens any socket that has dropped and re-reads every balance — demo and real alike. This
     * is a genuine repair, not just a re-read: if the socket went away the REST figure alone cannot
     * bring the account back to live.
     */
    fun syncBalances() {
        viewModelScope.launch { deriv.syncNow() }
    }

    override fun onCleared() {
        super.onCleared()
        // Nothing is torn down here.
        //
        // The trading engine is deliberately NOT stopped: this used to stop it, which meant swiping
        // the app away from recents ended the run — the stake had already left the account, the
        // contract was still open, and nothing was ever written to the history. That is the exact
        // failure the foreground service exists to prevent. The engine lives in the
        // [TradingRuntime], so it survives this; it ends when the user stops it, when a risk limit
        // is reached, or when the session is closed.
        //
        // The market-data socket is not disconnected either. It used to be, and that was equally
        // fatal: `disconnect()` sets `manualClosed`, so the socket never reconnects, and the engine
        // — which reads the market state from it to decide whether to enter — goes quiet. The run
        // would carry on but never take another trade, with no error anywhere to explain it.
        //
        // `deriv` is not disposed here for the same reason: the engine still needs it to settle
        // whatever is open. It is disposed on sign-out, which is the one moment it is safe.
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
        recoveryComparisonOperator = ComparisonOperator.values()
            .firstOrNull { it.symbol == e.recoveryComparisonOperator }
            ?: ComparisonOperator.GREATER_THAN,
        recoveryThresholdPercent = e.recoveryThresholdPercent,
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
        martingaleEnabled = e.martingaleEnabled,
        evenOddMode = EvenOddMode.values()
            .firstOrNull { it.symbol == e.evenOddMode } ?: EvenOddMode.AUTO,
        evenOddOperator = ComparisonOperator.values()
            .firstOrNull { it.symbol == e.evenOddOperator } ?: ComparisonOperator.GREATER_THAN,
        evenOddThreshold = e.evenOddThreshold
    )

    private fun toSystemConfig(e: UserSettingsEntity): SystemConfig = SystemConfig(
        randomMarkets = e.randomMarkets,
        maxTradesPerMarket = e.maxTradesPerMarket,
        maxDailyLoss = e.maxDailyLoss,
        stopTradingOnLimit = e.stopTradingOnLimit
    )

    /**
     * One row of `strategy_settings` back to a [TradeSettings] blob. Every read is defensive —
     * a symbol a rename could have broken falls back to the blob's default rather than stranding
     * the user with an unreadable strategy.
     */
    private fun fromStrategySettings(e: StrategySettingsEntity): TradeSettings = TradeSettings(
        targetDigit = e.differsDigit,
        digitSelectionMode = DigitSelectionMode.values()
            .firstOrNull { it.symbol == e.differsDigitMode } ?: DigitSelectionMode.RANDOM,
        recoveryDirection = e.recoveryDirection,
        recoveryDigit = e.recoveryDigit,
        comparisonOperator = ComparisonOperator.values()
            .firstOrNull { it.symbol == e.differsOperator } ?: ComparisonOperator.LESS_THAN,
        thresholdPercent = e.differsThreshold,
        recoveryComparisonOperator = ComparisonOperator.values()
            .firstOrNull { it.symbol == e.recoveryOperator } ?: ComparisonOperator.GREATER_THAN,
        recoveryThresholdPercent = e.recoveryThreshold,
        analysisWindowSize = e.analysisWindow.coerceIn(MIN_ANALYSIS_WINDOW, MAX_ANALYSIS_WINDOW),
        stakeAmount = e.stakeAmount,
        takeProfit = e.takeProfit,
        stopLoss = e.stopLoss,
        durationUnit = TradeDurationUnit.values()
            .firstOrNull { it.label.equals(e.durationUnit, ignoreCase = true) } ?: TradeDurationUnit.TICKS,
        durationValue = e.durationValue,
        martingaleEnabled = e.martingaleEnabled,
        evenOddMode = EvenOddMode.values()
            .firstOrNull { it.symbol == e.evenOddMode } ?: EvenOddMode.AUTO,
        evenOddOperator = ComparisonOperator.values()
            .firstOrNull { it.symbol == e.evenOddOperator } ?: ComparisonOperator.GREATER_THAN,
        evenOddThreshold = e.evenOddThreshold
    )

    /** A [TradeSettings] blob into one row of `strategy_settings`. */
    private fun toStrategySettingsEntity(strategyId: String, s: TradeSettings): StrategySettingsEntity =
        StrategySettingsEntity(
            userId = currentUserId,
            strategyId = strategyId,
            differsDigit = s.targetDigit,
            differsDigitMode = s.digitSelectionMode.symbol,
            differsOperator = s.comparisonOperator.symbol,
            differsThreshold = s.thresholdPercent,
            recoveryDirection = s.recoveryDirection,
            recoveryDigit = s.recoveryDigit,
            recoveryOperator = s.recoveryComparisonOperator.symbol,
            recoveryThreshold = s.recoveryThresholdPercent,
            evenOddMode = s.evenOddMode.symbol,
            evenOddOperator = s.evenOddOperator.symbol,
            evenOddThreshold = s.evenOddThreshold,
            stakeAmount = s.stakeAmount,
            takeProfit = s.takeProfit,
            stopLoss = s.stopLoss,
            durationUnit = s.durationUnit.label,
            durationValue = s.durationValue,
            martingaleEnabled = s.martingaleEnabled,
            analysisWindow = s.analysisWindowSize,
            updatedAt = System.currentTimeMillis()
        )
}
