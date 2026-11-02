package com.tickedge.domain

import android.app.Application
import com.tickedge.BuildConfig
import com.tickedge.auth.DerivOAuthClient
import com.tickedge.data.deriv.DerivClient
import com.tickedge.data.instant.FidscriptAttrs
import com.tickedge.data.instant.FidscriptSession
import com.tickedge.data.instant.InstantDbClient
import com.tickedge.data.local.AppDatabase
import com.tickedge.data.local.CloudSessionEntity
import com.tickedge.data.local.SimulationSessionEntity
import com.tickedge.data.websocket.DerivWebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The trading engines, and the sessions they belong to, owned by the application rather than by a
 * screen.
 *
 * This exists because a trading bot that stops when you switch apps is not a trading bot. The
 * engine used to live in the ViewModel, which meant:
 *
 *  - its coroutines ran in `viewModelScope`, so they died with the Activity;
 *  - swiping the app away from recents destroyed the Activity, which cleared the ViewModel, which
 *    cancelled the loop mid-trade — the stake had left the account and nothing was recorded;
 *  - and the app had no foreground service, so the OS was free to freeze it in the background and
 *    then kill it.
 *
 * Moving the engine here fixes all three at once. The scope is application-scoped, so the loop
 * survives the Activity; the foreground service keeps the process alive and visible; and the
 * session belongs to the runtime, so it is closed by whatever ends the run — screen, service, or
 * process death — rather than by a button.
 *
 * There is **one engine per strategy**, not one engine for the app. That is what lets two
 * strategies trade at the same time without interfering: each has its own market rotation, its own
 * cool-offs, its own losing streak and its own session. It is also what lets the notification
 * describe every strategy separately instead of a single blended figure.
 *
 * One instance per process. Created lazily so it is never built before the application is.
 */
class TradingRuntime private constructor(
    private val application: Application,
    /** The scope every trading coroutine runs in. */
    val scope: CoroutineScope,
    val db: AppDatabase,
    val oauth: DerivOAuthClient,
    val deriv: DerivClient,
    val webSocketService: DerivWebSocketService
) {

    /** The user whose rows the engine writes. Set by the ViewModel as identity resolves. */
    @Volatile
    var userId: String = "default_trader"

    /**
     * The engine for each strategy, created on first use.
     *
     * Concurrent because the UI may ask for an engine on the main thread while a background
     * thread starts a run for another strategy.
     */
    private val engines = ConcurrentHashMap<String, TradingEngine>()

    /** The session each strategy's run is writing into. */
    private val sessionIds = ConcurrentHashMap<String, Long>()

    /** The open session for each strategy. */
    private val openSessions = ConcurrentHashMap<String, SimulationSessionEntity>()

    /**
     * Every strategy's published figures.
     *
     * Held here, not in the ViewModel, because the notification has to survive the Activity: a
     * registry that lived in a ViewModel would be empty the moment the app was backgrounded, which
     * is exactly when the notification matters most.
     */
    private val runRegistry = StrategyRunRegistry()
    val runStates: StateFlow<Map<String, StrategyRunState>> = runRegistry.states

    /** Posts the alerts — the moments a run ends because a limit was reached. */
    private val notifier = TradingNotifier(application)

    /** The FIDScript client, lazily — it needs the cloud session, which may not exist yet. */
    private val fidscriptSession = FidscriptSession(application)
    private val cloudClient = InstantDbClient(
        appId = BuildConfig.INSTANT_APP_ID,
        apiBase = BuildConfig.INSTANT_API_BASE,
        session = fidscriptSession
    )

    /**
     * The last `stopRequestedAt` this device already acted on. A stop older than the last one seen
     * is not re-fired: two devices must not keep stopping each other in a loop over a stale flag.
     */
    @Volatile
    private var lastSeenStopAt: Long = 0L

    /**
     * Whether the two cloud loops are already running. They are started lazily on cloud sign-in,
     * which can happen more than once for a session (a re-verify), so each is guarded against a
     * second, competing loop rather than started every time.
     */
    @Volatile
    private var cloudStopWatchStarted = false
    @Volatile
    private var sessionProgressMirrorStarted = false
    @Volatile
    private var cloudSessionPullStarted = false

    // ---------------------------------------------------------- cloud session mirror

    /**
     * Publish an open session to the cloud so a sibling device sees it go live.
     *
     * No-op when there is no cloud session — the whole mirror is optional, and a device that is not
     * signed in to the cloud still trades normally; it just does not share its record.
     */
    fun mirrorSessionOpen(session: SimulationSessionEntity) {
        if (!cloudClient.isReady()) return
        val fidUserId = fidscriptSession.userId() ?: return
        val id = session.id
        scope.launch(Dispatchers.IO) {
            cloudClient.upsertSession(
                userId = fidUserId,
                entityId = InstantDbClient.sessionEntityId(session),
                fields = mapOf(
                    "userId" to fidUserId,
                    "sessionId" to id,
                    "strategyId" to session.strategyId,
                    "strategy" to session.strategy,
                    "isReal" to session.isReal,
                    "accountId" to session.accountId,
                    "status" to session.status,
                    "startedAt" to session.startedAt,
                    "initialSimulatedBalance" to session.initialSimulatedBalance,
                    "currentSimulatedBalance" to session.currentSimulatedBalance,
                    "updatedAt" to System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Re-write a running session's live totals so a sibling sees the P&L moving, not just "open".
     */
    suspend fun mirrorSessionProgress(strategyId: String) {
        if (!cloudClient.isReady()) return
        val session = openSessions[strategyId] ?: return
        val fidUserId = fidscriptSession.userId() ?: return
        val summary = db.simulationDao().getSessionSummary(session.userId, session.id).first()
        cloudClient.upsertSession(
            userId = fidUserId,
            entityId = InstantDbClient.sessionEntityId(session),
            fields = mapOf(
                "userId" to fidUserId,
                "sessionId" to session.id,
                "status" to "ACTIVE",
                "totalTrades" to summary.completedTrades,
                "winCount" to summary.winCount,
                "lossCount" to summary.lossCount,
                "netProfit" to summary.totalRealizedPnl,
                "grossProfit" to summary.grossProfit,
                "grossLoss" to summary.grossLoss,
                "totalStaked" to summary.totalStaked,
                "updatedAt" to System.currentTimeMillis()
            )
        )
    }

    /** Publish a session's COMPLETED row, so a sibling sees it finished with its final figures. */
    suspend fun mirrorSessionClosed(strategyId: String, session: SimulationSessionEntity) {
        if (!cloudClient.isReady()) return
        val fidUserId = fidscriptSession.userId() ?: return
        val summary = db.simulationDao().getSessionSummary(session.userId, session.id).first()
        cloudClient.upsertSession(
            userId = fidUserId,
            entityId = InstantDbClient.sessionEntityId(session),
            fields = mapOf(
                "userId" to fidUserId,
                "sessionId" to session.id,
                "status" to "COMPLETED",
                "endedAt" to System.currentTimeMillis(),
                "totalTrades" to summary.completedTrades,
                "winCount" to summary.winCount,
                "lossCount" to summary.lossCount,
                "netProfit" to summary.totalRealizedPnl,
                "grossProfit" to summary.grossProfit,
                "grossLoss" to summary.grossLoss,
                "totalStaked" to summary.totalStaked,
                "updatedAt" to System.currentTimeMillis()
            )
        )
    }

    /**
     * Keep a running session's live totals in the cloud so a sibling sees the P&L move.
     *
     * Mirrors every open session's SQL totals every [CLOUD_PROGRESS_POLL_MS], no-op when nothing is
     * open or there is no cloud session. This is what makes "the same session on two phones" a
     * moving figure on both rather than an open-with-nothing on the remote one.
     */
    fun startSessionProgressMirror() {
        if (sessionProgressMirrorStarted) return
        sessionProgressMirrorStarted = true
        scope.launch {
            while (true) {
                if (cloudClient.isReady() && fidscriptSession.userId() != null) {
                    sessionIds.keys.toList().forEach { strategyId ->
                        mirrorSessionProgress(strategyId)
                    }
                }
                delay(CLOUD_PROGRESS_POLL_MS)
            }
        }
    }

    /**
     * Pull this user's other-device sessions into Room, so the History Sessions list shows them.
     *
     * The write half of shared sessions (this device publishing its own run) needs a matching read
     * half, or a sibling sees only "a run went live and finished" — the open/close one-shots — and
     * never the live P&L between them, and on this device the remote run is absent from the history
     * entirely. This loop is that read half: every poll it reads the `sessions` namespace and
     * upserts rows the user does not already own locally, and the `sessions` flow then re-emits and
     * the UI follows. Same ~5 s period as the progress mirror, so a live remote run ticks on both.
     *
     * Started lazily like the other two (guarded, start-once) so a device that is signed in only
     * pays for the loop while it is actually signed in.
     */
    fun startCloudSessionPull() {
        if (cloudSessionPullStarted) return
        cloudSessionPullStarted = true
        scope.launch(Dispatchers.IO) {
            while (true) {
                pullCloudSessions()
                delay(CLOUD_PROGRESS_POLL_MS)
            }
        }
    }

    /** One poll of the `sessions` namespace into [com.tickedge.data.local.CloudSessionEntity] rows. */
    private suspend fun pullCloudSessions() {
        if (!cloudClient.isReady()) return
        val fidUserId = fidscriptSession.userId() ?: return
        val rows = cloudClient.readNamespace(FidscriptAttrs.NS_SESSIONS).getOrNull() ?: return

        // Leave out the sessions this device already owns locally — the pull's whole job is the
        // ones that came from a sibling. A session and its own published copy share the same cloud
        // key, so comparing keys separates "mine" from "theirs". A plain Room-id match is not
        // enough here: two devices of the same person can both hold a "session 1", so identity is
        // the key, not the id.
        val localKeys = db.simulationDao().getSessionsForUser(userId).first()
            .map { InstantDbClient.sessionEntityId(it) }.toSet()
        val dao = db.cloudSessionDao()
        for ((entityId, byAttr) in rows) {
            if (entityId in localKeys) continue
            val cloudSessionId = asLong(byAttr["sessionId"])
            if (cloudSessionId == 0L) continue

            dao.upsert(
                CloudSessionEntity(
                    cloudEntityId = entityId,
                    // Stored under the *local* user id, not the FIDScript one: the `sessions` flow
                    // that renders this table is scoped to the local id, exactly like the pulled
                    // trades. fidUserId is only who owns the cloud rows; locally they belong to the
                    // same person's profile.
                    userId = userId,
                    cloudSessionId = cloudSessionId,
                    accountId = byAttr["accountId"]?.toString().orEmpty(),
                    isReal = asBool(byAttr["isReal"]),
                    strategy = byAttr["strategy"]?.toString().orEmpty().ifEmpty { "Differs" },
                    strategyId = byAttr["strategyId"]?.toString().orEmpty(),
                    status = byAttr["status"]?.toString().orEmpty().ifEmpty { "ACTIVE" },
                    startedAt = asLong(byAttr["startedAt"]),
                    endedAt = asNullableLong(byAttr["endedAt"]),
                    totalTrades = asInt(byAttr["totalTrades"]),
                    winCount = asInt(byAttr["winCount"]),
                    lossCount = asInt(byAttr["lossCount"]),
                    netProfit = asDouble(byAttr["netProfit"]),
                    grossProfit = asDouble(byAttr["grossProfit"]),
                    grossLoss = asDouble(byAttr["grossLoss"]),
                    totalStaked = asDouble(byAttr["totalStaked"]),
                    initialSimulatedBalance = asDouble(byAttr["initialSimulatedBalance"]),
                    currentSimulatedBalance = asDouble(byAttr["currentSimulatedBalance"]),
                    updatedAt = asLong(byAttr["updatedAt"])
                )
            )
        }
    }

    // The cloud returns attribute values as their native JSON types, but the decode path can hand a
    // number back as a String; these keep the read tolerant rather than dropping a value it cannot
    // cast. `endedAt` is the one that is legitimately absent while a session is still ACTIVE, so it
    // has its own nullable flavour.
    private fun asLong(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        else -> v?.toString()?.toDoubleOrNull()?.toLong() ?: 0L
    }

    private fun asNullableLong(v: Any?): Long? = when (v) {
        null -> null
        is Number -> v.toLong()
        else -> v.toString().toDoubleOrNull()?.toLong()
    }

    private fun asInt(v: Any?): Int = asLong(v).toInt()

    private fun asDouble(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        else -> v?.toString()?.toDoubleOrNull() ?: 0.0
    }

    private fun asBool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toLong() != 0L
        else -> v?.toString()?.toBoolean() ?: false
    }

    /**
     * Tell every other signed-in device to stop trading, on the shared run-control channel.
     *
     * The stop is written to the cloud; each device's watch loop is what acts on it locally. This
     * is the "stop on one phone, stop on the other" — a device that only stops its own engine would
     * leave the sibling trading.
     */
    fun broadcastStop() {
        val fidUserId = fidscriptSession.userId() ?: return
        if (!cloudClient.isReady()) return
        scope.launch(Dispatchers.IO) {
            cloudClient.requestRunStop(fidUserId)
            // A stop we just asked for is one we have already seen; don't let our own watch re-fire.
            lastSeenStopAt = System.currentTimeMillis()
        }
    }

    /**
     * Watch the shared stop channel.
     *
     * Every [CLOUD_STOP_POLL_MS] the watch reads this user's `run_control` row and, when a
     * `stopRequested` is newer than the one already acted on, stops every engine on this device and
     * clears the flag so the next stop is the one that fires. Polling rather than a push subscription
     * keeps it on the same REST transport everything else already uses, and a ~5 s period is fast
     * enough that "stop on the other phone" lands within one poll, not minutes.
     *
     * Started lazily (not in `get()`) so a device with no cloud session pays nothing. Guarded so a
     * sign-in that fires the start twice (a re-verify on the same session) does not open a second,
     * competing watch.
     */
    fun startCloudStopWatch() {
        if (cloudStopWatchStarted) return
        cloudStopWatchStarted = true
        scope.launch {
            while (true) {
                val fidUserId = fidscriptSession.userId()
                if (fidUserId != null && cloudClient.isReady()) {
                    cloudClient.readNamespace(com.tickedge.data.instant.FidscriptAttrs.NS_RUN_CONTROL)
                        .onSuccess { rows ->
                            val row = rows[InstantDbClient.controlEntityId(fidUserId)]
                            if (row != null) {
                                val requested = (row["stopRequested"] as? Boolean) == true
                                val at = (row["stopRequestedAt"] as? Number)?.toLong() ?: 0L
                                if (requested && at > lastSeenStopAt) {
                                    lastSeenStopAt = at
                                    stopAllEngines()
                                    cloudClient.clearRunStop(fidUserId)
                                }
                            }
                        }
                }
                delay(CLOUD_STOP_POLL_MS)
            }
        }
    }


    /**
     * The engine for a strategy, created the first time it is asked for.
     *
     * Engines are cheap until they are started, so asking for one that will never run costs
     * nothing but a few fields.
     */
    fun engineFor(strategyId: String): TradingEngine =
        engines.getOrPut(strategyId) { createEngine(strategyId) }

    /** Every engine that has been created, for the notification and the strategy list. */
    fun engineForOrNull(strategyId: String): TradingEngine? = engines[strategyId]

    private fun createEngine(strategyId: String): TradingEngine {
        val engine = TradingEngine(
            deriv = deriv,
            database = db,
            scope = scope,
            userIdProvider = { userId },
            sessionIdProvider = { sessionIds[strategyId] ?: NO_SESSION },
            // Every exit from a run — the user's Stop, a stop loss, a daily limit, a blocked order —
            // lands here. The session is closed and the foreground service is torn down once no
            // strategy is left running, so the notification cannot outlive the trading it
            // describes.
            onRunEnded = {
                scope.launch { closeSession(strategyId) }
                stopServiceWhenNothingRuns()
            },
            strategyId = strategyId
        )

        // The engine's own state is the source of truth for what a run is doing. Copying it into
        // the registry is what lets one notification describe several strategies at once.
        scope.launch {
            engine.runState.collect { state ->
                runRegistry.put(state)
            }
        }

        // The alerts. The engine already decides *why* a run ended and words it; the runtime only
        // has to make sure the user is told, whether or not the app is in front of them.
        scope.launch {
            engine.stopNotice.collect { notice ->
                // A user's own Stop is not an alert — they pressed the button, they know.
                if (notice != null && notice.kind != StopKind.USER) {
                    notifier.postEvent(notice.title, notice.detail)
                }
            }
        }

        return engine
    }

    /** Opens a session for a strategy's run and publishes it. */
    fun openSession(strategyId: String, session: SimulationSessionEntity, openedId: Long) {
        val stored = session.copy(id = openedId)
        sessionIds[strategyId] = openedId
        openSessions[strategyId] = stored
        // Publish it so a sibling device sees the run go live — the record is shared, not per-phone.
        mirrorSessionOpen(stored)
    }

    /**
     * Closes the open session for a strategy and writes its figures.
     *
     * Idempotent, and safe to call from anywhere: the service stopping it, the user pressing
     * Stop, or the engine ending the run on its own. Only an ACTIVE session is closed, so a
     * finished run's `endedAt` is not overwritten by a later sign-out.
     */
    suspend fun closeSession(strategyId: String) {
        val id = sessionIds[strategyId] ?: return
        val session = openSessions[strategyId]
        if (session == null || session.status != "ACTIVE") {
            sessionIds.remove(strategyId)
            openSessions.remove(strategyId)
            return
        }

        writeSessionTotals(session)

        // The session just closed — publish its COMPLETED row so a sibling sees it finished.
        mirrorSessionClosed(strategyId, session)

        // The session row stays in the database — that is the history. Only the runtime's
        // reference to it is dropped.
        sessionIds.remove(strategyId)
        openSessions.remove(strategyId)
    }

    /**
     * Writes a session's final figures and marks it closed.
     *
     * Every total comes from SQL over that session's own settled trades rather than from a running
     * counter in the engine — a counter misses anything settled while the app was backgrounded and
     * dies with the process; the trades are the record.
     *
     * Public because a session left open by a run that never stopped has to be closed the same way,
     * with the same figures, when the next run begins.
     */
    suspend fun writeSessionTotals(session: SimulationSessionEntity) {
        val summary = db.simulationDao().getSessionSummary(userId, session.id).first()
        db.simulationDao().closeSession(
            sessionId = session.id,
            status = "COMPLETED",
            endedAt = System.currentTimeMillis(),
            totalTrades = summary?.completedTrades ?: 0,
            winCount = summary?.winCount ?: 0,
            lossCount = summary?.lossCount ?: 0,
            netProfit = summary?.totalRealizedPnl ?: 0.0,
            grossProfit = summary?.grossProfit ?: 0.0,
            grossLoss = summary?.grossLoss ?: 0.0,
            totalStaked = summary?.totalStaked ?: 0.0,
            // The live account balance, not a running sum. The difference between the two is
            // anything the account did outside this bot, and a session that hides that reads as
            // "the app took it" when it did not.
            endingBalance = deriv.activeAccount?.balance ?: session.currentSimulatedBalance
        )
    }

    /** The session id a strategy's positions are stamped with. */
    fun sessionIdFor(strategyId: String): Long = sessionIds[strategyId] ?: NO_SESSION

    /** Forgets the open session without closing it — used when signing out. */
    fun releaseSession(strategyId: String) {
        sessionIds.remove(strategyId)
        openSessions.remove(strategyId)
    }

    /** Forgets every open session — used when signing out. */
    fun releaseAllSessions() {
        sessionIds.clear()
        openSessions.clear()
    }

    /**
     * Closes every open session belonging to [userId], with its totals, on sign-out.
     *
     * `releaseAllSessions` forgets the in-memory reference; that is wrong on a sign-out, because
     * the session row stays ACTIVE with no totals, and the run's trades are orphaned on the page
     * that shows finished runs. Closing is the honest ending: each open session gets its SQL
     * figures and a COMPLETED status, so the history a person just signed out of is complete.
     * Only sessions that were actually opened (id present) are closed; the NO_SESSION placeholder
     * is never a real row.
     */
    suspend fun closeAllOpenSessionsForUser(userId: String) {
        sessionIds.keys.toList().forEach { strategyId ->
            val id = sessionIds[strategyId]
            if (id == null || id == NO_SESSION) {
                sessionIds.remove(strategyId)
                openSessions.remove(strategyId)
                return@forEach
            }
            val session = openSessions[strategyId] ?: return@forEach
            if (session.userId == userId && session.status == "ACTIVE") {
                writeSessionTotals(session)
            }
            sessionIds.remove(strategyId)
            openSessions.remove(strategyId)
        }
    }

    /** Whether any strategy is currently running. */
    fun isAnythingRunning(): Boolean = runStates.value.values.any { it.isRunning }

    /**
     * Stops every strategy that is running.
     *
     * Used when the user stops the app's trading from outside — the notification's stop action,
     * or signing out. Each engine ends at its own next safe point, so a contract already in flight
     * is still settled and recorded.
     */
    fun stopAllEngines() {
        engines.values.forEach { engine ->
            val state = engine.simState.value
            if (state != SimulatorState.IDLE && state != SimulatorState.STOPPED) {
                engine.stopTrading()
            }
        }
    }

    /**
     * Zero the risk counters on every strategy at once.
     *
     * Both strategies can be running side by side, and the user's reset is about the account's
     * counters, not about one strategy's, so every engine that exists is reset — a strategy that
     * has never run gets nothing created on its behalf.
     */
    fun resetRiskStatsAll() {
        engines.values.forEach { engine -> engine.resetRiskStats() }
    }

    /**
     * Stops the foreground service, but only once nothing is trading.
     *
     * With one strategy this is the whole story. With two, a strategy that stops while the other
     * keeps going must not take the notification — and the process — down with it.
     */
    private fun stopServiceWhenNothingRuns() {
        scope.launch {
            // The engine's own state has to reach the registry first, or this would see the run as
            // still live and leave a service running with nothing behind it.
            kotlinx.coroutines.delay(SERVICE_STOP_GRACE_MS)
            if (!isAnythingRunning()) {
                TradingService.stop(application)
            }
        }
    }

    companion object {
        private const val NO_SESSION = 0L

        /**
         * How long to wait before deciding a run has really ended.
         *
         * The engine publishes "stopped" and then calls back; the registry is updated by a
         * collector, so there is a moment where the engine has stopped but the registry has not
         * caught up. Without this pause the service would be torn down while a strategy still
         * looked busy.
         */
        private const val SERVICE_STOP_GRACE_MS = 750L

        /** How often the shared-stop watch polls the cloud. Fast enough that a stop on the other
         *  phone lands within one tick; quiet enough that a signed-in-but-idle device costs little. */
        private const val CLOUD_STOP_POLL_MS = 5_000L

        /** How often a running session's live totals are re-published to the cloud. Same cadence as
         *  the stop watch — a sibling should see P&L move within seconds, not settle-and-lag. */
        private const val CLOUD_PROGRESS_POLL_MS = 5_000L

        @Volatile
        private var INSTANCE: TradingRuntime? = null

        /**
         * The runtime for this process.
         *
         * Built on first use rather than in the ViewModel, so it survives the Activity and can be
         * reached from a service that starts before any screen exists.
         *
         * It also owns the OAuth client, the Deriv client and the market-data socket. Those were
         * all created against `viewModelScope`, which meant every one of them was cancelled when
         * the Activity went away — the sockets closed, the token stopped refreshing, and the
         * trading loop lost the very connection it needed to settle an open contract. They are
         * created here, against an application scope, so they outlive the screen.
         */
        fun get(application: Application): TradingRuntime =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val oauth = DerivOAuthClient.share(application, scope)
                    val deriv = DerivClient(
                        BuildConfig.DERIV_APP_ID,
                        { oauth.accessToken() },
                        scope
                    )
                    TradingRuntime(
                        application = application,
                        scope = scope,
                        db = AppDatabase.getDatabase(application),
                        oauth = oauth,
                        deriv = deriv,
                        webSocketService = DerivWebSocketService(scope)
                    ).also { INSTANCE = it }
                }
            }

        /** The runtime, if one has been created. Null before the first [get]. */
        fun peek(): TradingRuntime? = INSTANCE
    }
}
