package com.tickedge.domain

import kotlin.math.abs

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

    /** Win probability of a Digit Over/Under contract with the given barrier. */
    fun overUnderWinProbability(barrier: Int, isOver: Boolean): Double {
        val winningDigits = if (isOver) (barrier + 1).coerceAtMost(9) else barrier.coerceAtLeast(0)
        return winningDigits.coerceIn(0, 9) / 10.0
    }

    /** Win probability of a Digit Differs contract — 9 of the 10 possible last digits. */
    fun differsWinProbability(): Double = 9.0 / 10.0

    /** Profit returned per unit staked on a win, at fair pricing for probability [p]. */
    fun payoutRatioFor(winProbability: Double): Double {
        require(winProbability > 0.0 && winProbability < 1.0) {
            "win probability must be strictly between 0 and 1, was $winProbability"
        }
        return (1.0 - winProbability) / winProbability
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
     * @param targetProfit profit to bank on top of breaking even. `null` means the spec's 10% of
     *   [baseStake]; pass an absolute amount to target something else.
     * @param winProbability probability the recovery contract wins; drives the payout ratio.
     * @param maxSteps the user's configured Martingale cap.
     * @param maxTotalRisk the daily-loss ceiling. Exceeding it makes the plan infeasible.
     * @param minStake smallest stake the venue accepts.
     */
    fun plan(
        baseStake: Double,
        lossesToRecover: Double,
        targetProfit: Double? = null,
        winProbability: Double,
        maxSteps: Int,
        maxTotalRisk: Double,
        minStake: Double = DEFAULT_MIN_STAKE
    ): Plan {
        require(baseStake > 0) { "baseStake must be positive, was $baseStake" }
        require(maxSteps >= 1) { "maxSteps must be at least 1, was $maxSteps" }
        require(maxTotalRisk > 0) { "maxTotalRisk must be positive, was $maxTotalRisk" }
        require(lossesToRecover >= 0) { "lossesToRecover cannot be negative, was $lossesToRecover" }

        val payoutRatio = payoutRatioFor(winProbability)
        val target = targetProfit ?: baseStake * RECOVERY_TARGET_RATIO

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
     * Recovery target: bank an extra 10% of the base stake on top of breaking even.
     *
     * Expressed as a ratio of the base stake rather than a fixed amount, so "10%" keeps meaning
     * the same thing at a $1 base and a $100 base.
     */
    const val RECOVERY_TARGET_RATIO = 0.10

    /** Deriv's smallest accepted stake for these contracts. */
    const val DEFAULT_MIN_STAKE = 0.35
}
