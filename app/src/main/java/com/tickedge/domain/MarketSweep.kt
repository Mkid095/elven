package com.tickedge.domain

import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.DigitFrequency
import com.tickedge.data.model.EvenOddMode
import com.tickedge.data.model.isAtLeast
import com.tickedge.data.model.MarketState
import com.tickedge.data.model.SupportedMarkets
import com.tickedge.data.model.SystemConfig
import com.tickedge.data.model.TradeSettings
import com.tickedge.data.model.TradeType

/**
 * One market's reading from a sweep.
 *
 * Kept even when the market did not qualify: the screen shows the whole field, because "scanned
 * five markets, none met the condition" is a different statement from "no data at all", and only
 * the former tells the user the bot is working.
 *
 * [observedPercent] is the probability *that market's contract pays out on* — P(differs from digit)
 * for a Differs read, P(over/under barrier) for a recovery read — which is why the same threshold
 * means different numbers on the two kinds of sweep.
 *
 * Null [observedPercent] means nothing can honestly be claimed yet (no ticks, or too few), which is
 * deliberately distinct from `0.0`: "not measured" and "measured, zero" are different facts.
 */
data class MarketReading(
    val symbol: String,
    val digit: Int,
    val observedPercent: Double?,
    val sampleSize: Int,
    val qualifies: Boolean,
    /** How far past (or short of) the threshold this market sits, in percentage points. */
    val marginPercent: Double?,
    /**
     * Trades still to sit this market's entry out for, when it is currently on a cool-off.
     *
     * Carried on the reading rather than kept beside it so the row can explain itself in the same
     * place that shows the percentage — a market that is skipped for having lost, while showing a
     * qualifying number next to it, otherwise reads as a bug in the maths.
     */
    val coolOffRemaining: Int = 0,
    /**
     * How many of the rotation window's recent trades this market carried.
     *
     * Shown on the row for the same reason the cool-off is: a market that is being passed over
     * because it has been used more than its share looks identical to one that is being passed
     * over because its numbers are bad, and only the user can tell which the bot meant.
     */
    val tradesInWindow: Int = 0,
    /** True when this market has carried its share of the window and is being held back. */
    val overFrequency: Boolean = false,
    /**
     * Set on readings produced by the Even/Odd sweep, naming the parity class the market is being
     * judged on ("Even" or "Odd").
     *
     * The parity sweeps store the class in [digit] as a sentinel (0 = even, 9 = odd) because the
     * whole pipeline — the order, the settlement, the history row — already carries one digit
     * column, and the UI would otherwise have to reverse-engineer the class from the number. The
     * label is the human-readable form of that same value.
     */
    val parityLabel: String? = null,
    val reason: String = ""
)

/** The result of evaluating every subscribed market in one pass. */
data class MarketSweep(
    val readings: List<MarketReading> = emptyList(),
    /** The market the order goes into, or null when nothing qualified. */
    val candidate: MarketReading? = null,
    /**
     * Set when the sweep declined to trade even though something qualified.
     *
     * Non-null only in the one case where the no-repeat rule forced a hold: the market that just
     * carried a trade is the *sole* qualifier. Surfaced so the screen can say the bot is waiting
     * deliberately, rather than showing a qualifying market and no trade — which reads as a stall
     * and is indistinguishable, to the user, from a bug.
     */
    val heldBack: MarketReading? = null
) {
    val evaluatedCount: Int get() = readings.size
    val qualifyingCount: Int get() = readings.count { it.qualifies }

    /** Markets whose entry is currently on a cool-off after repeated losses. */
    val coolingOffCount: Int get() = readings.count { it.coolOffRemaining > 0 }

    /** Markets being passed over because they have already carried their share of recent trades. */
    val rotationSkippedCount: Int get() = readings.count { it.overFrequency }
}

/**
 * Evaluates **every** subscribed market against the entry condition and returns both the full set
 * of readings and the market to trade.
 *
 * This is the whole entry model in one place: one pass over all five markets, each judged on the
 * probability its own contract pays out on, then a single pick among whatever cleared the bar.
 *
 * The previous shape drew one market at random and tested only that one, sleeping a second when it
 * failed. Four of the five markets were therefore invisible to the decision every cycle, and a
 * qualifying market could go unseen for many seconds while the bot worked through the others.
 *
 * Pure and free of engine state so the rule that spends real money can be tested without a socket,
 * a database or a coroutine.
 *
 * @param excludeSymbol the market that just carried a trade, which must not be taken again while
 *   any other market qualifies. Null means nothing has been traded yet.
 * @param digitFor resolves the digit a given market would trade. Random mode supplies a per-market
 *   draw here, so "a qualifying digit anywhere in the field" is an opportunity on whichever market
 *   produced it — randomisation widens what is considered and never substitutes for the threshold.
 * @param coolOff reports the cool-off state of an exact contract, or null when it is tradeable.
 *   Consulted *after* the threshold rather than instead of it: the reading is still computed and
 *   still shown, so a market sitting out its cool-off can be seen to be qualifying, which is what
 *   makes the skip look deliberate instead of broken.
 */
fun sweepMarkets(
    settings: TradeSettings,
    config: SystemConfig,
    isRecovery: Boolean,
    excludeSymbol: String?,
    digitFor: (String) -> Int,
    getMarketState: (String) -> MarketState?,
    coolOff: (TradeEntry) -> CooldownState? = { null },
    /**
     * Overrides which recovery side P(…) is measuring, for a ladder that has switched barriers.
     *
     * Null reads [TradeSettings.recoveryDirection]. It has to be an override rather than a settings
     * mutation because the ladder can flip between rungs: mutating the settings mid-streak would
     * also change the *sizing* that was already committed for the outstanding loss.
     */
    recoveryIsOver: Boolean? = null,
    /**
     * How often each market has been traded lately, so the pick can rotate instead of clustering.
     *
     * Null leaves the decision exactly as it was — every qualifying market equally eligible — which
     * is what a caller with no trading history yet should get.
     */
    rotation: MarketRotation? = null
): MarketSweep {
    val isOver = recoveryIsOver ?: settings.recoveryDirection.equals("OVER", ignoreCase = true)
    val tradeType = if (isRecovery) TradeType.OVER_UNDER else TradeType.DIFFERS

    val readings = SupportedMarkets.ALL.map { market ->
        val symbol = market.symbol
        val digit = digitFor(symbol)
        val state = getMarketState(symbol)

        // Resolved up front, before the data checks, so that EVERY market reports its cool-off
        // state — including one with no ticks yet.
        //
        // This ordering is load-bearing. The deadlock guard in the trading loop decides whether the
        // whole field is parked by comparing the parked count against the evaluated count, and a
        // cooled-off market that was still accumulating ticks used to return early with
        // `coolOffRemaining = 0`. That undercount meant the guard never fired, the cool-off never
        // aged, and with every market parked the bot sat idle forever with nothing on screen
        // explaining why. A market on a break is parked whether or not it has data yet.
        val cool = coolOff(TradeEntry(symbol, digit, tradeType))
        // `coolOff` reports what is *known* about an entry, which includes a tally of losses that
        // has not yet reached the cool-off threshold — a state whose `tradesRemaining` is 0 and
        // which is perfectly tradable. Testing for `cool == null` instead of `cool.isCoolingOff`
        // therefore treated "lost once or twice" as "parked forever", and the reason line rendered
        // the literal "cooling off — 0 trades left". The entry stayed untradeable until something
        // else won on it, so the tally could never be worked back down: one or two unlucky trades
        // permanently closed a market off.
        val parked = cool?.takeIf { it.isCoolingOff }
        val coolRemaining = parked?.tradesRemaining ?: 0
        val coolReason = if (parked == null) {
            ""
        } else {
            "cooling off — ${parked.tradesRemaining} trade" +
                (if (parked.tradesRemaining == 1) "" else "s") +
                " left after repeated losses here"
        }

        // Read the rotation for every market, before the data checks, for the same reason: a market
        // being held back for frequency has to say so whether or not it has ticks yet.
        val used = rotation?.tradesInWindow(symbol) ?: 0
        val overUsed = rotation?.isOverFrequency(symbol) == true
        val frequencyNote = if (used <= 0) {
            ""
        } else {
            "$used of the last ${DEFAULT_ROTATION_WINDOW_TRADES} trades" +
                if (overUsed) " — held back so the others catch up" else ""
        }
        val skipReason = listOf(coolReason, frequencyNote).filter { it.isNotBlank() }
            .joinToString(" · ")

        when {
            state == null ->
                MarketReading(
                    symbol = symbol, digit = digit, observedPercent = null, sampleSize = 0,
                    qualifies = false, marginPercent = null,
                    coolOffRemaining = coolRemaining,
                    tradesInWindow = used,
                    overFrequency = overUsed,
                    reason = skipReason.ifBlank { "no tick data yet" }
                )

            state.sampleSize < MIN_SAMPLE_SIZE ->
                MarketReading(
                    symbol = symbol, digit = digit, observedPercent = null,
                    sampleSize = state.sampleSize, qualifies = false, marginPercent = null,
                    coolOffRemaining = coolRemaining,
                    tradesInWindow = used,
                    overFrequency = overUsed,
                    reason = skipReason.ifBlank {
                        "needs $MIN_SAMPLE_SIZE ticks, has ${state.sampleSize}"
                    }
                )

            else -> {
                val observed = DigitAnalysisEngine.observedPercentForTrade(
                    frequencies = state.digitFrequencies,
                    targetDigit = digit,
                    isRecovery = isRecovery,
                    isOver = isOver
                )
                // The condition that applies depends on which contract is being considered. This
                // used to apply the Differs threshold to Over/Under readings too, which compared
                // a ~50%-scale recovery probability against a ~90%-scale threshold — a comparison
                // no recovery market could satisfy, so recovery never qualified on its own terms.
                val (operator, threshold) = settings.conditionFor(isRecovery)
                val meetsCondition = DigitAnalysisEngine.evaluateCondition(
                    observedPercent = observed,
                    thresholdPercent = threshold,
                    operator = operator,
                    sampleSize = state.sampleSize,
                    minimumSampleSize = MIN_SAMPLE_SIZE
                )
                MarketReading(
                    symbol = symbol,
                    digit = digit,
                    observedPercent = observed,
                    sampleSize = state.sampleSize,
                    qualifies = meetsCondition && parked == null,
                    marginPercent = marginAgainst(observed, threshold, operator),
                    coolOffRemaining = coolRemaining,
                    tradesInWindow = used,
                    overFrequency = overUsed,
                    reason = skipReason
                )
            }
        }
    }

    val pick = pickCandidate(readings, config, excludeSymbol)
    return MarketSweep(
        readings = readings,
        candidate = pick.chosen,
        heldBack = pick.heldBack
    )
}

/** The digit sentinel that stands in for an **even** class in a parity sweep reading. */
const val PARITY_EVEN_SENTINEL = 0

/** The digit sentinel that stands in for an **odd** class in a parity sweep reading. */
const val PARITY_ODD_SENTINEL = 9

/**
 * Evaluates every subscribed market on the **parity of its last digit** — Even {0,2,4,6,8} vs
 * Odd {1,3,5,7,9} — and returns the full field plus the market to trade.
 *
 * This is the Even/Odd strategy's entry model, kept beside [sweepMarkets] so the two share the
 * no-repeat, rotation and margin plumbing without the Differs path knowing about parity. Each
 * market's [MarketReading.digit] is the class the trade will be, encoded as a sentinel:
 * [PARITY_EVEN_SENTINEL] for an Even trade, [PARITY_ODD_SENTINEL] for an Odd one — and
 * [MarketReading.observedPercent] is the observed percentage of *that* class over the window,
 * which is the probability the DIGITEVEN/DIGITODD contract actually pays out on.
 *
 * @param mode which class to trade. [EvenOddMode.AUTO] picks the side that fits the operator:
 *   "above" tests the **hot** side (the class that has been appearing more) against the
 *   threshold, "below" tests the **cold** side — the mean-reversion read. [EvenOddMode.EVEN]
 *   and [EvenOddMode.ODD] pin the class.
 * @param excludeSymbol the market that just carried a trade. The no-repeat rule applies before
 *   the pick, exactly as in [sweepMarkets].
 * @param rotation the rotation window, applied before the pick so the field actually rotates.
 */
fun sweepEvenOddMarkets(
    settings: TradeSettings,
    config: SystemConfig,
    mode: EvenOddMode,
    excludeSymbol: String?,
    getMarketState: (String) -> MarketState?,
    coolOff: (TradeEntry) -> CooldownState? = { null },
    rotation: MarketRotation? = null
): MarketSweep {
    val operator = settings.evenOddOperator
    val threshold = settings.evenOddThreshold
    val tradeType = TradeType.EVEN_ODD

    val readings = SupportedMarkets.ALL.map { market ->
        val symbol = market.symbol
        val state = getMarketState(symbol)

        // The class this market would trade. Fixed modes always know it; AUTO knows it whenever
        // there are frequencies to look at. Without data and in AUTO the class is genuinely
        // undetermined, so there is no entry a cool-off could be parked on either.
        val resolvedClass = if (state == null && mode == EvenOddMode.AUTO) {
            null
        } else {
            resolveParityClass(
                frequencies = state?.digitFrequencies ?: emptyList(),
                mode = mode,
                operator = operator
            )
        }

        // The entry the cool-off is keyed on. Resolved before the data check, for the same
        // ordering reason as [sweepMarkets]: a market on a break must report it even when it is
        // still accumulating ticks, or the deadlock guard undercounts the parked field.
        val entry = resolvedClass?.let { TradeEntry(symbol, it, tradeType) }
        val cool = entry?.let { coolOff(it) }
        val parked = cool?.takeIf { it.isCoolingOff }
        val coolRemaining = parked?.tradesRemaining ?: 0
        val coolReason = if (parked == null) {
            ""
        } else {
            "cooling off — ${parked.tradesRemaining} trade" +
                (if (parked.tradesRemaining == 1) "" else "s") +
                " left after repeated losses here"
        }

        val used = rotation?.tradesInWindow(symbol) ?: 0
        val overUsed = rotation?.isOverFrequency(symbol) == true
        val frequencyNote = if (used <= 0) {
            ""
        } else {
            "$used of the last ${DEFAULT_ROTATION_WINDOW_TRADES} trades" +
                (if (overUsed) " — held back so the others catch up" else "")
        }
        val skipReason = listOf(coolReason, frequencyNote).filter { it.isNotBlank() }
            .joinToString(" · ")

        when {
            state == null ->
                MarketReading(
                    symbol = symbol,
                    digit = resolvedClass ?: PARITY_EVEN_SENTINEL,
                    observedPercent = null,
                    sampleSize = 0,
                    qualifies = false,
                    marginPercent = null,
                    coolOffRemaining = coolRemaining,
                    tradesInWindow = used,
                    overFrequency = overUsed,
                    parityLabel = resolvedClass?.let { parityLabel(it) },
                    reason = skipReason.ifBlank { "no tick data yet" }
                )

            state.sampleSize < MIN_SAMPLE_SIZE ->
                MarketReading(
                    symbol = symbol,
                    digit = resolvedClass ?: PARITY_EVEN_SENTINEL,
                    observedPercent = null,
                    sampleSize = state.sampleSize,
                    qualifies = false,
                    marginPercent = null,
                    coolOffRemaining = coolRemaining,
                    tradesInWindow = used,
                    overFrequency = overUsed,
                    parityLabel = resolvedClass?.let { parityLabel(it) },
                    reason = skipReason.ifBlank {
                        "needs $MIN_SAMPLE_SIZE ticks, has ${state.sampleSize}"
                    }
                )

            else -> {
                val cls = resolvedClass ?: PARITY_EVEN_SENTINEL
                val isEven = cls == PARITY_EVEN_SENTINEL
                val observed = DigitAnalysisEngine.parityPercentFor(state.digitFrequencies, isEven)
                val meetsCondition = DigitAnalysisEngine.evaluateCondition(
                    observedPercent = observed,
                    thresholdPercent = threshold,
                    operator = operator,
                    sampleSize = state.sampleSize,
                    minimumSampleSize = MIN_SAMPLE_SIZE
                ) && parked == null
                MarketReading(
                    symbol = symbol,
                    digit = cls,
                    observedPercent = observed,
                    sampleSize = state.sampleSize,
                    qualifies = meetsCondition,
                    marginPercent = marginAgainst(observed, threshold, operator),
                    coolOffRemaining = coolRemaining,
                    tradesInWindow = used,
                    overFrequency = overUsed,
                    parityLabel = parityLabel(cls),
                    reason = skipReason
                )
            }
        }
    }

    val pick = pickCandidate(readings, config, excludeSymbol)
    return MarketSweep(readings = readings, candidate = pick.chosen, heldBack = pick.heldBack)
}

/**
 * Which parity class a market trades, given its [mode] and the entry [operator].
 *
 * Fixed modes are trivial. AUTO picks the side the operator is "for": an "above" test is a
 * continuation read, so it trades the **hot** class — the one that has been appearing more, and
 * the one that would pay out if the run continues; a "below" test is a mean-reversion read, so it
 * trades the **cold** class. Because even % + odd % is 100, the hot side is the one whose
 * percentage is at least 50 and the cold side the other, so the pick is a single comparison
 * rather than a pair of threshold tests.
 *
 * Pure over the frequencies so it can be tested, and so the sweep, the event log and the UI all
 * name the same class for the same window.
 */
internal fun resolveParityClass(
    frequencies: List<DigitFrequency>,
    mode: EvenOddMode,
    operator: ComparisonOperator
): Int = when (mode) {
    EvenOddMode.EVEN -> PARITY_EVEN_SENTINEL
    EvenOddMode.ODD -> PARITY_ODD_SENTINEL
    EvenOddMode.AUTO -> {
        val hotIsEven = DigitAnalysisEngine.evenPercentFor(frequencies) >=
            DigitAnalysisEngine.oddPercentFor(frequencies)
        when {
            operator.isAtLeast && hotIsEven -> PARITY_EVEN_SENTINEL
            operator.isAtLeast -> PARITY_ODD_SENTINEL
            !operator.isAtLeast && !hotIsEven -> PARITY_EVEN_SENTINEL
            else -> PARITY_ODD_SENTINEL
        }
    }
}

/** The human-readable name of a class encoded as a [PARITY_EVEN_SENTINEL] sentinel. */
private fun parityLabel(sentinel: Int): String =
    if (sentinel == PARITY_EVEN_SENTINEL) "Even" else "Odd"

/** A pick, plus the qualifier that was passed over to honour the no-repeat rule. */
private data class Pick(val chosen: MarketReading?, val heldBack: MarketReading?)

/**
 * How far past its threshold a reading sits, always expressed so that **positive means met**.
 *
 * `observed - threshold` is only correct for an "above" test. Under a "below" test it inverts: a
 * reading that clears "below 55%" at 40% would score -15 and look like a failure, while a reading
 * of 80% would score +25 and look like a strong pass despite being the worst available. Since the
 * margin drives both the progress-bar ordering and the "best opportunity" pick when random markets
 * are off, that inversion would have made the engine choose the market least likely to pay out.
 */
private fun marginAgainst(observed: Double, threshold: Double, operator: ComparisonOperator): Double =
    if (operator.isAtLeast) observed - threshold else threshold - observed

/**
 * Chooses which qualifying market the order goes into, never taking the same one twice in a row.
 *
 * The no-repeat rule is applied **before** the random draw, not after. Drawing first and then
 * discarding the repeat only wastes the draw; it also means the market that has just been traded
 * stays in the pool, so when it happens to be the sole qualifier the sweep re-enters it every
 * cycle.
 *
 * When the just-traded market is the *only* one that cleared the threshold, this **holds** rather
 * than trading it again. That case previously yielded — it took the repeat and set [Pick.heldBack]
 * — because stalling was judged worse than repeating. Device logs showed that reasoning was wrong:
 * with a threshold set near the Differs baseline, one market commonly qualifies alone, so the
 * "yield" case was not rare at all and produced exactly the consecutive same-market trades the
 * rule exists to prevent. A missing trade is visible and recoverable; a duplicate is neither.
 * The hold is reported through [MarketSweep.heldBack] so the screen can say the wait is deliberate.
 */
private fun pickCandidate(
    readings: List<MarketReading>,
    config: SystemConfig,
    excludeSymbol: String?
): Pick {
    val qualifiers = readings.filter { it.qualifies }
    if (qualifiers.isEmpty()) return Pick(null, null)

    val alternatives = if (excludeSymbol == null) {
        qualifiers
    } else {
        qualifiers.filter { it.symbol != excludeSymbol }
    }

    if (alternatives.isEmpty()) {
        // Sole qualifier, and it is the one just traded. Hold rather than repeat.
        return Pick(null, qualifiers.first())
    }

    // Rotation: set aside the markets that have already carried their share of the recent window.
    //
    // This is applied *after* the no-repeat exclusion and *before* the pick, and it narrows to the
    // least-used group rather than merely dropping the busiest — dropping alone would still choose
    // at random among whatever was left, which clusters just as unevenly one step removed. Taking
    // only the markets tied on the fewest recent trades is what makes the field actually rotate.
    //
    // The fallback matters as much as the rule: when every qualifier is over its share, there is
    // nothing left to rotate into, and holding would forfeit the trade over a bookkeeping nicety.
    // The least-used qualifier wins instead, which is still the fairest available choice.
    val leastUsed = alternatives.filter { !it.overFrequency }.minOfOrNull { it.tradesInWindow }
    val rotated = when {
        leastUsed != null -> alternatives.filter { !it.overFrequency && it.tradesInWindow == leastUsed }
        else -> alternatives.minBy { it.tradesInWindow }.let { listOf(it) }
    }

    // Random markets on (the default) keeps the field rotating among equals. Off, the pick is the
    // qualifier furthest past the threshold, which is the setting's original "trade the best
    // opportunity" meaning — still subject to the no-repeat and rotation rules, since both are
    // applied first. The symbol is a tie-break so the same market wins ties rather than the pick
    // flickering between equals on every poll.
    val chosen = if (config.randomMarkets) {
        rotated.random()
    } else {
        rotated.maxWithOrNull(
            // `maxWithOrNull`, not `maxByOrNull`: the selector would have to return a single
            // Comparable, and Pair is not one. Ordering by margin first and symbol second gives the
            // tie-break without giving up the deterministic pick.
            compareBy<MarketReading> { it.marginPercent ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.symbol }
        ) ?: rotated.first()
    }
    return Pick(chosen, null)
}

/** Minimum ticks required before the digit-frequency entry condition is trusted. */
internal const val MIN_SAMPLE_SIZE = 10