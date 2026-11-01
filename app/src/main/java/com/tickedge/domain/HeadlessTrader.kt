package com.tickedge.domain

import android.app.Application
import android.content.Context
import android.util.Log
import com.tickedge.data.deriv.DerivAccountKind
import com.tickedge.data.local.ScheduledRunEntity
import com.tickedge.data.local.StrategySettingsEntity
import com.tickedge.data.local.SimulationSessionEntity
import com.tickedge.data.model.SystemConfig
import com.tickedge.data.model.TradeDurationUnit
import com.tickedge.data.model.TradeSettings
import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.DigitSelectionMode
import com.tickedge.data.model.EvenOddMode
import kotlinx.coroutines.flow.first

/**
 * Boots a trading run with no Activity and no ViewModel in front of it.
 *
 * This is the path a schedule takes when the phone is locked, the app is closed, or the process
 * was just woken by an alarm: there is no [com.tickedge.ui.viewmodel.MainViewModel] to drive the
 * usual Start button. It does the same things that path does — re-derive identity, load the
 * accounts, open the market socket, open a session, then start the engine — but reads everything
 * it needs straight from the on-disk stores (the Deriv token, Room settings) rather than from a
 * live ViewModel that may not exist.
 *
 * Kept out of the ViewModel on purpose: the ViewModel is created against a screen, and this runs
 * without one. The two share the [TradingRuntime] and the engine, so a scheduled run and a
 * button-pressed run are the same run, just started from different doors.
 */
object HeadlessTrader {

    private const val MIN_WINDOW = 1
    private const val MAX_WINDOW = 1000

    /**
     * The engine is live and trading, or the reason it is not.
     *
     * Returning a value rather than throwing keeps the alarm receiver calm: a missed token or a
     * signed-out device is an ordinary outcome, not an exception, and the caller reports it
     * instead of crashing on the receiver thread.
     */
    sealed interface StartResult {
        /** The engine started and will trade until a limit or a Stop. */
        data object Trading : StartResult
        /** Nothing is signed in; no run can start. */
        data object SignedOut : StartResult
        /** The Deriv access token has lapsed and cannot be refreshed. */
        data object TokenExpired : StartResult
        /** The run is already going, so this start is a no-op. */
        data object AlreadyRunning : StartResult
    }

    /**
     * Start [strategyId] on [accountKind] using the settings saved under [userId].
     *
     * [userId] is the stable key the device signed in under; when it is null the function re-
     * derives it from the Deriv session so a receiver that only knows "the current user" works.
     */
    suspend fun start(
        context: Context,
        strategyId: String,
        accountKind: DerivAccountKind,
        userId: String? = null
    ): StartResult {
        val runtime = TradingRuntime.get(context.applicationContext as Application)
        val userId = userId?.takeIf { it.isNotBlank() }
            ?: runtime.oauth.currentUser()?.stableKey?.takeIf { it.isNotBlank() }
            ?: return StartResult.SignedOut

        runtime.userId = userId

        // Re-derive auth and pull the accounts from disk before any order, the same the normal
        // start does — a fresh process has no in-memory auth yet.
        runtime.oauth.rehydrate()
        runtime.deriv.loadAccounts()

        if (accountKind == DerivAccountKind.REAL) {
            // Live trading is arming the user explicitly chose; a scheduled run must never silently
            // start live, so this only happens when the schedule's account kind is REAL.
            runtime.deriv.setAccount(DerivAccountKind.REAL)
        } else {
            runtime.deriv.setAccount(DerivAccountKind.DEMO)
        }

        // The entry decision reads the public market socket, so it has to be open before the loop
        // runs or the first tick will never arrive.
        runtime.webSocketService.start()

        val engine = runtime.engineFor(strategyId)
        if (engine.simState.value == com.tickedge.domain.SimulatorState.TRADE_IN_PROGRESS ||
            engine.simState.value == com.tickedge.domain.SimulatorState.SCANNING
        ) {
            return StartResult.AlreadyRunning
        }

        // A run with no account to trade on can be started but will not place orders; surface it
        // rather than launching a loop that can never fire.
        val account = runtime.deriv.activeAccount
        if (account == null) {
            return if (runtime.oauth.accessToken() == null) StartResult.TokenExpired else StartResult.SignedOut
        }

        ensureProfile(runtime, userId)
        openSession(runtime, userId, strategyId, accountKind, account.id)

        val settings = runtime.db.strategySettingsDao().get(userId, strategyId)
            ?: StrategySettingsEntity(userId = userId, strategyId = strategyId)
        val userRow = runtime.db.userSettingsDao().getSettings(userId).first()

        engine.startTrading(
            settings = settings.toTradeSettings(),
            config = userRow?.toSystemConfig() ?: SystemConfig(),
            getMarketState = { symbol -> runtime.webSocketService.marketsState.value[symbol] },
            safety = TradingEngine.TradingSafety(
                activeKind = { accountKind },
                isRealArmed = { accountKind == DerivAccountKind.REAL && userRow?.realArmed == true },
                requirePerOrderConfirm = { accountKind == DerivAccountKind.REAL && userRow?.requirePerOrderConfirm == true }
            )
        )
        Log.i("HeadlessTrader", "Started headless run: $strategyId on $accountKind for $userId")
        return StartResult.Trading
    }

    /** Stop a strategy that a schedule (or anyone else) started, no UI required. */
    fun stop(context: Context, strategyId: String) {
        TradingRuntime.get(context.applicationContext as Application).engineFor(strategyId).stopTrading()
    }

    /** Make sure the profile row the session and settings hang off actually exists. */
    private suspend fun ensureProfile(runtime: TradingRuntime, userId: String) {
        runtime.db.userProfileDao().insertOrUpdateProfile(
            com.tickedge.data.local.UserProfileEntity(
                id = userId,
                displayName = runtime.oauth.currentUser()?.displayName.orEmpty()
            )
        )
    }

    /**
     * Open the session the run's trades will be stamped with.
     *
     * The engine writes positions through a session-id provider that reads this, so the session
     * row has to exist before the first order or the trade lands under the NO_SESSION placeholder
     * and is invisible to every session-scoped query — the same race the in-app start avoids by
     * opening the session before it launches the loop.
     */
    private suspend fun openSession(
        runtime: TradingRuntime,
        userId: String,
        strategyId: String,
        accountKind: DerivAccountKind,
        accountId: String
    ) {
        val session = SimulationSessionEntity(
            userId = userId,
            accountId = accountId,
            isReal = accountKind == DerivAccountKind.REAL,
            strategy = StrategyCatalog.find(strategyId).name,
            strategyId = strategyId,
            status = "ACTIVE",
            initialSimulatedBalance = runtime.deriv.activeAccount?.balance ?: 0.0,
            currentSimulatedBalance = runtime.deriv.activeAccount?.balance ?: 0.0
        )
        val opened = runtime.db.simulationDao().insertSession(session)
        runtime.openSession(strategyId, session, opened)
    }

    /**
     * One row of `strategy_settings` back to a [TradeSettings] blob.
     *
     * Mirrors the ViewModel's mapping (MainViewModel.fromStrategySettings) so a scheduled run and
     * a button-pressed run read the very same numbers; defensive fallbacks keep a symbol a rename
     * could break from stranding the schedule with an unreadable setting.
     */
    private fun StrategySettingsEntity.toTradeSettings(): TradeSettings = TradeSettings(
        targetDigit = differsDigit,
        digitSelectionMode = DigitSelectionMode.values()
            .firstOrNull { it.symbol == differsDigitMode } ?: DigitSelectionMode.RANDOM,
        recoveryDirection = recoveryDirection,
        recoveryDigit = recoveryDigit,
        comparisonOperator = ComparisonOperator.values()
            .firstOrNull { it.symbol == differsOperator } ?: ComparisonOperator.LESS_THAN,
        thresholdPercent = differsThreshold,
        recoveryComparisonOperator = ComparisonOperator.values()
            .firstOrNull { it.symbol == recoveryOperator } ?: ComparisonOperator.GREATER_THAN,
        recoveryThresholdPercent = recoveryThreshold,
        analysisWindowSize = analysisWindow.coerceIn(MIN_WINDOW, MAX_WINDOW),
        stakeAmount = stakeAmount,
        takeProfit = takeProfit,
        stopLoss = stopLoss,
        durationUnit = TradeDurationUnit.values()
            .firstOrNull { it.label.equals(durationUnit, ignoreCase = true) } ?: TradeDurationUnit.TICKS,
        durationValue = durationValue,
        martingaleEnabled = martingaleEnabled,
        evenOddMode = EvenOddMode.values()
            .firstOrNull { it.symbol == evenOddMode } ?: EvenOddMode.AUTO,
        evenOddOperator = ComparisonOperator.values()
            .firstOrNull { it.symbol == evenOddOperator } ?: ComparisonOperator.GREATER_THAN,
        evenOddThreshold = evenOddThreshold
    )

    /** System config for a scheduled run, from the shared row's risk columns. */
    private fun com.tickedge.data.local.UserSettingsEntity.toSystemConfig(): SystemConfig =
        SystemConfig(
            randomMarkets = randomMarkets,
            maxTradesPerMarket = maxTradesPerMarket,
            maxDailyLoss = maxDailyLoss,
            stopTradingOnLimit = stopTradingOnLimit
        )
}
