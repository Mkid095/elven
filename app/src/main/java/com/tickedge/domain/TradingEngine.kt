package com.tickedge.domain

import com.tickedge.data.deriv.DerivClient
import com.tickedge.data.deriv.DerivAccountKind
import com.tickedge.data.deriv.DerivApiException
import com.tickedge.data.deriv.DerivContractParams
import com.tickedge.data.deriv.DerivContractType
import com.tickedge.data.local.AppDatabase
import com.tickedge.data.local.SimulationPositionEntity
import com.tickedge.data.model.MarketState
import com.tickedge.data.model.SimulatedTrade
import com.tickedge.data.model.SupportedMarkets
import com.tickedge.data.model.SystemConfig
import com.tickedge.data.model.TradeDurationUnit
import com.tickedge.data.model.TradeOutcome
import com.tickedge.data.model.TradeSettings
import com.tickedge.data.model.TradeType
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
    private val userIdProvider: () -> String
) {
    private val _simState = MutableStateFlow(SimulatorState.IDLE)
    val simState: StateFlow<SimulatorState> = _simState.asStateFlow()

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
     * Net profit for the current run, used to enforce the take-profit target. Wins add and losses
     * subtract, so it is a true net figure rather than a gross-loss counter.
     */
    private val _sessionProfit = MutableStateFlow(0.0)
    val sessionProfit: StateFlow<Double> = _sessionProfit.asStateFlow()

    /** Day-of-year on which the loss accumulator was last reset, for midnight rollover. */
    private val _dailyLossAnchorDay = MutableStateFlow(-1L)

    private val _pendingConfirm = MutableStateFlow<NextTradePreview?>(null)
    val pendingConfirmation: StateFlow<NextTradePreview?> = _pendingConfirm.asStateFlow()

    private var executionJob: Job? = null
    private var confirmDeferred: CompletableDeferred<Boolean>? = null
    private var streakLossCount = 0
    private var lastTradedMarketSymbol = ""
    private var tradesOnCurrentMarket = 0

    /** Live safety/permission inputs, resolved each trade so the UI can change them at runtime. */
    data class TradingSafety(
        val activeKind: () -> DerivAccountKind,
        val isRealArmed: () -> Boolean,
        val requirePerOrderConfirm: () -> Boolean,
        val maxMartingaleSteps: () -> Int
    )

    fun startTrading(
        settings: TradeSettings,
        config: SystemConfig,
        getMarketState: (String) -> MarketState?,
        safety: TradingSafety
    ) {
        if (_simState.value == SimulatorState.TRADE_IN_PROGRESS || _simState.value == SimulatorState.SCANNING) {
            return
        }

        _simState.value = SimulatorState.SCANNING
        executionJob = scope.launch(Dispatchers.Default) {
            while (isActive && _simState.value != SimulatorState.STOPPED) {
                rolloverDailyLossIfNeeded()

                // Risk-management stop conditions.
                val dailyLimitHit = (config.stopTradingOnLimit &&
                    _dailyLossAccumulated.value >= config.maxDailyLoss) ||
                    _dailyLossAccumulated.value >= settings.stopLoss
                if (dailyLimitHit) {
                    _simState.value = SimulatorState.STOPPED
                    break
                }

                // Take-profit was previously collected in the UI and persisted but never read
                // here, so a configured profit target could never halt a run.
                val takeProfitHit = settings.takeProfit > 0.0 &&
                    _sessionProfit.value >= settings.takeProfit
                if (takeProfitHit) {
                    _simState.value = SimulatorState.STOPPED
                    break
                }

                val chosenSymbol = selectMarket(config)
                val isRecovery = streakLossCount > 0 && settings.martingaleEnabled &&
                    streakLossCount <= safety.maxMartingaleSteps()

                // Past the Martingale cap, stop rather than silently reverting to the base stake
                // while the UI still advertises "IN RECOVERY (STEP n)" and the previous stake is
                // still unrecovered.
                if (streakLossCount > safety.maxMartingaleSteps() && settings.martingaleEnabled) {
                    _simState.value = SimulatorState.STOPPED
                    break
                }

                val tradeType = if (isRecovery) TradeType.OVER_UNDER else TradeType.DIFFERS
                val stake = if (isRecovery) {
                    settings.stakeAmount * Math.pow(2.0, streakLossCount.toDouble())
                } else {
                    settings.stakeAmount
                }

                // Entry condition. Recovery trades must always execute to recover exposure, but a
                // fresh Differs entry previously fired on a fixed 1.5s cadence regardless of the
                // digit statistics — the threshold settings were recorded and never consulted.
                if (!isRecovery && !entryConditionMet(settings, chosenSymbol, getMarketState)) {
                    _simState.value = SimulatorState.SCANNING
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                val preview = buildPreview(chosenSymbol, settings, tradeType, isRecovery, stake)
                _nextTrade.value = preview

                val executed = executeRealTrade(settings, config, getMarketState, safety, chosenSymbol, tradeType, stake, preview)
                if (!executed) {
                    // A safety gate blocked the order (e.g. real account not armed), or the
                    // socket dropped. Previously this only broke the loop, leaving _simState on
                    // SCANNING — the UI kept showing "Stop Automated Trading" with Start
                    // unreachable, so the user had to press Stop to clear a loop that had exited.
                    _simState.value = SimulatorState.STOPPED
                    break
                }

                // Short intermission so the user sees the outcome before the next trade.
                delay(1500L)
            }
            // Normal exit (limit hit, cap reached, gate blocked): never leave the machine in
            // SCANNING, which the UI reads as "running".
            if (_simState.value != SimulatorState.TRADE_IN_PROGRESS) {
                _simState.value = SimulatorState.STOPPED
            }
        }
    }

    /**
     * Evaluate the configured digit-frequency entry condition for a market.
     *
     * Returns true when the sample is too small to judge — the settings screen exposes a
     * minimum sample size, and refusing to trade until enough ticks accumulate is the intended
     * behaviour rather than an error.
     */
    private fun entryConditionMet(
        settings: TradeSettings,
        symbol: String,
        getMarketState: (String) -> MarketState?
    ): Boolean {
        val state = getMarketState(symbol) ?: return false
        val freq = state.digitFrequencies.firstOrNull { it.digit == settings.targetDigit }
            ?: return false
        return DigitAnalysisEngine.evaluateCondition(
            observedPercent = freq.percentage,
            thresholdPercent = settings.thresholdPercent,
            operator = settings.comparisonOperator,
            sampleSize = state.sampleSize,
            minimumSampleSize = MIN_SAMPLE_SIZE
        )
    }

    /**
     * Reset the daily loss accumulator when the calendar day rolls over.
     *
     * Without this the counter accumulated forever within a process, so a run spanning midnight
     * stayed halted on yesterday's losses.
     */
    private fun rolloverDailyLossIfNeeded() {
        if (_dailyLossAnchorDay.value == currentDayOfYear()) return
        _dailyLossAnchorDay.value = currentDayOfYear()
        _dailyLossAccumulated.value = 0.0
    }

    private fun currentDayOfYear(): Long =
        java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR).toLong()

    private fun buildPreview(
        symbol: String,
        settings: TradeSettings,
        tradeType: TradeType,
        isRecovery: Boolean,
        stake: Double
    ): NextTradePreview {
        val def = SupportedMarkets.find(symbol)
        val targetDigit = if (isRecovery) settings.recoveryDigit else settings.targetDigit
        val recoveryDir = settings.recoveryDirection
        val nextSymbol = SupportedMarkets.ALL.filter { it.symbol != symbol }.random().symbol
        val nextDef = SupportedMarkets.find(nextSymbol)
        val winText = if (isRecovery) "Rolls back to Differs (Stake $" + "%.2f".format(settings.stakeAmount) + ")" else "Stays on Differs"
        val lossText = if (isRecovery) "Advance to Step " + (streakLossCount + 1) else "Switch to " + recoveryDir + " " + targetDigit + " Recovery"
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
            ifWinNet = if (isRecovery) stake * 0.95 else stake * 0.10,
            ifLossNextStake = if (settings.martingaleEnabled) stake * 2.0 else stake,
            accountLabel = "awaiting proposal"
        )
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
        preview: NextTradePreview
    ): Boolean {
        val trading = deriv.ensureTrading() ?: return false
        val account = deriv.activeAccount ?: return false
        val kind = account.kind

        // Build the digit contract.
        val targetDigit = if (tradeType == TradeType.OVER_UNDER) settings.recoveryDigit else settings.targetDigit
        val contractType = when {
            tradeType == TradeType.OVER_UNDER &&
                settings.recoveryDirection.equals("OVER", ignoreCase = true) -> DerivContractType.DIGIT_OVER
            tradeType == TradeType.OVER_UNDER -> DerivContractType.DIGIT_UNDER
            else -> DerivContractType.DIGIT_DIFFERS
        }
        val durationUnit = when (settings.durationUnit) {
            TradeDurationUnit.TICKS -> "t"
            TradeDurationUnit.SECONDS -> "s"
            TradeDurationUnit.MINUTES -> "m"
        }
        val params = DerivContractParams(
            contractType = contractType,
            underlyingSymbol = symbol,
            currency = account.currency,
            amount = stake,
            barrier = targetDigit,
            duration = settings.durationValue.coerceIn(1, 100),
            durationUnit = durationUnit
        )

        val proposal = try {
            trading.proposal(params)
        } catch (e: DerivApiException) {
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
            recoveryDirection = settings.recoveryDirection,
            currentStake = stake,
            entryQuote = proposal.spot,
            remainingUnits = settings.durationValue,
            totalUnits = settings.durationValue,
            unitLabel = settings.durationUnit.label.lowercase(),
            statusText = "Awaiting settlement...",
            isRecoveryMode = tradeType == TradeType.OVER_UNDER,
            recoveryStep = streakLossCount,
            winPayout = proposal.payout,
            winNetProfit = proposal.payout - proposal.askPrice,
            nextStakeOnLoss = if (settings.martingaleEnabled) stake * 2.0 else settings.stakeAmount,
            currency = account.currency,
            isReal = kind == DerivAccountKind.REAL,
            askPrice = proposal.askPrice
        )
        _activeTrade.value = active
        _simState.value = SimulatorState.TRADE_IN_PROGRESS

        // Safety gate before committing real money.
        if (kind == DerivAccountKind.REAL) {
            if (!safety.isRealArmed()) {
                _activeTrade.value = null
                _simState.value = SimulatorState.STOPPED
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
                    _activeTrade.value = null
                    _simState.value = SimulatorState.STOPPED
                    return false
                }
                _simState.value = SimulatorState.TRADE_IN_PROGRESS
            }
        }

        val buy = try {
            trading.buy(proposal.id, proposal.askPrice)
        } catch (e: DerivApiException) {
            _activeTrade.value = null
            _simState.value = SimulatorState.STOPPED
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

        val settlement = trading.awaitSettlement(buy.contractId)
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
            _consecutiveLosses.value = 0
        } else {
            streakLossCount++
            _consecutiveLosses.value = streakLossCount
            if (netResult < 0) _dailyLossAccumulated.update { it + (-netResult) }
        }
        _sessionProfit.update { it + netResult }
        lastTradedMarketSymbol = symbol
        tradesOnCurrentMarket++

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
                    sessionId = 1L,
                    marketSymbol = symbol,
                    marketName = SupportedMarkets.find(symbol).shortName,
                    scenarioType = tradeType.displayName,
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
                    isReal = kind == DerivAccountKind.REAL
                )
            )
            deriv.refreshBalances()
        }
        return true
    }

    fun resolvePendingConfirmation(approved: Boolean) {
        confirmDeferred?.complete(approved)
    }

    fun dismissOutcomeBanner() {
        _recentTradeOutcome.value = null
    }

    fun stopTrading() {
        executionJob?.cancel()
        confirmDeferred?.complete(false)
        confirmDeferred = null
        _simState.value = SimulatorState.STOPPED
        _activeTrade.value = null
        _pendingConfirm.value = null
    }

    fun resetDailyLoss() {
        _dailyLossAccumulated.value = 0.0
        streakLossCount = 0
    }

    private fun selectMarket(config: SystemConfig): String {
        return if (config.randomMarkets) {
            val eligible = SupportedMarkets.ALL.filter { it.symbol != lastTradedMarketSymbol }
            eligible.random().symbol
        } else {
            if (tradesOnCurrentMarket >= config.maxTradesPerMarket) {
                tradesOnCurrentMarket = 0
                val eligible = SupportedMarkets.ALL.filter { it.symbol != lastTradedMarketSymbol }
                eligible.random().symbol
            } else {
                lastTradedMarketSymbol.ifEmpty { SupportedMarkets.ALL[0].symbol }
            }
        }
    }
}

enum class SimulatorState {
    IDLE,
    SCANNING,
    TRADE_IN_PROGRESS,
    WAITING_CONFIRMATION,
    STOPPED
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
    val accountLabel: String = ""
)

/** How often the loop re-evaluates the entry condition while waiting for a signal. */
private const val POLL_INTERVAL_MS = 1000L

/** Minimum ticks required before the digit-frequency entry condition is trusted. */
private const val MIN_SAMPLE_SIZE = 10
