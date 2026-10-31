package com.tickedge.domain

import com.tickedge.data.deriv.DerivClient
import com.tickedge.data.deriv.DerivAccountKind
import com.tickedge.data.deriv.DerivApiException
import com.tickedge.data.deriv.DerivContractParams
import com.tickedge.data.deriv.DerivContractType
import com.tickedge.data.deriv.DerivSettlement
import com.tickedge.data.deriv.DerivTradingClient
import com.tickedge.data.deriv.PlaceOrderPermit
import com.tickedge.data.local.AppDatabase
import com.tickedge.data.local.SimulationPositionEntity
import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.isAtLeast
import com.tickedge.data.model.DigitSelectionMode
import com.tickedge.data.model.EvenOddMode
import java.util.concurrent.ConcurrentHashMap
import com.tickedge.data.model.MarketState
import com.tickedge.data.model.SimulatedTrade
import com.tickedge.data.model.SupportedMarkets
import com.tickedge.data.model.SystemConfig
import com.tickedge.data.model.TradeDurationUnit
import com.tickedge.data.model.TradeOutcome
import com.tickedge.data.model.TradeSettings
import com.tickedge.data.model.TradeType
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Places REAL Deriv options orders (Digit Differs + Digits Over/Under with Martingale recovery)
 * and reads authoritative contract settlements. It does not fabricate results.
 *
 * Safety: demo runs autonomously; a real account is only used when explicitly armed, and can
 * additionally require a per-order confirmation. Martingale doubling is capped.
 */
/**
 * Draws a random target digit for one market, avoiding an immediate repeat of [previous] and any
 * digit currently [parked] on a cool-off.
 *
 * Parked digits are skipped rather than merely filtered out afterwards: rolling a digit the bot is
 * not allowed to trade wastes the draw and leaves that market looking like it failed the condition
 * when it did not.
 *
 * The fallbacks matter more than they look. If every digit but the previous one is parked, or all ten
 * are, there is no legal draw — and returning nothing would mean the market silently drops out of
 * the sweep rather than being reported, so each narrowing step relaxes one constraint rather than
 * giving up.
 *
 * Extracted as a pure top-level function so the no-repeat rule can be unit-tested; [TradingEngine]
 * holds the per-market `previous` state.
 */
internal fun drawRandomDigit(previous: Int?, parked: Set<Int> = emptySet()): Int {
    val candidates = (0..9).filter { it != previous && it !in parked }
    val pool = candidates
        .ifEmpty { (0..9).filter { it !in parked } }
        .ifEmpty { (0..9).toList() }
    return pool.random()
}

/** Logcat tag for the trade execution path, which had no logging at all. */
private const val tag = "TradingEngine"

class TradingEngine(
    private val deriv: DerivClient,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    /**
     * Supplies the stable user identity used as the `userId` scope for database writes.
     *
     * This is a provider, not a value: the engine is constructed when the ViewModel is created,
     * which on a cold start is *before* any OAuth session exists. Capturing the id at
     * construction time would pin every trade to the anonymous fallback and keep writing there
     * after the user signs in — and would keep the previous user's rows after a sign-out and
     * sign-in as someone else. Resolving it per write keeps trades with whoever is actually
     * signed in.
     */
    private val userIdProvider: () -> String,
    /**
     * Supplies the session id stamped onto every position.
     *
     * A provider for the same reason as [userIdProvider], plus one more: the session does not
     * exist when the engine is constructed — it is created when Start is pressed. Positions were
     * previously written with a hardcoded `sessionId = 1L`, so every position ever recorded shared
     * one bucket and could not be attributed to a run.
     */
    private val sessionIdProvider: () -> Long,
    /**
     * Invoked exactly once when a run ends, for **any** reason — a risk limit, a blocked order, or
     * the user pressing Stop.
     *
     * This exists because the session was only ever closed from the UI's Stop handler. When the bot
     * halted *itself* the session row stayed ACTIVE, and `ensureSessionStarted` deliberately reuses
     * an ACTIVE session — so the next Start quietly continued the previous run instead of opening a
     * new one. The second session the user expected never existed, and the first one's `endedAt`
     * spanned both runs. One callback at the single point every exit path passes through is what
     * makes "one Start, one session" true regardless of how the run ended.
     */
    private val onRunEnded: () -> Unit = {},
    /**
     * The strategy this engine runs.
     *
     * One engine per strategy. That is what makes two strategies able to trade at once: each has
     * its own rotation window, its own cool-offs, its own losing streak and its own session, so
     * neither can interfere with the other. It is also what lets the notification say which
     * strategy is doing what.
     */
    private val strategyId: String = StrategyCatalog.DEFAULT.id
) {
    private val _simState = MutableStateFlow(SimulatorState.IDLE)
    val simState: StateFlow<SimulatorState> = _simState.asStateFlow()

    /**
     * This run's published figures.
     *
     * The shape the rest of the app reads, so the notification and the strategy card describe a run
     * without knowing anything about how the engine works internally.
     */
    private val _runState = MutableStateFlow(
        StrategyRunState(
            strategyId = strategyId,
            strategyName = StrategyCatalog.find(strategyId).name
        )
    )
    val runState: StateFlow<StrategyRunState> = _runState.asStateFlow()

    /** The strategy's display name, resolved once. */
    private val strategyName: String = StrategyCatalog.find(strategyId).name

    /**
     * Whether this engine runs the Even/Odd strategy.
     *
     * One engine per strategy, so the strategy id is the whole question: when it is the
     * Even/Odd strategy, every entry and every recovery rung is a DIGITEVEN/DIGITODD contract
     * rather than a Differs or Over/Under one. Everything else — rotation, cool-off, stop
     * loss, daily limit, balance guard, session bookkeeping — is strategy-agnostic and shared.
     */
    private val isEvenOddStrategy: Boolean =
        StrategyCatalog.find(strategyId).id == StrategyCatalog.EVEN_ODD.id

    /** The DIGITEVEN/DIGITODD contract for a parity class encoded as a digit sentinel. */
    private fun parityContractTypeFor(targetDigit: Int): DerivContractType =
        if (targetDigit == PARITY_EVEN_SENTINEL) DerivContractType.DIGIT_EVEN
        else DerivContractType.DIGIT_ODD

    /** A short human label for a parity class encoded as a digit sentinel. */
    private fun parityLabelFor(targetDigit: Int): String =
        if (targetDigit == PARITY_EVEN_SENTINEL) "even" else "odd"

    /**
     * Records something about this run in the published state.
     *
     * Every change the outside world can see about a run goes through here, so the notification and
     * the strategy card can never describe a run differently from the way it actually is.
     */
    private fun publishRunState(transform: (StrategyRunState) -> StrategyRunState) {
        _runState.update { current ->
            transform(current.copy(strategyId = strategyId, strategyName = strategyName))
        }
    }

    /** A one-line description of what the bot is holding right now. */
    private fun describeActiveTrade(trade: ActiveTradeState): String {
        val stake = "$" + String.format(java.util.Locale.US, "%.2f", trade.currentStake)
        return when {
            isEvenOddStrategy -> {
                // The class is encoded in targetDigit: the sentinel that names which parity
                // this contract settles on. Saying "differs from 0" here would describe the
                // Differs contract, not the one actually open.
                val cls = if (trade.targetDigit == PARITY_EVEN_SENTINEL) "Even" else "Odd"
                val phase = if (trade.isRecoveryMode) "recovery · " else ""
                "${trade.marketName} · ${cls.lowercase()} · $phase$stake"
            }
            trade.isRecoveryMode ->
                "${trade.marketName} · ${trade.recoveryDirection.lowercase()} " +
                    "${trade.targetDigit} · $stake"
            else ->
                "${trade.marketName} · differs from ${trade.targetDigit} · $stake"
        }
    }

    private val _activeTrade = MutableStateFlow<ActiveTradeState?>(null)
    val activeTrade: StateFlow<ActiveTradeState?> = _activeTrade.asStateFlow()

    private val _nextTrade = MutableStateFlow(NextTradePreview())
    val nextTrade: StateFlow<NextTradePreview> = _nextTrade.asStateFlow()

    private val _consecutiveLosses = MutableStateFlow(0)
    val consecutiveLosses: StateFlow<Int> = _consecutiveLosses.asStateFlow()

    private val _recentTradeOutcome = MutableStateFlow<SimulatedTrade?>(null)
    val recentTradeOutcome: StateFlow<SimulatedTrade?> = _recentTradeOutcome.asStateFlow()
    val lastFinishedTrade: StateFlow<SimulatedTrade?> = _recentTradeOutcome.asStateFlow()

    private val _dailyLossAccumulated = MutableStateFlow(0.0)
    val dailyLossAccumulated: StateFlow<Double> = _dailyLossAccumulated.asStateFlow()

    /**
     * The cost of the losing streak in progress — what the **stop loss** is measured against.
     *
     * Zero at the start of a run and zero again after any win, because a win ends the streak. A
     * loss adds to it.
     *
     * Separate from [dailyLossAccumulated] on purpose. The stop loss answers "how deep is this run
     * in the hole right now", which is a question about the *current* streak; the daily limit
     * answers "how much has this account lost today", which no win can undo. Measuring the stop
     * loss on the daily figure made the bot halt on losses the account had already recovered — the
     * recovery ladder's whole purpose is to win the money back, and then the stop loss took it away
     * again.
     *
     * The rules live in [LossAccounting] so they can be tested without a socket.
     */
    private val _streakLoss = MutableStateFlow(0.0)
    val streakLoss: StateFlow<Double> = _streakLoss.asStateFlow()

    private val lossAccounting = LossAccounting()

    /**
     * Net profit for the current run, used to enforce the take-profit target. Wins add and losses
     * subtract, so it is a true net figure rather than a gross-loss counter.
     */
    private val _sessionProfit = MutableStateFlow(0.0)
    val sessionProfit: StateFlow<Double> = _sessionProfit.asStateFlow()

    /** Day-of-year on which the loss accumulator was last reset, for midnight rollover. */
    private val _dailyLossAnchorDay = MutableStateFlow(-1L)

    private val _pendingConfirm = MutableStateFlow<NextTradePreview?>(null)
    val pendingConfirmation: StateFlow<NextTradePreview?> = _pendingConfirm.asStateFlow()

    /**
     * Contracts the bot is currently refusing after a run of losses on them.
     *
     * Consulted on every path that can choose a market — the sweep, the random digit draw, and the
     * forced-recovery fallback — because a restriction enforced in only one of them is a restriction
     * that the others route around.
     */
    private val cooldown = MarketCooldownTracker()
    val coolingOffEntries: StateFlow<List<TradeEntry>> = cooldown.coolingOff

    /**
     * How recently each market has carried a trade, so the field rotates instead of clustering.
     *
     * Rebuilt whenever the setting changes, because the ceiling is the user's: they decide how often
     * one market may be traded before the others get a turn.
     */
    private var rotation = MarketRotation()
    private var rotationMaxTrades = -1

    /**
     * Why the last run ended, or null while it is running.
     *
     * The engine used to set `STOPPED` and break without recording why, so every automatic stop —
     * stop loss, daily limit, take profit, a blocked real-money order — looked identical on screen
     * to the user pressing Stop. A bot that halts itself on a risk limit is doing the one thing it
     * exists to do, and saying so is the difference between a working guardrail and a malfunction.
     */
    private val _stopNotice = MutableStateFlow<StopNotice?>(null)
    val stopNotice: StateFlow<StopNotice?> = _stopNotice.asStateFlow()

    /** Clears the banner once the user has read it. */
    fun dismissStopNotice() {
        _stopNotice.value = null
    }

    /**
     * Why the last order could not be placed, or null when it went in.
     *
     * Every rejection path writes the *actual* cause here rather than the caller inferring one.
     * The generic "an order could not be placed" that a `false` return used to produce named the
     * two most likely suspects — account not connected, live trading not armed — for a failure that
     * was usually neither: a dropped socket, a rejected proposal, or a declined confirmation.
     */
    private var lastOrderFailure: String? = null

    /**
     * Markets whose orders failed recently, mapped to the moment the bot may use them again.
     *
     * The value is an expiry rather than a count so the skip cannot outlive the session or be
     * cleared by the ladder state reset — a market that refused an order stays set aside for
     * [MARKET_SKIP_AFTER_MS] no matter what else the run does, then comes back into rotation on
     * its own.
     */
    private val failedMarkets = mutableMapOf<String, Long>()

    /** Set when Stop was pressed with a contract in flight; honoured once it has settled. */
    private var stopRequested = false

    /**
     * Markets currently set aside, with the expired ones dropped on the way out.
     *
     * Pruning here rather than leaving the map to grow means the skip list always states what it
     * actually means — "these markets are unavailable right now" — instead of silently
     * accumulating every market that ever refused an order.
     */
    private fun blockedMarkets(): Set<String> {
        val now = System.currentTimeMillis()
        failedMarkets.entries.removeIf { it.value <= now }
        return failedMarkets.keys
    }

    private var executionJob: Job? = null
    private var confirmDeferred: CompletableDeferred<Boolean>? = null
    private var streakLossCount = 0

    /**
     * Money lost so far in the current losing streak, which is what recovery has to clear.
     *
     * Tracked as an amount rather than derived from `streakLossCount`: the count alone cannot size
     * a recovery stake, because every rung carries a different stake.
     */
    private var recoveryOutstanding = 0.0

    /**
     * Whether the ladder has switched to the alternate barrier since the streak began.
     *
     * Reset on every win and on every fresh streak, so the ladder always starts on the primary
     * Over/Under barrier and only moves once it has lost there. Toggling mid-streak would silently
     * move a stake that was sized against one payout onto a contract priced differently.
     */
    private var recoveryOnAlternate = false

    /**
     * The barrier this recovery rung should trade.
     *
     * With [TradeSettings.recoveryAlternates] off this is always the configured primary, so the
     * behaviour is exactly what it was before. With it on, a ladder that has already lost on the
     * primary switches to the alternate on its next rung — a different contract at a different
     * barrier, rather than the same one staked larger.
     */
    private fun recoveryBarrierFor(settings: TradeSettings): Pair<Int, String> =
        if (settings.recoveryAlternates && recoveryOnAlternate) {
            settings.recoveryAlternateDigit to settings.recoveryAlternateDirection
        } else {
            settings.recoveryDigit to settings.recoveryDirection
        }

    /**
     * Stake needed to clear [recoveryOutstanding] and still bank the target profit.
     *
     * Replaces plain `stake * 2^n`. Doubling does not target anything — after a 1.00 loss it stakes
     * 2.00 for a contract paying r, which recovers 2.00*r against a 1.00 hole and usually still
     * falls short, while at a low payout ratio it wildly over-recovers and banks nothing extra.
     * The ladder is now sized from the contract's own payout so a win genuinely ends the streak in
     * profit.
     *
     * Falls back to the previous doubling behaviour if the plan cannot be built, so a configuration
     * problem can never silently stop the engine from trading.
     */
    private suspend fun recoveryStakeFor(
        settings: TradeSettings,
        symbol: String,
        barrier: Int,
        direction: String
    ): Double {
        // Quote the contract this rung is actually about to be, rather than inferring a payout from
        // the barrier digit. Differs and Over/Under have no fixed return — the quote moves with the
        // index, the barrier and the duration — so a stake derived from the digit alone is sized
        // against a number the venue is not going to pay. Falls back to the theoretical ratio if the
        // socket is down; a rung placed at a slightly wrong price beats a ladder that never runs.
        val liveRatio = runCatching {
            val account = deriv.activeAccount
            val trading = deriv.ensureTrading()
            if (account == null || trading == null) {
                null
            } else {
                val quote = trading.proposal(
                    DerivContractParams(
                        contractType = recoveryContractType(direction),
                        underlyingSymbol = symbol,
                        currency = account.currency,
                        amount = roundToVenuePrecision(settings.stakeAmount),
                        barrier = barrier,
                        duration = settings.durationValue.coerceIn(1, 100),
                        durationUnit = durationUnitFor(settings)
                    )
                )
                RecoveryCalculator.payoutRatioFromQuote(quote.payout, quote.askPrice)
            }
        }.getOrNull()

        return recoveryStakeSized(settings, recoveryOutstanding, streakLossCount, barrier, direction, liveRatio)
    }

    /**
     * Pure sizing: the recovery rung for a given [outstanding] loss and [streak] length, without
     * reading engine state. [recoveryStakeFor] is the wrapper that quotes the live contract and
     * feeds it here; keeping the arithmetic in [recoveryRungStake] is what lets the UI preview the
     * *next* stake on a loss — the exact value the engine will place next — rather than showing a
     * bare `stake * 2`, and lets the ladder be walked end to end in a test.
     *
     * @param barrier digit the rung trades, which is the *active* one — the alternate when the
     *   ladder has switched. Sizing on the primary while trading the alternate would stake an
     *   amount recovered at a payout the venue is not offering.
     * @param direction "OVER" or "UNDER", likewise the active one.
     * @param livePayoutRatio ratio quoted by the venue for this contract, when one is available.
     */
    private fun recoveryStakeSized(
        settings: TradeSettings,
        outstanding: Double,
        streak: Int,
        barrier: Int = settings.recoveryDigit,
        direction: String = settings.recoveryDirection,
        livePayoutRatio: Double? = null
    ): Double = recoveryRungStake(
        baseStake = settings.stakeAmount,
        outstanding = outstanding,
        streak = streak,
        recoveryDigit = barrier,
        isOver = direction.equals("OVER", ignoreCase = true),
        livePayoutRatio = livePayoutRatio
    )

    /**
     * Stake needed to clear [recoveryOutstanding] and still bank the target profit, for a
     * DIGITEVEN/DIGITODD rung.
     *
     * The parity version of [recoveryStakeFor]: the rung's class is already chosen by the sweep's
     * auto-qualifier rule, so there is no barrier to size against — only the class. The live
     * quote is the number the rung will actually settle at, exactly as with the Over/Under rung,
     * with the theoretical 0.5-prior as the fallback when the socket is down.
     */
    private suspend fun parityRecoveryStakeFor(
        settings: TradeSettings,
        symbol: String,
        targetDigit: Int
    ): Double {
        val liveRatio = runCatching {
            val account = deriv.activeAccount
            val trading = deriv.ensureTrading()
            if (account == null || trading == null) {
                null
            } else {
                val quote = trading.proposal(
                    DerivContractParams(
                        contractType = parityContractTypeFor(targetDigit),
                        underlyingSymbol = symbol,
                        currency = account.currency,
                        amount = roundToVenuePrecision(settings.stakeAmount),
                        barrier = 0,
                        duration = settings.durationValue.coerceIn(1, 100),
                        durationUnit = durationUnitFor(settings)
                    )
                )
                RecoveryCalculator.payoutRatioFromQuote(quote.payout, quote.askPrice)
            }
        }.getOrNull()

        return parityRecoveryRungStake(
            baseStake = settings.stakeAmount,
            outstanding = recoveryOutstanding,
            streak = streakLossCount,
            livePayoutRatio = liveRatio
        )
    }

    /**
     * The parity class a market's Even/Odd trade would use, resolving the same way the sweep
     * resolves it.
     *
     * Used on the forced-recovery fallback, where no qualifying reading exists to name the class:
     * the market's own frequencies decide, with [PARITY_EVEN_SENTINEL] as the neutral default
     * when it has no data yet. Keeping the rule in [resolveParityClass] means the fallback and the
     * sweep cannot disagree about which side of the market is the hot one.
     */
    private fun parityClassFor(
        settings: TradeSettings,
        symbol: String,
        getMarketState: (String) -> MarketState?
    ): Int = resolveParityClass(
        frequencies = getMarketState(symbol)?.digitFrequencies ?: emptyList(),
        mode = settings.evenOddMode,
        operator = settings.evenOddOperator
    )

    // `roundToVenuePrecision` now lives at file scope next to the recovery maths, so the engine and the
    // planner cannot drift apart on how a stake is rounded.
    private var lastTradedMarketSymbol = ""

    /**
     * Consecutive recovery sweeps in which no market met the condition.
     *
     * Reset by any win and by every non-recovery poll. Reaching [MAX_RECOVERY_SCAN_POLLS] forces the
     * recovery rung through regardless, because the exposure is already spent and an entry condition
     * that rarely fires would otherwise strand it indefinitely.
     */
    private var recoveryScanPolls = 0

    /**
 * Live safety/permission inputs, resolved each trade so the UI can change them at runtime.
 *
 * `maxMartingaleSteps` was removed deliberately. It used to stop the loop at the cap, which
 * stranded an unrecovered stake while the UI still read "IN RECOVERY" — and it stopped being a
 * stop condition when the ladder was changed to run until the stop loss. The step limit now means
 * only how far the doubling schedule climbs within a rung; the ladder's overall length is bounded
 * by the stop loss, which is the limit that actually protects the balance. Leaving the parameter
 * here would have been a setting that reads as a safety control and does nothing.
 */
    data class TradingSafety(
        val activeKind: () -> DerivAccountKind,
        val isRealArmed: () -> Boolean,
        val requirePerOrderConfirm: () -> Boolean
    )

    fun startTrading(
        settings: TradeSettings,
        config: SystemConfig,
        getMarketState: (String) -> MarketState?,
        safety: TradingSafety
    ) {
        // Cancelling any previous loop before the state check, rather than only setting the state.
        // Reading `_simState` and then writing it is check-then-act across two suspension-free
        // statements, but `startTrading` is invoked from the UI thread while the loop mutates that
        // same field from a background dispatcher — so a Start arriving as the old loop is exiting
        // could observe STOPPED, pass the guard, and launch a second loop beside the first. Two
        // loops then trade independently, each with its own view of `lastTradedMarketSymbol`, and
        // the no-repeat rule cannot hold between them. Succeeding here means only one loop can ever
        // be live, which is what the rule assumes.
        executionJob?.cancel()
        if (_simState.value == SimulatorState.TRADE_IN_PROGRESS || _simState.value == SimulatorState.SCANNING) {
            return
        }

        _simState.value = SimulatorState.SCANNING
        // A fresh Start clears the previous run's banner. Leaving it up would attach the last run's
        // stop reason to a run that has not stopped yet.
        _stopNotice.value = null
        // A Start means the user wants to trade now, not that a Stop pressed during the previous
        // run's open contract is still pending. Without this the first settled trade of the new run
        // would read `stopRequested`, close the run immediately, and the Start would appear to do
        // nothing.
        stopRequested = false
        lastOrderFailure = null
        // A new run starts from zero. The published figures belong to the run that just ended, and
        // carrying them into the next one would make the notification describe two runs as one.
        publishRunState {
            it.copy(
                status = RunStatus.SCANNING,
                trades = 0,
                wins = 0,
                losses = 0,
                netPnl = 0.0,
                consecutiveLosses = 0,
                activeTradeSummary = null,
                startedAt = System.currentTimeMillis()
            )
        }
        executionJob = scope.launch(Dispatchers.Default) {
            while (isActive && _simState.value != SimulatorState.STOPPED) {
                rolloverDailyLossIfNeeded()

                // Risk-management stop conditions.
                //
                // The two limits are checked apart rather than as one boolean, because they are
                // different decisions with different remedies: the stop loss is a per-run ceiling
                // the user sets, the daily limit is a whole-day budget. Telling the user "you hit
                // your limit" when they actually hit the other one sends them to the wrong setting.
                // The stop loss reads the current streak; the daily limit reads the day's total.
                // Different questions, different remedies, so they are checked apart and reported
                // apart.
                val streakLoss = _streakLoss.value
                val dailyLoss = _dailyLossAccumulated.value
                val stopLossHit = streakLoss >= settings.stopLoss
                val dailyLimitHit = config.stopTradingOnLimit && dailyLoss >= config.maxDailyLoss
                if (stopLossHit || dailyLimitHit) {
                    _stopNotice.value = if (stopLossHit) {
                        StopNotice(
                            kind = StopKind.STOP_LOSS,
                            title = "Trading stopped — stop loss reached",
                            detail = "This losing streak reached ${fmtMoney(streakLoss)}, at or " +
                                "past your stop loss of ${fmtMoney(settings.stopLoss)}. No new " +
                                "orders will be placed. A win that recovers the loss resets this, " +
                                "so the limit applies to a streak in progress rather than to " +
                                "everything lost today."
                        )
                    } else {
                        StopNotice(
                            kind = StopKind.DAILY_LIMIT,
                            title = "Trading stopped — daily loss limit reached",
                            detail = "Today's losses reached ${fmtMoney(dailyLoss)}, at or past " +
                                "your daily limit of ${fmtMoney(config.maxDailyLoss)}. No new " +
                                "orders will be placed today. This figure is not reduced by a win " +
                                "later in the day — the money was lost either way."
                        )
                    }
                    _simState.value = SimulatorState.STOPPED
                    publishRunState { it.copy(status = RunStatus.STOPPED, activeTradeSummary = null) }
                    break
                }

                // Take-profit was previously collected in the UI and persisted but never read
                // here, so a configured profit target could never halt a run.
                val takeProfitHit = settings.takeProfit > 0.0 &&
                    _sessionProfit.value >= settings.takeProfit
                if (takeProfitHit) {
                    _stopNotice.value = StopNotice(
                        kind = StopKind.TAKE_PROFIT,
                        title = "Trading stopped — profit target reached",
                        detail = "Session profit reached ${fmtMoney(_sessionProfit.value)}, at " +
                            "or past your take profit of ${fmtMoney(settings.takeProfit)}. No " +
                            "new orders will be placed."
                    )
                    _simState.value = SimulatorState.STOPPED
                    publishRunState { it.copy(status = RunStatus.STOPPED, activeTradeSummary = null) }
                    break
                }

                // The ladder used to stop dead at the Martingale cap. That is not what the cap means: it is a
                // *step* limit for the doubling schedule, not a limit on how many steps the ladder
                // may take. Stopping there stranded the exposure — the previous stake was still
                // unrecovered and the UI still said "IN RECOVERY" — while the stop loss, the one
                // bound that actually protects the balance, had not been reached. The ladder now
                // runs until the stop loss or the daily limit stops it, and past the cap it keeps
                // placing the largest rung rather than silently reverting to the base stake, which
                // would claim the recovery had happened when it had not.
                val isRecovery = streakLossCount > 0 && settings.martingaleEnabled
                // The Even/Odd strategy trades DIGITEVEN/DIGITODD on entry and on every recovery
                // rung; the Differs strategy keeps its Differs entry + Over/Under recovery.
                val parity = isEvenOddStrategy
                val tradeType = when {
                    parity -> TradeType.EVEN_ODD
                    isRecovery -> TradeType.OVER_UNDER
                    else -> TradeType.DIFFERS
                }

                // THE ENTRY DECISION. Every subscribed market is evaluated in this one pass — not
                // one randomly chosen market per poll — and the order goes into whichever market
                // actually cleared the threshold. The previous shape drew a market at random, tested
                // only it, and slept a second if it failed, so a qualifying market could go unseen
                // for many cycles while the bot cycled through the others.
                //
                // The rotation tracker is rebuilt only when the setting actually changes, so the
                // window survives between polls and between runs — rebuilding it every pass would
                // reset the counts to zero and make the ceiling unreachable.
                if (rotationMaxTrades != config.maxTradesPerMarket) {
                    rotationMaxTrades = config.maxTradesPerMarket
                    rotation = MarketRotation(maxTradesPerMarket = config.maxTradesPerMarket)
                }

                // The market that just carried a trade is passed as excluded, so the sweep cannot
                // hand the same one back twice in a row while another qualifier exists.
                val sweep = if (parity) {
                    // The parity sweep names its own thresholds and class, so the scan-state
                    // figures below read the Even/Odd pair rather than the Differs one.
                    sweepEvenOddMarkets(
                        settings = settings,
                        config = config,
                        mode = settings.evenOddMode,
                        excludeSymbol = lastTradedMarketSymbol.takeIf { it.isNotBlank() },
                        getMarketState = getMarketState,
                        coolOff = { entry -> cooldown.state(entry) },
                        rotation = rotation
                    ).also {
                        _scanState.value = _scanState.value.copy(
                            thresholdPercent = settings.evenOddThreshold,
                            isGreaterThan = settings.evenOddOperator.isAtLeast
                        )
                    }
                } else {
                    sweepMarkets(
                        settings = settings,
                        config = config,
                        isRecovery = isRecovery,
                        excludeSymbol = lastTradedMarketSymbol.takeIf { it.isNotBlank() },
                        digitFor = { symbol -> digitFor(settings, symbol, isRecovery) },
                        getMarketState = getMarketState,
                        coolOff = { entry -> cooldown.state(entry) },
                        // The sweep has to measure the same side the ladder is trading, or it would be
                        // evaluating P(over barrier) while the order goes in as P(under barrier).
                        recoveryIsOver = if (isRecovery) {
                            recoveryBarrierFor(settings).second.equals("OVER", ignoreCase = true)
                        } else {
                            null
                        },
                        rotation = rotation
                    )
                }
                recoveryScanPolls = if (isRecovery) recoveryScanPolls + 1 else 0

                // Recovery is gated on the same condition as everything else, which means a ladder
                // can stall behind a threshold that rarely fires and strand an outstanding loss.
                // After MAX_RECOVERY_SCAN_POLLS fruitless sweeps the rung is placed anyway: waiting
                // forever is a decision too, and the exposure is already spent.
                val forcedRecovery = isRecovery && sweep.candidate == null &&
                    recoveryScanPolls >= MAX_RECOVERY_SCAN_POLLS

                // A market whose order just failed is set aside for a while and the next qualifying
                // one is taken instead of re-trying the same book.
                //
                // Re-sending into a market that has already refused the order is how one venue-side
                // problem becomes a stall: the sweep keeps picking the same qualifier, that pick
                // keeps failing, and the bot spends the whole session retrying it while three other
                // qualifying markets sit untouched. The skip is per market and time-boxed, so the
                // bot comes back to it rather than writing a market off for the rest of the session.
                val blocked = blockedMarkets()
                val blockedPick = sweep.candidate?.takeIf { it.symbol in blocked }
                val chosen = if (blockedPick != null) {
                    sweep.readings.firstOrNull { it.qualifies && it.symbol !in blocked }
                } else {
                    sweep.candidate
                }

                _scanState.value = ScanState(
                    isScanning = chosen == null && !forcedRecovery,
                    thresholdPercent = settings.thresholdPercent,
                    isGreaterThan = settings.comparisonOperator.isAtLeast,
                    qualified = chosen != null,
                    lastSweep = sweep,
                    reason = if (chosen != null) {
                        ""
                    } else when {
                        forcedRecovery -> "no market met the condition for $MAX_RECOVERY_SCAN_POLLS " +
                            "polls — placing recovery anyway so the loss is not stranded"
                        // Distinguish "nothing qualified" from "the only qualifier just traded". The
                        // second is the no-repeat rule holding, which is a deliberate pause rather
                        // than a stall, and the user needs to be able to tell them apart.
                        sweep.heldBack != null -> "${sweep.heldBack?.symbol} is the only market " +
                            "qualifying and was just traded — holding to avoid a repeat"
                        // Say the cool-off out loud. A market whose statistics clear the threshold
                        // and is still not traded is the one case where silence reads as a bug rather
                        // than as the rule working.
                        sweep.coolingOffCount > 0 ->
                            "${sweep.coolingOffCount} market/entry pair(s) cooling off after " +
                                "repeated losses — no others have met the condition yet"
                        // Say the rotation out loud too, for the same reason. A field where the
                        // busiest market is being held back looks like nothing is qualifying.
                        sweep.rotationSkippedCount > 0 ->
                            "${sweep.rotationSkippedCount} market(s) held back for trading too " +
                                "often — waiting for the others to catch up"
                        isRecovery -> "no Over/Under market is above the recovery threshold yet"
                        else -> "no market has met the entry condition"
                    }
                )

                if (chosen == null && !forcedRecovery) {
                    // The cool-off ages by one per settled trade, which is what "the next N trades"
                    // means. But if *every* market is parked there are no trades coming, so that
                    // clock would never advance and the bot would sit idle forever — a stall with no
                    // error and no explanation, the worst failure mode available. Age it once per
                    // poll in that state only: in normal operation at least one market is tradable,
                    // trades settle, and the cool-off keeps its intended length.
                    //
                    // Compared against the tracker's own parked count rather than the sweep's,
                    // because the sweep only reports a cool-off on markets it produced a reading
                    // for. Asking the tracker removes the dependency entirely.
                    val parkedCount = cooldown.coolingOff.value.size
                    if (sweep.evaluatedCount > 0 && parkedCount >= sweep.evaluatedCount) {
                        cooldown.advancePastTrade()
                        Log.w(
                            tag,
                            "all $parkedCount markets are on a cool-off and none can trade — " +
                                "ageing it by one poll so the bot cannot deadlock"
                        )
                    }
                    _simState.value = SimulatorState.SCANNING
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                // Forced recovery has no qualifying market to name, so fall back to the configured
                // one rather than leaving the contract without a symbol.
                val chosenSymbol = chosen?.symbol ?: selectMarket(config, settings, tradeType, getMarketState).also {
                    val parked = if (parity) {
                        // The rung's class depends on the market's own frequencies, so when the
                        // sweep found no qualifier the fallback quotes the Even entry: it is the
                        // cooler of the two, and the log only needs to say a cool-off was
                        // outranked by an outstanding loss.
                        TradeEntry(it, PARITY_EVEN_SENTINEL, TradeType.EVEN_ODD)
                    } else {
                        TradeEntry(it, settings.recoveryDigit, TradeType.OVER_UNDER)
                    }
                    val cool = cooldown.state(parked)
                    if (cool?.isCoolingOff == true) {
                        Log.w(
                            tag,
                            "forced recovery is overriding a cool-off on ${parked.describe()} " +
                                "(${cool.tradesRemaining} trades left) — an outstanding loss " +
                                "outranks avoiding a market that has been losing"
                        )
                    }
                }
                val targetDigit = if (parity) {
                    chosen?.digit ?: parityClassFor(settings, chosenSymbol, getMarketState)
                } else {
                    chosen?.digit ?: digitFor(settings, chosenSymbol, isRecovery)
                }

                // Drop the "no market qualified" note now that an order is going in, but keep the sweep and its
                // threshold: the card is about to show the market it entered, so its reading has to
                // survive the transition.
                _scanState.value = _scanState.value.copy(isScanning = false, reason = "")

                // Sized here rather than before the sweep, because a recovery rung is only correct
                // once the market and barrier it will be placed on are known: the payout ratio that
                // decides the stake is a property of that exact contract, not of the settings.
                val stake = when {
                    parity -> if (isRecovery) {
                        parityRecoveryStakeFor(settings, chosenSymbol, targetDigit)
                    } else {
                        roundToVenuePrecision(settings.stakeAmount)
                    }
                    isRecovery -> {
                        val (barrier, direction) = recoveryBarrierFor(settings)
                        recoveryStakeFor(settings, chosenSymbol, barrier, direction)
                    }
                    else -> {
                        // The base stake comes straight from settings, so it needs the same rounding:
                        // a user who types 0.005 would otherwise have every entry refused too.
                        roundToVenuePrecision(settings.stakeAmount)
                    }
                }

                val trading = deriv.ensureTrading()
                val preview = buildPreview(trading, chosenSymbol, settings, tradeType, isRecovery, stake, targetDigit)
                _nextTrade.value = preview

                val executed = executeRealTrade(
                    settings, config, getMarketState, safety, chosenSymbol, tradeType, stake, preview, targetDigit
                )
                if (!executed) {
                    // The balance check can end the run outright; it publishes its own STOPPED
                    // state and notice. Falling out of the loop here is correct: backing off and
                    // continuing would fight that decision, and the next retry could not succeed.
                    if (_simState.value == SimulatorState.STOPPED) break

                    // The order did not go in for a transient reason. This used to halt the run
                    // with a banner that named the wrong suspects — "check the account is
                    // connected and that live trading is armed" — for a failure that is nearly
                    // always a transient: a dropped socket, a refused quote, a declined
                    // confirmation. Every one of those clears on its own, so halting on them
                    // turned a blip into a dead bot that only the user could restart, and it
                    // dropped the session the user had started.
                    //
                    // Instead: remember the market that refused, pause long enough for it to
                    // change its mind, and carry on. The next sweep takes a different qualifying
                    // market.
                    val why = lastOrderFailure ?: "the order could not be placed"
                    lastOrderFailure = null
                    failedMarkets[chosenSymbol] = System.currentTimeMillis() + MARKET_SKIP_AFTER_MS
                    Log.w(tag, "order not placed on $chosenSymbol — $why")
                    _scanState.value = _scanState.value.copy(
                        isScanning = true,
                        qualified = false,
                        reason = "$why — ${SupportedMarkets.find(chosenSymbol).shortName} set " +
                            "aside briefly. Trying the other markets."
                    )
                    _simState.value = SimulatorState.SCANNING
                    delay(ORDER_FAILURE_BACKOFF_MS)
                    continue
                }

                // A deferred Stop: the contract that was open has settled and been recorded, so
                // the run can end here instead of placing the next order the user declined.
                if (stopRequested) {
                    Log.i(tag, "stop requested during a trade — closing after the contract settled")
                    _simState.value = SimulatorState.STOPPED
                    publishRunState { it.copy(status = RunStatus.STOPPED, activeTradeSummary = null) }
                    break
                }

                // Short intermission so the user sees the outcome before the next trade.
                delay(1500L)
            }
            // Normal exit (limit hit, gate blocked): never leave the machine in
            // SCANNING, which the UI reads as "running".
            if (_simState.value != SimulatorState.TRADE_IN_PROGRESS) {
                _simState.value = SimulatorState.STOPPED
            }
            // Also true for the self-stopping exits above — stop loss, take profit, a blocked
            // order. Those are the paths that actually ended a ladder in practice, and they never
            // went through [stopTrading], so the streak survived them. The next Start then opened
            // mid-recovery, sized from a loss that had already been written off. Clearing here as
            // well as in [stopTrading] covers both ways a run can end.
            clearRunState()
            // The run is over whichever way it ended. Publishing it here — rather than only on the
            // paths that stop themselves — means the notification and the strategy card agree with
            // the engine about a run the user stopped.
            publishRunState { it.copy(status = RunStatus.STOPPED, activeTradeSummary = null) }
            executionJob = null
            // Every exit path above lands here, so this is the one place that can guarantee the
            // session is closed. Fires after `executionJob = null` so a Start arriving from the
            // callback does not see a live job.
            onRunEnded()
        }
    }

    /**
     * Evaluate the configured digit-frequency entry condition for a market.
     *
     * Returns true when the sample is too small to judge — the settings screen exposes a
     * minimum sample size, and refusing to trade until enough ticks accumulate is the intended
     * behaviour rather than an error.
     */
    /**
     * Last randomly drawn target digit per market.
     *
     * Retained per symbol (not globally) because the requirement is that each market works a
     * different digit, and it also prevents an immediate repeat on the same market — a uniform
     * redraw would otherwise repeat 1 time in 10, which looks like a stuck digit to the user.
     */
    private val lastRandomDigit = ConcurrentHashMap<String, Int>()

    /**
     * The digit this market's opportunity trades.
     *
     * Recovery always uses its own Over/Under barrier. Fixed mode pins the configured digit. Random
     * mode draws per market and never repeats that market's previous draw — and because the draw is
     * memoised on the symbol, the digit chosen by the sweep, the one shown in the preview and the
     * one sent as the contract barrier are always the same digit even if the sweep re-runs.
     */
    private fun digitFor(settings: TradeSettings, symbol: String, isRecovery: Boolean): Int {
        val tradeType = if (isRecovery) TradeType.OVER_UNDER else TradeType.DIFFERS
        // The barrier the ladder is actually on, not always the configured primary — a rung sized on
        // Over 2 and placed on Under 7 would be priced against a contract that was never quoted.
        if (isRecovery) return recoveryBarrierFor(settings).first
        if (settings.digitSelectionMode == DigitSelectionMode.FIXED) return settings.targetDigit
        val parked = cooldown.parkedDigits(symbol, tradeType)
        return lastRandomDigit.compute(symbol) { _, previous -> drawRandomDigit(previous, parked) }!!
    }

    private val _scanState = MutableStateFlow(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /**
     * Reset the daily loss accumulator when the calendar day rolls over.
     *
     * Without this the counter accumulated forever within a process, so a run spanning midnight
     * stayed halted on yesterday's losses.
     */
    private fun rolloverDailyLossIfNeeded() {
        if (_dailyLossAnchorDay.value == currentDayOfYear()) return
        _dailyLossAnchorDay.value = currentDayOfYear()
        lossAccounting.rolloverDay()
        _dailyLossAccumulated.value = lossAccounting.dailyLoss
    }

    private fun currentDayOfYear(): Long =
        java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR).toLong()

    private suspend fun buildPreview(
        trading: DerivTradingClient?,
        symbol: String,
        settings: TradeSettings,
        tradeType: TradeType,
        isRecovery: Boolean,
        stake: Double,
        targetDigit: Int
    ): NextTradePreview {
        // Even/Odd: the recovery contract is also a parity contract, not an Over/Under one, so
        // every number on the preview — the current rung's quote, the next rung's size — is built
        // from the DIGITEVEN/DIGITODD side rather than the Over/Under side.
        if (isEvenOddStrategy) {
            return buildParityPreview(trading, symbol, settings, isRecovery, stake, targetDigit)
        }

        // The direction the ladder is actually on, so the card names the contract that will be traded
        // rather than the configured one the ladder may have already switched away from.
        val recoveryDir = if (isRecovery) {
            recoveryBarrierFor(settings).second
        } else {
            settings.recoveryDirection
        }
        val nextSymbol = SupportedMarkets.ALL.filter { it.symbol != symbol }.random().symbol
        val nextDef = SupportedMarkets.find(nextSymbol)
        val winText = if (isRecovery) "Rolls back to Differs (Stake $" + "%.2f".format(settings.stakeAmount) + ")" else "Stays on Differs"
        val lossText = if (isRecovery) {
            "Advance to Step " + (streakLossCount + 1)
        } else {
            "Switch to " + recoveryDir + " " + settings.recoveryDigit + " Recovery"
        }

        // Differs / Over / Under have no fixed return: the payout depends on the index, barrier,
        // duration and time, so the only honest win figure is what Deriv quotes right now. Fetch a
        // live proposal for the exact contract about to be traded; profit on a win is payout - stake.
        val currency = deriv.activeAccount?.currency ?: "USD"
        val params = DerivContractParams(
            contractType = contractTypeFor(tradeType, settings, targetDigit),
            underlyingSymbol = symbol,
            currency = currency,
            amount = stake,
            barrier = targetDigit,
            duration = settings.durationValue.coerceIn(1, 100),
            durationUnit = durationUnitFor(settings)
        )
        val live = runCatching { trading?.proposal(params) }.getOrNull()
        val livePayout = live != null
        val ifWinNet = live?.let { it.payout - it.askPrice } ?: 0.0
        val winPayout = live?.payout ?: 0.0

        // The next stake on a loss is what the engine will actually place next — the recovery-ladder
        // rung, not a bare 2x. Preview it for the state after this order loses.
        //
        // Quoted live for the same reason the current rung is: the engine now sizes recovery from a
        // live payout, so a preview built from the theoretical ratio would show a number that does
        // not match what gets placed. That matters most in exactly the case the ladder exists for —
        // a deep hole, where the two can differ by a large factor. The recovery contract is quoted on
        // the market in hand; if the next rung lands on a different index the quote moves with it,
        // which is why the card is labelled as a quote rather than a promise.
        val ifLossNextStake = if (settings.martingaleEnabled) {
            // The barrier the NEXT rung will trade, which is not necessarily this one's: a recovery
            // rung that loses flips the ladder to the alternate. Quoting the current barrier would
            // preview a stake sized against a payout the next order will not be priced at.
            val (nextBarrier, nextDirection) =
                if (isRecovery && settings.recoveryAlternates) {
                    if (recoveryOnAlternate) {
                        settings.recoveryDigit to settings.recoveryDirection
                    } else {
                        settings.recoveryAlternateDigit to settings.recoveryAlternateDirection
                    }
                } else {
                    settings.recoveryDigit to settings.recoveryDirection
                }

            val nextRatio = runCatching {
                trading?.proposal(
                    DerivContractParams(
                        contractType = recoveryContractType(nextDirection),
                        underlyingSymbol = symbol,
                        currency = currency,
                        amount = roundToVenuePrecision(settings.stakeAmount),
                        barrier = nextBarrier,
                        duration = settings.durationValue.coerceIn(1, 100),
                        durationUnit = durationUnitFor(settings)
                    )
                )
            }.getOrNull()?.let { RecoveryCalculator.payoutRatioFromQuote(it.payout, it.askPrice) }

            recoveryStakeSized(
                settings,
                recoveryOutstanding + stake,
                streakLossCount + 1,
                nextBarrier,
                nextDirection,
                nextRatio
            )
        } else {
            stake
        }

        return NextTradePreview(
            marketName = nextDef.shortName,
            tradeType = tradeType,
            targetDigit = targetDigit,
            recoveryDirection = recoveryDir,
            stake = stake,
            isRecovery = isRecovery,
            recoveryStep = streakLossCount,
            ifWinAction = winText,
            ifLossAction = lossText,
            ifWinNet = ifWinNet,
            ifLossNextStake = ifLossNextStake,
            winPayout = winPayout,
            livePayout = livePayout,
            accountLabel = if (livePayout) "Live payout " + "%.2f".format(winPayout) + " " + currency
            else "Live payout at order"
        )
    }

    /**
     * The preview when the active strategy is Even/Odd.
     *
     * Every rung of this ladder is a parity contract — a loss on a DIGITEVEN rung is recovered on
     * the next DIGITEVEN/DIGITODD rung, the class picked by the same auto-qualifier rule the entry
     * uses. That is what "Even/Odd recovers Even/Odd" means, and it is what makes the preview
     * different from the Over/Under one: there is no barrier the next rung might flip between,
     * only a class, and the next class is the one the next sweep's auto-qualifier will pick.
     */
    private suspend fun buildParityPreview(
        trading: DerivTradingClient?,
        symbol: String,
        settings: TradeSettings,
        isRecovery: Boolean,
        stake: Double,
        targetDigit: Int
    ): NextTradePreview {
        val currency = deriv.activeAccount?.currency ?: "USD"
        val nextSymbol = SupportedMarkets.ALL.filter { it.symbol != symbol }.random().symbol
        val nextDef = SupportedMarkets.find(nextSymbol)

        val winText = if (isRecovery)
            "Rolls back to Even/Odd entry (Stake $" + "%.2f".format(settings.stakeAmount) + ")"
        else "Stays on Even/Odd"
        val lossText = if (isRecovery) {
            "Advance to Step " + (streakLossCount + 1)
        } else {
            "Switch to Even/Odd Recovery"
        }

        val params = DerivContractParams(
            contractType = parityContractTypeFor(targetDigit),
            underlyingSymbol = symbol,
            currency = currency,
            amount = stake,
            barrier = 0,
            duration = settings.durationValue.coerceIn(1, 100),
            durationUnit = durationUnitFor(settings)
        )
        val live = runCatching { trading?.proposal(params) }.getOrNull()
        val livePayout = live != null
        val ifWinNet = live?.let { it.payout - it.askPrice } ?: 0.0
        val winPayout = live?.payout ?: 0.0

        val ifLossNextStake = if (settings.martingaleEnabled) {
            // The next rung is a parity rung, not an Over/Under one. The class it would use is the
            // auto-qualifier's pick on the market that wins the next sweep — unknown here — so the
            // honest preview quotes the *current* class on the current market: a slightly-off next
            // rung is better than a next-rung figure built from the wrong contract type.
            val nextRatio = runCatching {
                trading?.proposal(
                    DerivContractParams(
                        contractType = parityContractTypeFor(targetDigit),
                        underlyingSymbol = symbol,
                        currency = currency,
                        amount = roundToVenuePrecision(settings.stakeAmount),
                        barrier = 0,
                        duration = settings.durationValue.coerceIn(1, 100),
                        durationUnit = durationUnitFor(settings)
                    )
                )
            }.getOrNull()?.let { RecoveryCalculator.payoutRatioFromQuote(it.payout, it.askPrice) }

            parityRecoveryRungStake(
                baseStake = settings.stakeAmount,
                outstanding = recoveryOutstanding + stake,
                streak = streakLossCount + 1,
                livePayoutRatio = nextRatio
            )
        } else {
            stake
        }

        return NextTradePreview(
            marketName = nextDef.shortName,
            tradeType = TradeType.EVEN_ODD,
            targetDigit = targetDigit,
            // The recovery "direction" slot is parity-specific: it names the class, not a side of a
            // barrier. The UI is responsible for rendering it as a class when the strategy is
            // Even/Odd, which is the only place the sentinel digit means anything.
            recoveryDirection = if (targetDigit == PARITY_EVEN_SENTINEL) "EVEN" else "ODD",
            stake = stake,
            isRecovery = isRecovery,
            recoveryStep = streakLossCount,
            ifWinAction = winText,
            ifLossAction = lossText,
            ifWinNet = ifWinNet,
            ifLossNextStake = ifLossNextStake,
            winPayout = winPayout,
            livePayout = livePayout,
            accountLabel = if (livePayout) "Live payout " + "%.2f".format(winPayout) + " " + currency
            else "Live payout at order"
        )
    }

    /**
     * The Deriv contract type for a trade.
     *
     * For the Even/Odd strategy the contract is a parity contract chosen by the class the sweep
     * named — [targetDigit] is the sentinel digit encoding Even or Odd. For the Differs strategy
     * it reads the **active** recovery barrier rather than the configured one, so every caller —
     * the order, the preview quote and the next-stake quote — resolves to the same contract the
     * ladder is actually on. Reading `settings.recoveryDirection` directly would quote
     * DIGIT_OVER for a ladder that had already switched to Under 7.
     */
    private fun contractTypeFor(
        tradeType: TradeType,
        settings: TradeSettings,
        targetDigit: Int = settings.recoveryDigit
    ): DerivContractType = when {
        isEvenOddStrategy -> parityContractTypeFor(targetDigit)
        tradeType == TradeType.OVER_UNDER ->
            recoveryContractType(recoveryBarrierFor(settings).second)
        else -> DerivContractType.DIGIT_DIFFERS
    }

    /** The Deriv contract for a recovery direction, independent of the settings object. */
    private fun recoveryContractType(direction: String): DerivContractType =
        if (direction.equals("OVER", ignoreCase = true)) {
            DerivContractType.DIGIT_OVER
        } else {
            DerivContractType.DIGIT_UNDER
        }

    /** Maps the configured duration unit to the Deriv `duration_unit` string. */
    private fun durationUnitFor(settings: TradeSettings): String = when (settings.durationUnit) {
        TradeDurationUnit.TICKS -> "t"
        TradeDurationUnit.SECONDS -> "s"
        TradeDurationUnit.MINUTES -> "m"
    }

    /**
     * Executes one real trade on the active account. Returns false if a safety gate blocked it
     * (caller should stop the loop).
     */
    private suspend fun executeRealTrade(
        settings: TradeSettings,
        config: SystemConfig,
        getMarketState: (String) -> MarketState?,
        safety: TradingSafety,
        symbol: String,
        tradeType: TradeType,
        stake: Double,
        preview: NextTradePreview,
        targetDigit: Int
    ): Boolean {
        val trading = deriv.ensureTrading()
        if (trading == null) {
            lastOrderFailure = "the trading socket is not connected — re-authenticate to restore it"
            return false
        }
        val account = deriv.activeAccount
        if (account == null) {
            lastOrderFailure = "no Deriv account is selected — pick an account on the Live Trading page"
            return false
        }
        val kind = account.kind

        // Snapshotted on entry, before the settlement below mutates either field. Both are written
        // to the position row so the history can say what the ladder was doing *at this trade* —
        // deriving them afterwards would describe this trade using a ladder that has since moved on.
        val outstandingAtPlacement = recoveryOutstanding
        val recoveryStepAtPlacement = streakLossCount

        // `targetDigit` was resolved by the caller and already accounts for random selection, so
        // the barrier below is guaranteed to be the digit whose statistics were evaluated.
        // Insufficient funds, checked before anything is sent.
        //
        // This halts the run, it does not back off. Backing off was the old behaviour, and it
        // could not have worked: the account's balance only changes by a settled trade, so an
        // account that cannot cover the stake now cannot cover it again — the retry would fail
        // identically, and the loop would spin on that failure until the stop loss or the daily
        // limit did its job. Stopping is the honest description of the state, and it leaves the
        // user a sentence they can act on ("top up, or lower the stake") instead of a bot that
        // quietly does nothing.
        val minimumNeeded = stake + STAKE_HEADROOM
        if (account.balance in 0.0..minimumNeeded) {
            lastOrderFailure = "not enough balance — this trade needs ${fmtMoney(stake)} but " +
                "the account holds ${fmtMoney(account.balance)}"
            _activeTrade.value = null
            _simState.value = SimulatorState.STOPPED
            _stopNotice.value = StopNotice(
                kind = StopKind.INSUFFICIENT_BALANCE,
                title = "Trading stopped — not enough balance",
                detail = "This trade needs ${fmtMoney(stake)} but the account holds " +
                    "${fmtMoney(account.balance)}. No more orders will be placed until the " +
                    "balance covers the stake — top the account up, or lower the stake on the " +
                    "Trade Settings page, then start again."
            )
            publishRunState { it.copy(status = RunStatus.STOPPED, activeTradeSummary = null) }
            onRunEnded()
            Log.w(tag, "run halted — ${lastOrderFailure}")
            return false
        }

        val params = DerivContractParams(
            contractType = contractTypeFor(tradeType, settings, targetDigit),
            underlyingSymbol = symbol,
            currency = account.currency,
            amount = stake,
            barrier = targetDigit,
            duration = settings.durationValue.coerceIn(1, 100),
            durationUnit = durationUnitFor(settings)
        )

        val proposal = try {
            trading.proposal(params)
        } catch (e: Exception) {
            // Deliberately broad. This previously caught only DerivApiException, so an
            // IllegalStateException from the socket layer escaped, killed the trading coroutine,
            // and left the UI showing Start pressed with nothing running — and nothing logged to
            // explain it. A failed proposal must never be able to end the session.
            Log.w(tag, "proposal failed for $symbol: ${e.message}", e)
            lastOrderFailure = "Deriv refused to price a contract on $symbol: ${e.message}"
            _recentTradeOutcome.value = SimulatedTrade(
                id = java.util.UUID.randomUUID().toString(),
                marketSymbol = symbol,
                marketName = SupportedMarkets.find(symbol).shortName,
                tradeType = tradeType,
                targetDigit = targetDigit,
                stake = 0.0,
                payout = 0.0,
                netResult = 0.0,
                outcome = TradeOutcome.LOST,
                entryQuote = 0.0,
                exitQuote = 0.0,
                exitDigit = -1,
                timestamp = System.currentTimeMillis(),
                durationText = "${settings.durationValue} ${settings.durationUnit.label.lowercase()}",
                accountId = account.id,
                contractId = "",
                currency = account.currency,
                isReal = kind == DerivAccountKind.REAL,
                note = "proposal failed: ${e.message}"
            )
            return false
        }

        val active = ActiveTradeState(
            marketSymbol = symbol,
            marketName = SupportedMarkets.find(symbol).displayName,
            tradeType = tradeType,
            targetDigit = targetDigit,
            recoveryDirection = if (isEvenOddStrategy) {
                // The class this rung trades, not a side of a barrier. The UI renders it as a
                // parity class when the strategy is Even/Odd, which is the only place the
                // sentinel digit means anything.
                if (targetDigit == PARITY_EVEN_SENTINEL) "EVEN" else "ODD"
            } else {
                settings.recoveryDirection
            },
            currentStake = stake,
            entryQuote = proposal.spot,
            remainingUnits = settings.durationValue,
            totalUnits = settings.durationValue,
            unitLabel = settings.durationUnit.label.lowercase(),
            statusText = "Awaiting settlement...",
            // For the parity strategy a recovery rung is also a parity contract, so the "in
            // recovery" flag keys off the streak rather than the Over/Under trade type.
            isRecoveryMode = when {
                isEvenOddStrategy && tradeType == TradeType.EVEN_ODD -> streakLossCount > 0
                else -> tradeType == TradeType.OVER_UNDER
            },
            recoveryStep = streakLossCount,
            winPayout = proposal.payout,
            winNetProfit = proposal.payout - proposal.askPrice,
            nextStakeOnLoss = if (settings.martingaleEnabled) {
                if (isEvenOddStrategy) {
                    // The next rung is a parity rung sized from the same class's live quote,
                    // not an Over/Under one.
                    parityRecoveryRungStake(
                        baseStake = settings.stakeAmount,
                        outstanding = recoveryOutstanding + stake,
                        streak = streakLossCount + 1
                    )
                } else {
                    recoveryStakeSized(settings, recoveryOutstanding + stake, streakLossCount + 1)
                }
            } else settings.stakeAmount,
            currency = account.currency,
            isReal = kind == DerivAccountKind.REAL,
            askPrice = proposal.askPrice
        )
        _activeTrade.value = active
        // Published the moment the contract is open, so the notification says what is being held
        // rather than a generic "scanning" while money is at risk.
        publishRunState {
            it.copy(
                status = if (active.isRecoveryMode) RunStatus.RECOVERING else RunStatus.IN_TRADE,
                activeTradeSummary = describeActiveTrade(active),
                consecutiveLosses = streakLossCount
            )
        }
        _simState.value = SimulatorState.TRADE_IN_PROGRESS

        // Safety gate before committing real money.
        //
        // The outcome is captured as a permit rather than just being an early return, so the buy
        // below cannot be reached without having demonstrably passed here.
        var orderPermit = PlaceOrderPermit.Paper
        if (kind == DerivAccountKind.REAL) {
            if (!safety.isRealArmed()) {
                lastOrderFailure = "the account is live but real-money orders are not armed — " +
                    "switch to the live account on the Live Trading page, or trade on demo"
                _activeTrade.value = null
                _simState.value = SimulatorState.SCANNING
                return false
            }
            if (safety.requirePerOrderConfirm()) {
                _simState.value = SimulatorState.WAITING_CONFIRMATION
                _pendingConfirm.value = preview.copy(accountLabel = "REAL " + account.id + " · " + "%.2f".format(stake) + " " + account.currency)
                val deferred = CompletableDeferred<Boolean>()
                confirmDeferred = deferred
                val approved = withTimeoutOrNull(30_000L) { deferred.await() } ?: false
                confirmDeferred = null
                _pendingConfirm.value = null
                if (!approved) {
                    lastOrderFailure = "the per-order confirmation was declined or timed out"
                    _activeTrade.value = null
                    _simState.value = SimulatorState.SCANNING
                    return false
                }
                _simState.value = SimulatorState.TRADE_IN_PROGRESS
            }
            // Reached only by falling through every check above.
            orderPermit = PlaceOrderPermit.RealMoneyApproved()
        }

        val buy = try {
            trading.buy(proposal.id, proposal.askPrice, orderPermit).also {
                Log.i(
                    tag,
                    "bought ${it.contractId.ifBlank { "(no id)" }} on $symbol " +
                        "digit=$targetDigit stake=${"%.2f".format(stake)} real=${kind == DerivAccountKind.REAL}"
                )
            }
        } catch (e: Exception) {
            // Same reasoning as the proposal catch above: no single failure may end trading.
            Log.w(tag, "buy failed for $symbol: ${e.message}", e)
            lastOrderFailure = "the order was rejected by Deriv: ${e.message}"
            _activeTrade.value = null
            _simState.value = SimulatorState.SCANNING
            _recentTradeOutcome.value = SimulatedTrade(
                id = java.util.UUID.randomUUID().toString(),
                marketSymbol = symbol,
                marketName = SupportedMarkets.find(symbol).shortName,
                tradeType = tradeType,
                targetDigit = targetDigit,
                stake = stake,
                payout = 0.0,
                netResult = 0.0,
                outcome = TradeOutcome.LOST,
                entryQuote = proposal.spot,
                exitQuote = proposal.spot,
                exitDigit = -1,
                timestamp = System.currentTimeMillis(),
                durationText = "${settings.durationValue} ${settings.durationUnit.label.lowercase()}",
                accountId = account.id,
                contractId = "",
                currency = account.currency,
                isReal = kind == DerivAccountKind.REAL,
                note = "buy failed: ${e.message}"
            )
            return false
        }

        _activeTrade.update { it?.copy(contractId = buy.contractId) }

        // The stake has already left the account by this point, so a throw here must not end the
        // session or lose the record of what was bought. Fall back to "not settled", which books
        // the full stake as lost — the conservative outcome, since the money is gone either way.
        val settlement = try {
            trading.awaitSettlement(buy.contractId)
        } catch (e: Exception) {
            Log.w(tag, "settlement polling failed for ${buy.contractId}: ${e.message}", e)
            DerivSettlement(buy.contractId, 0.0, 0.0, isWin = false, settled = false)
        }
        val isWin = settlement.settled && settlement.isWin

        // An unsettled contract has still been paid for: buy_price left the account and will not
        // come back unless the contract resolves in our favour. Booking it as 0.0 understated both
        // the P/L ledger and the daily-loss budget, and because `else if (settlement.settled)` also
        // skipped the loss counter, a settlement-poll timeout silently consumed real money with
        // every risk control still reporting zero. Treat an unresolved stake as at-risk loss until
        // proven otherwise; `outcome` still records it as PENDING for the UI.
        val netResult = when {
            !settlement.settled -> -buy.buyPrice
            isWin -> settlement.payout - buy.buyPrice
            else -> -buy.buyPrice
        }
        val outcome = when {
            !settlement.settled -> TradeOutcome.PENDING
            isWin -> TradeOutcome.WON
            else -> TradeOutcome.LOST
        }

        // Streak + loss/profit tracking. Unsettled contracts advance the streak too — the money
        // is committed even though the outcome is unknown.
        if (isWin) {
            streakLossCount = 0
            recoveryOutstanding = 0.0
            recoveryScanPolls = 0
            // A finished streak always resets to the primary barrier, so the next ladder opens on
            // Over 2 rather than inheriting the alternate the last one ended on.
            recoveryOnAlternate = false
            _consecutiveLosses.value = 0
            // The win ends the losing streak, so the stop loss starts again from zero.
            //
            // This is the whole point of measuring the stop loss on the *current* streak rather
            // than on everything lost so far. A recovery rung is sized to clear the hole and bank
            // a target on top, so a win on it puts the account back in profit — and the loss that
            // caused the ladder has been recovered. Counting it against the stop loss anyway meant
            // the bot kept counting a debt it had already paid: the user's stop loss was reached
            // by losses the account no longer had, and the run halted on money that was never lost.
            lossAccounting.recordWin()
            _streakLoss.value = lossAccounting.streakLoss
        } else {
            streakLossCount++
            _consecutiveLosses.value = streakLossCount
            // The whole stake is at risk on a loss, not just the net, and an unsettled contract
            // has already been paid for.
            recoveryOutstanding += stake
            // Two different measures, deliberately not one.
            //
            // `_streakLoss` is what the *stop loss* reads: the cost of the losing streak in
            // progress, reset by the win that recovers it. `_dailyLossAccumulated` is what the
            // *daily limit* reads: everything lost today, which a win does not erase — the money
            // is gone even if it was later earned back.
            //
            // They used to be the same counter, so the stop loss was really the daily limit wearing
            // a different name, and neither could be reasoned about on its own.
            if (netResult < 0) {
                lossAccounting.recordLoss(-netResult)
                _streakLoss.value = lossAccounting.streakLoss
                _dailyLossAccumulated.value = lossAccounting.dailyLoss
            }
            // A recovery rung that lost moves the ladder onto the alternate barrier for the next
            // one, when the user asked for it. Only recovery losses switch it — the entry trade
            // that opened the streak must start on the primary, or the ladder would open on
            // Under 7 having never tried Over 2. After the outstanding figure is updated, so the log
            // reports the hole the next rung actually has to clear.
            if (settings.recoveryAlternates && tradeType == TradeType.OVER_UNDER) {
                recoveryOnAlternate = !recoveryOnAlternate
                val (barrier, direction) = recoveryBarrierFor(settings)
                Log.i(
                    tag,
                    "recovery rung lost — next rung switches to $direction $barrier, clearing " +
                        "${"%.2f".format(recoveryOutstanding)}"
                )
            }
        }
        _sessionProfit.update { it + netResult }
        // The trade is settled, so the run's figures are settled too. Counted here rather than
        // anywhere else, so the notification's trade count and the trades in the history can never
        // disagree.
        publishRunState {
            it.copy(
                trades = it.trades + 1,
                wins = it.wins + if (isWin) 1 else 0,
                losses = it.losses + if (isWin) 0 else 1,
                netPnl = it.netPnl + netResult,
                consecutiveLosses = streakLossCount,
                activeTradeSummary = null,
                status = RunStatus.SCANNING
            )
        }

        // Age the cool-offs before booking this one. Order matters: a loss that trips the threshold
        // must buy the full cool-off, so the trade that caused it cannot also spend one of the
        // trades it just earned.
        cooldown.advancePastTrade()
        val entry = TradeEntry(symbol, targetDigit, tradeType)
        cooldown.record(entry, isWin = isWin)
        cooldown.state(entry)?.let { after ->
            if (after.isCoolingOff) {
                Log.i(
                    tag,
                    "${entry.describe()} is now on a ${after.tradesRemaining}-trade cool-off " +
                        "after repeated losses"
                )
            }
        }

        lastTradedMarketSymbol = symbol
        // Booked against the rotation window so the next sweep can prefer the markets that have
        // had less recent use. Every settled trade counts, win or loss — the point is how much of
        // the account's exposure went through this market, not how the market performed.
        rotation.record(symbol)

        val exitDigit = getMarketState(symbol)?.lastDigit ?: -1
        val record = SimulatedTrade(
            id = java.util.UUID.randomUUID().toString(),
            marketSymbol = symbol,
            marketName = SupportedMarkets.find(symbol).shortName,
            tradeType = tradeType,
            targetDigit = targetDigit,
            stake = stake,
            payout = if (settlement.settled) settlement.payout else 0.0,
            netResult = netResult,
            outcome = outcome,
            entryQuote = proposal.spot,
            exitQuote = proposal.spot,
            exitDigit = exitDigit,
            timestamp = System.currentTimeMillis(),
            durationText = "${settings.durationValue} ${settings.durationUnit.label.lowercase()}",
            accountId = account.id,
            contractId = buy.contractId,
            currency = account.currency,
            isReal = kind == DerivAccountKind.REAL,
            note = if (settlement.settled) "" else "settlement not confirmed within timeout"
        )

        _recentTradeOutcome.value = record
        _activeTrade.value = null
        _simState.value = SimulatorState.SCANNING

        scope.launch(Dispatchers.IO) {
            database.simulationDao().insertPosition(
                SimulationPositionEntity(
                    userId = userIdProvider(),
                    sessionId = sessionIdProvider(),
                    marketSymbol = symbol,
                    marketName = SupportedMarkets.find(symbol).shortName,
                    scenarioType = tradeType.displayName,
                    strategyId = strategyId,
                    simulatedStake = stake,
                    simulatedPayout = if (settlement.settled) settlement.payout else 0.0,
                    simulatedNetResult = netResult,
                    outcome = outcome.name,
                    targetDigit = targetDigit,
                    entryQuote = proposal.spot,
                    exitQuote = proposal.spot,
                    exitDigit = exitDigit,
                    openedAt = System.currentTimeMillis(),
                    settledAt = System.currentTimeMillis(),
                    accountId = account.id,
                    contractId = buy.contractId,
                    isReal = kind == DerivAccountKind.REAL,
                    askPrice = buy.buyPrice,
                    // Profit per unit staked, straight from the quote this order was priced at.
                    // Null when the venue quoted nothing usable, so the detail screen says
                    // "Unavailable" instead of showing a 0% return that looks like a real figure.
                    payoutRatio = RecoveryCalculator.payoutRatioFromQuote(
                        payout = if (settlement.settled) settlement.payout else 0.0,
                        askPrice = buy.buyPrice
                    ),
                    recoveryStep = recoveryStepAtPlacement,
                    outstandingBefore = outstandingAtPlacement
                )
            )
        }
        // No balance refresh here: the trading socket pushes the balance on buy and again on
        // settlement, so the figure on screen is already current. A REST re-fetch at this point
        // would race the push and could overwrite a newer number with an older one.
        return true
    }

    fun resolvePendingConfirmation(approved: Boolean) {
        confirmDeferred?.complete(approved)
    }

    fun dismissOutcomeBanner() {
        _recentTradeOutcome.value = null
    }

    /**
     * Ends the run at the next safe point.
     *
     * @return true when the run has ended now; false when it was deferred and the caller must
     *   leave the session open until the open contract settles. A caller that closes the session
     *   regardless writes the deferred trade against a session that has already ended.
     *
     * When a contract is open, the request is *deferred* rather than executed. Cancelling the
     * coroutine mid-settlement abandoned the contract: the stake had already left the account, but
     * nothing was written to the database, so the trade vanished from history while the balance
     * moved by an amount no record accounted for. Pressing Stop means "place no further orders",
     * not "throw away what is already in flight". The loop settles it, records it, then exits.
     */
    fun stopTrading(): Boolean {
        // A confirmation the user never got to answer is them cancelling, not a failure.
        confirmDeferred?.complete(false)
        confirmDeferred = null
        _pendingConfirm.value = null

        val contractOpen = _simState.value == SimulatorState.TRADE_IN_PROGRESS && _activeTrade.value != null
        if (contractOpen) {
            stopRequested = true
            _stopNotice.value = StopNotice(
                kind = StopKind.USER,
                title = "Stopping — waiting for the open contract",
                detail = "No further orders will be placed. The contract already in flight will " +
                    "settle and be recorded before the run ends."
            )
            return false
        }

        executionJob?.cancel()
        _simState.value = SimulatorState.STOPPED
        _activeTrade.value = null
        publishRunState { it.copy(status = RunStatus.STOPPED, activeTradeSummary = null) }
        // The coroutine is cancelled, so it will never reach its own `onRunEnded()`. The UI's Stop
        // is therefore the only path out of a user-initiated run and it has to close the session
        // itself. `endSession` is idempotent, so this cannot double-close a run that ended on its
        // own.
        onRunEnded()
        // A run has ended, so the ladder must not survive into the next one. See [clearRunState].
        clearRunState()
        // Otherwise the card keeps showing the last market it was evaluating, which reads as
        // "still working" after the user has stopped it.
        _scanState.value = ScanState.Idle
        return true
    }

    fun resetDailyLoss() {
        _dailyLossAccumulated.value = 0.0
        streakLossCount = 0
    }

    private fun selectMarket(
        config: SystemConfig,
        settings: TradeSettings,
        tradeType: TradeType,
        getMarketState: (String) -> MarketState?
    ): String {
        // Recovery uses a single fixed barrier, so "this market is on a break" reduces to "this
        // market's recovery entry is on a break".
        val parity = isEvenOddStrategy
        val barrier = when {
            parity -> null
            tradeType == TradeType.OVER_UNDER -> settings.recoveryDigit
            else -> null
        }

        fun isParked(symbol: String): Boolean = when {
            parity -> {
                // The class is the market's own, so a market is parked on an entry only when its
                // class has lost repeatedly. When it has no data yet there is no entry to be
                // parked on, and the next sweep will name one.
                val cls = parityClassFor(settings, symbol, getMarketState)
                cooldown.state(TradeEntry(symbol, cls, TradeType.EVEN_ODD))?.isCoolingOff == true
            }
            barrier != null ->
                cooldown.state(TradeEntry(symbol, barrier, tradeType))?.isCoolingOff == true
            else -> false
        }

        fun pick(): String {
            val free = SupportedMarkets.ALL
                .filter { it.symbol != lastTradedMarketSymbol && !isParked(it.symbol) }
            val fallback = SupportedMarkets.ALL.filter { it.symbol != lastTradedMarketSymbol }
            // Every market parked means the whole recovery set is on a break. Refusing outright
            // would strand an outstanding stake, so the cool-off gives way and the caller logs it.
            val pool = free.ifEmpty { fallback }.ifEmpty { SupportedMarkets.ALL.toList() }
            return pool.random().symbol
        }

        // The forced-recovery fallback market, when the sweep found nothing to trade. It goes
        // through the same rotation as the normal path, so a recovery rung does not silently undo
        // the spreading the entry trades were doing.
        if (config.randomMarkets) {
            return pick()
        }
        val held = lastTradedMarketSymbol
        if (held.isNotBlank() && !isParked(held) && !rotation.isOverFrequency(held)) return held
        return pick()
    }

    /**
     * Forget everything the previous run left behind, so a new session starts clean.
     *
     * The martingale ladder is the part that matters most. `streakLossCount` and
     * `recoveryOutstanding` are engine fields that outlive the run that produced them, so a ladder
     * that stopped at step 4 left step 4 in force: the next Start opened with `isRecovery` already
     * true, sized the first order from the *previous* session's outstanding loss, and could never
     * climb back to the base stake because step 1 was already behind it.
     *
     * Resetting only on a win made this worse — the ladder was meant to be abandoned at a stop
     * loss, which is precisely when a win does not happen. `stopTrading` is the only place that
     * knows a run has ended, so it resets here rather than at the next Start: by then the fields
     * have already leaked into whatever else reads them.
     *
     * The loss cool-off is deliberately *not* in that list. Every field above holds money hostage:
     * leave one behind and the next run inherits a ladder rung it has to pay off. A cool-off holds
     * nothing — it only ever declines to take an order, it expires on its own, and a bot restarted
     * into a market that has been losing is the situation the rule exists for. Wiping it here would
     * make the rule last only as long as a single uninterrupted run.
     */
    private fun clearRunState() {
        streakLossCount = 0
        recoveryOutstanding = 0.0
        recoveryScanPolls = 0
        recoveryOnAlternate = false
        _consecutiveLosses.value = 0
        _sessionProfit.value = 0.0
        // The streak ends with the run, so the stop loss starts fresh next time. The *daily*
        // accumulator is deliberately not touched: it is the day's total, and ending a run does
        // not give the money back. It used to be reset here, which meant a user could stop and
        // restart to buy themselves a fresh daily loss budget.
        lossAccounting.endRun()
        _streakLoss.value = lossAccounting.streakLoss
        // Unlike the ladder state and the cool-off, the skip list is not worth keeping: a market
        // that refused an order thirty seconds ago is a fact about the previous run, and the next
        // one should evaluate the whole field from scratch.
        failedMarkets.clear()
        stopRequested = false
    }
}

enum class SimulatorState {
    IDLE,
    SCANNING,
    TRADE_IN_PROGRESS,
    WAITING_CONFIRMATION,
    STOPPED
}

/** What ended the last run. Drives both the banner's wording and its colour. */
enum class StopKind {
    /** The user pressed Stop. Not an error, so it carries no notice at all. */
    USER,
    /** Per-run loss ceiling. */
    STOP_LOSS,
    /** Whole-day loss budget. */
    DAILY_LIMIT,
    /** Session profit target. */
    TAKE_PROFIT,
    /** An order was refused by a safety gate or the socket dropped. */
    SAFETY_GATE,
    /** The account cannot cover the stake. Halts the run, because a balance cannot grow itself. */
    INSUFFICIENT_BALANCE
}

/**
 * A plain-language account of why trading stopped.
 *
 * Carries both a headline and the numbers behind it, because "you hit your limit" without saying
 * which limit, by how much, or what it means leaves the user guessing which setting to change.
 */
data class StopNotice(
    val kind: StopKind,
    val title: String,
    val detail: String
)

/** Two-decimal money formatting for the stop notices, matching what the account screens show. */
private fun fmtMoney(amount: Double): String =
    if (!amount.isFinite()) "—" else "$${"%.2f".format(amount)}"

/**
 * What the engine is looking at right now, published on every poll.
 *
 * Carries the whole [MarketSweep], not a single market, because the decision now spans every
 * subscribed market: a screen that showed only one would misrepresent what was measured.
 */
data class ScanState(
    val isScanning: Boolean = false,
    val symbol: String? = null,
    val targetDigit: Int? = null,
    val observedPercent: Double? = null,
    val thresholdPercent: Double? = null,
    val isGreaterThan: Boolean = true,
    val sampleSize: Int = 0,
    val qualified: Boolean = false,
    val reason: String = "",
    val lastSweep: MarketSweep = MarketSweep()
) {
    /** The market the order went into, if one was chosen. */
    val candidate: MarketReading? get() = lastSweep.candidate

    companion object {
        val Idle = ScanState()
    }
}

data class ActiveTradeState(
    val id: String = java.util.UUID.randomUUID().toString(),
    val marketSymbol: String = "R_25",
    val marketName: String = "Volatility 25",
    val tradeType: TradeType = TradeType.DIFFERS,
    val targetDigit: Int = 2,
    val recoveryDirection: String = "OVER",
    val currentStake: Double = 1.00,
    val entryQuote: Double = 0.0,
    val remainingUnits: Int = 5,
    val totalUnits: Int = 5,
    val unitLabel: String = "ticks",
    val statusText: String = "Awaiting...",
    val isRecoveryMode: Boolean = false,
    val recoveryStep: Int = 0,
    val winPayout: Double = 1.10,
    val winNetProfit: Double = 0.10,
    val nextStakeOnLoss: Double = 2.00,
    val contractId: String = "",
    val currency: String = "USD",
    val isReal: Boolean = false,
    val askPrice: Double = 0.0
)

data class NextTradePreview(
    val marketName: String = "Volatility 50",
    val tradeType: TradeType = TradeType.DIFFERS,
    val targetDigit: Int = 2,
    val recoveryDirection: String = "OVER",
    val stake: Double = 1.00,
    val isRecovery: Boolean = false,
    val recoveryStep: Int = 0,
    val ifWinAction: String = "Stays on Differs",
    val ifLossAction: String = "Switch to Over/Under Recovery",
    val ifWinNet: Double = 0.0,
    val ifLossNextStake: Double = 0.0,
    val winPayout: Double = 0.0,
    val livePayout: Boolean = false,
    val accountLabel: String = ""
)

/** How often the loop re-evaluates the entry condition while waiting for a signal. */
private const val POLL_INTERVAL_MS = 1000L


/**
 * How many consecutive polls a recovery rung may spend waiting for a qualifying Over/Under market
 * before it is placed anyway.
 *
 * The recovery entry condition is the same threshold the Differs entries use, applied to
 * P(over/under the barrier). A threshold tuned for Differs — which sits near 90% — rarely fires on
 * an Over/Under probability, so without this the ladder would stall on the first loss and the
 * outstanding stake would never be chased. Two minutes at the one-second poll interval is long
 * enough that a real signal is not missed, and short enough that recovery still happens.
 */
private const val MAX_RECOVERY_SCAN_POLLS = 120

/**
 * How long to wait after an order fails to place, before the loop looks again.
 *
 * Long enough that a socket that is reconnecting gets a real chance to come back rather than being
 * hammered once a second, short enough that a transient blip is invisible. The loop keeps scanning
 * through this — it is a backoff, not a stop.
 */
private const val ORDER_FAILURE_BACKOFF_MS = 5000L

/**
 * How long a market is set aside after it refuses an order.
 *
 * Long enough for the bot to spend its next few attempts elsewhere instead of re-sending into a
 * book that has already said no, short enough that the market is not written off for the session.
 */
private const val MARKET_SKIP_AFTER_MS = 30_000L

/**
 * Spare balance required on top of a stake before the order is attempted.
 *
 * Deriv reserves the stake and settles fees against it, so an account sitting exactly on the stake
 * is refused even though the arithmetic looks sufficient. Checking here with a small margin says
 * "not enough balance" instead of letting the venue return an error code nobody surfaces.
 */
private const val STAKE_HEADROOM = 0.01
