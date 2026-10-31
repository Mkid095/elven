package com.tickedge.domain

import kotlin.math.abs
import kotlin.math.pow

/**
 * Paper-trading recovery planner.
 *
 * **This computes what it *would* take to recover, and deliberately never does it.** It is a pure
 * function over the user's settings and is not wired to any order path — real stakes are never
 * escalated from here. Recovery is not guaranteed: every step is a bet with a negative expected
 * value, and the calculator's job is to show the size of that hole rather than hide it.
 *
 * ## The maths
 *
 * A binary option paying profit-ratio `r` on a win returns `s * r` for stake `s`. Recovering an
 * accumulated loss `L` and still banking a target `T` therefore needs:
 *
 * ```
 * s * r = L + T   =>   s = (L + T) / r
 * ```
 *
 * `r` comes from the contract's own win probability `p` under fair pricing, `r = (1 - p) / p`.
 * That is deliberately the least flattering assumption: it uses the theoretical payout rather than
 * the broker's quoted one, so the table cannot be flattered by a favourable price.
 *
 * Consequence worth stating plainly: because `r = (1-p)/p`, the expected value of every step is
 * `p * (s*r) - (1-p) * s = -s`. The higher the win probability, the smaller the loss per trade but
 * the smaller the payout, and recovery always ends up costing more than it returns. The plan reports
 * this number instead of hiding it.
 */
object RecoveryCalculator {

    /**
 * Win probability of a Digit Over/Under contract with the given barrier.
 *
 * The contract settles on the **last** digit of the final tick, and the two directions cover
 * opposite ends of the 0-9 range:
 *
 * - **Over N** wins on digits above N — 9-N of them, so Over 0 is 0.9 and Over 9 is 0.1.
 * - **Under N** wins on digits below N — N of them, so Under 9 is 0.9 and Under 0 is 0.1.
 *
 * This was inverted: it counted `N+1` digits for Over and `N` for Under, which made Over 9 come
 * out at 0.9 instead of 0.1 and Over 0 at 0.1 instead of 0.9. That is not a cosmetic slip — the
 * win probability feeds `payoutRatioFor` as `r = (1-p)/p`, so every recovery rung was sized
 * against a barrier at the wrong end of the range. At Over 9 the engine believed it had a 0.9
 * chance and staked to recover against that, when it actually has 0.1.
 */
    fun overUnderWinProbability(barrier: Int, isOver: Boolean): Double {
        val clamped = barrier.coerceIn(0, 9)
        val winningDigits = if (isOver) 9 - clamped else clamped
        return winningDigits.coerceIn(0, 9) / 10.0
    }

    /** Win probability of a Digit Differs contract — 9 of the 10 possible last digits. */
    fun differsWinProbability(): Double = 9.0 / 10.0

    /**
     * Win probability of a Digit Even/Odd contract.
     *
     * Five even last digits ({0,2,4,6,8}) and five odd ({1,3,5,7,9}), so the fair-prior probability
     * of each class is 0.5, which at fair pricing implies a 1.0 payout ratio — a win returns roughly
     * the stake. The venue's own quote is what recovery is actually sized against.
     */
    fun evenOddWinProbability(): Double = 0.5

    /** Profit returned per unit staked on a win, at fair pricing for probability [p]. */
    fun payoutRatioFor(winProbability: Double): Double {
        require(winProbability > 0.0 && winProbability < 1.0) {
            "win probability must be strictly between 0 and 1, was $winProbability"
        }
        return (1.0 - winProbability) / winProbability
    }

    /**
     * Payout ratio implied by a **live** proposal: profit per unit staked, from the numbers the
     * venue actually quoted.
     *
     * [payoutRatioFor] is only fair pricing derived from the digit, and Differs/Over/Under carry no
     * fixed return — the quote moves with the index, the barrier and the duration. Sizing a recovery
     * rung against the theoretical ratio stakes an amount that provably does not clear the hole: the
     * win comes back smaller than the loss it was meant to cover, and the streak resets as though it
     * had been recovered. The proposal is the only number the stake can honestly be judged against.
     *
     * Returns null when the quote is unusable (no price, or a payout that does not exceed it), so
     * the caller falls back to the theoretical ratio rather than dividing by something meaningless.
     */
    fun payoutRatioFromQuote(payout: Double, askPrice: Double): Double? {
        if (!payout.isFinite() || !askPrice.isFinite()) return null
        if (askPrice <= 0.0 || payout <= askPrice) return null
        return (payout - askPrice) / askPrice
    }

    /** One rung of the ladder. */
    data class Step(
        val step: Int,
        val stake: Double,
        /** Stake required to both clear everything still outstanding and bank [targetProfit]. */
        val winProbability: Double,
        val payoutRatio: Double,
        val netIfWin: Double,
        val netIfLoss: Double,
        /** Session P&L if this step wins / if it loses. */
        val runningPnlIfWin: Double,
        val runningPnlIfLoss: Double,
        /** Expected change in session P&L from taking this step. Always negative. */
        val expectedNet: Double,
        /** Running total staked if every step so far were lost. */
        val cumulativeRisk: Double
    )

    data class Plan(
        val baseStake: Double,
        val targetProfit: Double,
        val lossesToRecover: Double,
        val winProbability: Double,
        val payoutRatio: Double,
        val steps: List<Step>,
        val totalRiskIfAllLose: Double,
        val expectedNetAfterFullRecovery: Double,
        val isFeasible: Boolean,
        val infeasibleReason: String?
    ) {
        /** Cumulative stake needed to clear the losses and still bank the target. */
        val stakeToRecoverNow: Double get() = steps.firstOrNull()?.stake ?: Double.NaN
    }

    /**
     * Builds the recovery ladder.
     *
     * @param baseStake the user's normal stake; recovery steps are sized relative to the loss.
     * @param lossesToRecover losses already incurred in this run.
     * @param targetProfit profit to bank on top of breaking even. `null` means the spec's
     *   [RECOVERY_TARGET_RATIO] **of what was lost**; pass an absolute amount to target something
     *   else.
     * @param winProbability probability the recovery contract wins; drives the payout ratio.
     * @param maxSteps the user's configured Martingale cap.
     * @param maxTotalRisk the daily-loss ceiling. Exceeding it makes the plan infeasible.
     * @param minStake smallest stake the venue accepts.
     * @param livePayoutRatio ratio from a live proposal. Wins over [payoutRatioFor]'s theoretical
     *   value, because that is the price the rung will actually settle at. Ignored when null or
     *   non-positive.
     */
    fun plan(
        baseStake: Double,
        lossesToRecover: Double,
        targetProfit: Double? = null,
        winProbability: Double,
        maxSteps: Int,
        maxTotalRisk: Double,
        minStake: Double = DEFAULT_MIN_STAKE,
        livePayoutRatio: Double? = null
    ): Plan {
        require(baseStake > 0) { "baseStake must be positive, was $baseStake" }
        require(maxSteps >= 1) { "maxSteps must be at least 1, was $maxSteps" }
        require(maxTotalRisk > 0) { "maxTotalRisk must be positive, was $maxTotalRisk" }
        require(lossesToRecover >= 0) { "lossesToRecover cannot be negative, was $lossesToRecover" }

        // The live quote is preferred and the theoretical ratio is the fallback, not the other way
        // round. Sizing against a fair-pricing estimate stakes an amount the venue will not pay.
        val payoutRatio = livePayoutRatio?.takeIf { it.isFinite() && it > 0.0 }
            ?: payoutRatioFor(winProbability)
        val target = targetProfit ?: lossesToRecover * RECOVERY_TARGET_RATIO

        // Nothing to recover: a single base stake already clears the target.
        if (abs(lossesToRecover) < 0.005) {
            val stake = maxOf(baseStake, minStake)
            val step = Step(
                step = 1,
                stake = stake,
                winProbability = winProbability,
                payoutRatio = payoutRatio,
                netIfWin = stake * payoutRatio,
                netIfLoss = -stake,
                runningPnlIfWin = stake * payoutRatio,
                runningPnlIfLoss = -stake,
                expectedNet = -stake,
                cumulativeRisk = stake
            )
            return Plan(
                baseStake = baseStake,
                targetProfit = target,
                lossesToRecover = lossesToRecover,
                winProbability = winProbability,
                payoutRatio = payoutRatio,
                steps = listOf(step),
                totalRiskIfAllLose = stake,
                expectedNetAfterFullRecovery = -stake,
                isFeasible = stake <= maxTotalRisk,
                infeasibleReason = if (stake <= maxTotalRisk) null
                else "Even the base stake of ${fmt(stake)} exceeds the ${fmt(maxTotalRisk)} risk limit."
            )
        }

        val steps = mutableListOf<Step>()
        var outstanding = lossesToRecover
        var cumulativeRisk = 0.0
        var expectedTotal = 0.0
        var infeasibleReason: String? = null

        for (stepIndex in 1..maxSteps) {
            val rawStake = (outstanding + target) / payoutRatio
            val stake = maxOf(rawStake, minStake)
            cumulativeRisk += stake
            expectedTotal += -stake

            val netIfWin = stake * payoutRatio
            steps += Step(
                step = stepIndex,
                stake = stake,
                winProbability = winProbability,
                payoutRatio = payoutRatio,
                netIfWin = netIfWin,
                netIfLoss = -stake,
                runningPnlIfWin = netIfWin - outstanding,
                runningPnlIfLoss = -(outstanding + stake),
                expectedNet = -stake,
                cumulativeRisk = cumulativeRisk
            )

            if (cumulativeRisk > maxTotalRisk && infeasibleReason == null) {
                infeasibleReason =
                    "Step $stepIndex brings total exposure to ${fmt(cumulativeRisk)}, past the " +
                        "${fmt(maxTotalRisk)} limit. Recovery is not possible within your risk cap."
            }

            outstanding += stake
        }

        return Plan(
            baseStake = baseStake,
            targetProfit = target,
            lossesToRecover = lossesToRecover,
            winProbability = winProbability,
            payoutRatio = payoutRatio,
            steps = steps,
            totalRiskIfAllLose = cumulativeRisk,
            expectedNetAfterFullRecovery = expectedTotal,
            isFeasible = infeasibleReason == null,
            infeasibleReason = infeasibleReason
        )
    }

    private fun fmt(value: Double): String = "%.2f".format(value)

    /**
     * Recovery target: bank this fraction **of everything lost so far** on top of breaking even.
     *
     * The base is the accumulated loss, not the base stake. A flat 10% of the base stake is the same
     * target whether the hole is 0.35 or 20.00, so a deep ladder "recovers" to break even and no
     * more — which is the opposite of the intent, since the larger the hole the larger the effort
     * that had to be put in to close it. Tying the target to the hole keeps the reward proportional
     * to what the ladder actually risked.
     */
    const val RECOVERY_TARGET_RATIO = 0.10

    /** Deriv's smallest accepted stake for these contracts. */
    const val DEFAULT_MIN_STAKE = 0.35
}

/**
 * The stake for one rung of the recovery ladder: enough that a win clears every outstanding loss
 * **and** banks [RecoveryCalculator.RECOVERY_TARGET_RATIO] of it on top.
 *
 * Top-level and free of engine state so the whole ladder can be walked in a unit test. The bug this
 * shape makes testable was a `coerceIn(baseStake, baseStake * 2^(streak+1))` in the engine: on a low
 * payout ratio the correctly sized rung grows faster than doubling, so the clamp bit from step 3
 * onward, the win returned less than the hole, and the win handler cleared the streak as if it had
 * been recovered. Nothing in the engine could express "run the ladder to the end and check the
 * account actually levels", because the sizing was a private method reading private fields.
 *
 * There is no doubling ceiling here by design. The ladder's bound is the stop loss, which the
 * trading loop checks before it ever reaches a stake.
 *
 * @param outstanding money already lost in this streak that this rung has to clear.
 * @param streak how many losses deep the ladder is; only used for the doubling fallback.
 * @param livePayoutRatio the venue's quoted profit-per-unit-stake for this contract, when known.
 * @return a stake rounded to the 2 decimal places Deriv accepts, never below [baseStake].
 */
internal fun recoveryRungStake(
    baseStake: Double,
    outstanding: Double,
    streak: Int,
    recoveryDigit: Int,
    isOver: Boolean,
    livePayoutRatio: Double? = null
): Double {
    val theoretical = RecoveryCalculator.overUnderWinProbability(recoveryDigit, isOver)
    val plan = runCatching {
        RecoveryCalculator.plan(
            baseStake = baseStake,
            lossesToRecover = outstanding,
            winProbability = theoretical,
            maxSteps = 1,
            maxTotalRisk = Double.MAX_VALUE,
            livePayoutRatio = livePayoutRatio
        )
    }.getOrNull() ?: return roundToVenuePrecision(baseStake * 2.0.pow(streak.toDouble()))

    // Fall back to plain doubling if the plan produced nothing. `coerceAtLeast` (not an upper
    // clamp) is deliberate: a stake below the base would be a smaller bet on a bigger hole.
    val stake = plan.steps.firstOrNull()?.stake ?: baseStake
    return roundToVenuePrecision(stake.coerceAtLeast(baseStake))
}

/**
 * The stake for one rung of the parity recovery ladder: enough that a win clears every
 * outstanding loss **and** banks [RecoveryCalculator.RECOVERY_TARGET_RATIO] of it on top.
 *
 * The Even/Odd version of [recoveryRungStake]. A Digit Even/Odd contract pays on one of two
 * classes rather than on or against a barrier digit, so the win probability is a flat
 * [RecoveryCalculator.evenOddWinProbability] instead of a barrier-derived one. The
 * [livePayoutRatio] is what the rung is actually sized on: the DIGITEVEN/DIGITODD quote moves with
 * the index and the duration, and a stake sized against the theoretical 1.0 ratio would not clear
 * the hole on a contract the venue priced lower.
 *
 * Mirrors [recoveryRungStake] — top-level, free of engine state, testable — with the doubling
 * fallback for a failed plan and the `coerceAtLeast(baseStake)` floor so a rung is never smaller
 * than the base bet on a bigger hole.
 *
 * @param outstanding money already lost in this streak that this rung has to clear.
 * @param streak how many losses deep the ladder is; only used for the doubling fallback.
 * @param livePayoutRatio the venue's quoted profit-per-unit-stake for this DIGITEVEN/DIGITODD
 *   contract, when known.
 * @return a stake rounded to the 2 decimal places the venue accepts, never below [baseStake].
 */
internal fun parityRecoveryRungStake(
    baseStake: Double,
    outstanding: Double,
    streak: Int,
    livePayoutRatio: Double? = null
): Double {
    val plan = runCatching {
        RecoveryCalculator.plan(
            baseStake = baseStake,
            lossesToRecover = outstanding,
            winProbability = RecoveryCalculator.evenOddWinProbability(),
            maxSteps = 1,
            maxTotalRisk = Double.MAX_VALUE,
            livePayoutRatio = livePayoutRatio
        )
    }.getOrNull() ?: return roundToVenuePrecision(baseStake * 2.0.pow(streak.toDouble()))

    val stake = plan.steps.firstOrNull()?.stake ?: baseStake
    return roundToVenuePrecision(stake.coerceAtLeast(baseStake))
}

/**
 * Rounds a money amount to the 2 decimal places the venue accepts.
 *
 * Half-up on the scaled integer rather than floating-point rounding, so 0.005 lands on 0.01 instead
 * of whichever neighbour the nearest double happens to be. Non-finite and non-positive amounts
 * collapse to 0.0 rather than propagating a NaN into an order.
 */
internal fun roundToVenuePrecision(amount: Double): Double {
    if (!amount.isFinite() || amount <= 0.0) return 0.0
    return Math.round(amount * 100.0) / 100.0
}
