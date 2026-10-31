package com.tickedge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the recovery planner.
 *
 * The point of these tests is not only that the arithmetic is right — it is that the planner is
 * honest. Two behaviours are asserted specifically because they are the ones that could quietly
 * mislead someone about their money:
 *
 *  1. a winning step banks exactly the configured target and no more, and
 *  2. recovery is reported as INFEASIBLE when the stake ladder runs past the risk cap, rather
 *     than being presented as achievable.
 */
class RecoveryCalculatorTest {

    private val tolerance = 0.0001

    // ------------------------------------------------------------- probability helpers

    @Test
    fun `over wins on digits strictly above the barrier`() {
        // Barrier 4 -> wins on 5,6,7,8,9 -> 5 of 10.
        assertEquals(0.5, RecoveryCalculator.overUnderWinProbability(4, isOver = true), tolerance)
    }

    @Test
    fun `under wins on digits strictly below the barrier`() {
        // Barrier 4 -> wins on 0,1,2,3 -> 4 of 10.
        assertEquals(0.4, RecoveryCalculator.overUnderWinProbability(4, isOver = false), tolerance)
    }

    @Test
    fun `edge barriers do not produce a zero or one probability`() {
        // Over 0 wins on 1..9 (9 digits); Under 9 wins on 0..8 (9 digits). Neither may be 1.0,
        // which would make the payout ratio zero and the whole plan meaningless.
        assertEquals(0.9, RecoveryCalculator.overUnderWinProbability(0, isOver = true), tolerance)
        assertEquals(0.9, RecoveryCalculator.overUnderWinProbability(9, isOver = false), tolerance)
        assertEquals(0.0, RecoveryCalculator.overUnderWinProbability(9, isOver = true), tolerance)
        assertEquals(0.0, RecoveryCalculator.overUnderWinProbability(0, isOver = false), tolerance)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a certain-win probability is rejected rather than silently dividing by zero`() {
        RecoveryCalculator.payoutRatioFor(1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero-win probability is rejected`() {
        RecoveryCalculator.payoutRatioFor(0.0)
    }

    // ------------------------------------------------------------------ the target maths

    @Test
    fun `every winning step banks exactly the target and breaks even`() {
        listOf(0.2, 0.5, 0.8).forEach { p ->
            val plan = RecoveryCalculator.plan(
                baseStake = 1.0,
                lossesToRecover = 5.0,
                winProbability = p,
                maxSteps = 3,
                maxTotalRisk = 10_000.0
            )
            plan.steps.forEach { step ->
                assertEquals(
                    "step ${step.step} at p=$p should bank exactly the target",
                    plan.targetProfit,
                    step.runningPnlIfWin,
                    tolerance
                )
            }
        }
    }

    @Test
    fun `the default target is ten percent of what was lost`() {
        val plan = RecoveryCalculator.plan(
            baseStake = 20.0,
            lossesToRecover = 3.0,
            winProbability = 0.5,
            maxSteps = 2,
            maxTotalRisk = 10_000.0
        )
        // 10% of the LOSS, not of the base stake. The old default used the base stake, which made
        // the reward the same 2.00 whether the hole was 3.00 or 300.00 — so a deep ladder "recovered"
        // to exactly break even and never banked the premium the ladder existed to earn.
        assertEquals(0.3, plan.targetProfit, tolerance)
    }

    @Test
    fun `a deeper hole asks for a bigger premium, not the same one`() {
        val shallow = RecoveryCalculator.plan(
            baseStake = 0.35, lossesToRecover = 1.0, winProbability = 0.5,
            maxSteps = 1, maxTotalRisk = 10_000.0
        )
        val deep = RecoveryCalculator.plan(
            baseStake = 0.35, lossesToRecover = 20.0, winProbability = 0.5,
            maxSteps = 1, maxTotalRisk = 10_000.0
        )
        assertTrue(deep.targetProfit > shallow.targetProfit * 10)
    }

    @Test
    fun `an explicit target overrides the ten percent default`() {
        val plan = RecoveryCalculator.plan(
            baseStake = 1.0,
            lossesToRecover = 3.0,
            targetProfit = 5.0,
            winProbability = 0.5,
            maxSteps = 1,
            maxTotalRisk = 10_000.0
        )
        assertEquals(5.0, plan.targetProfit, tolerance)
        // stake * r must cover losses + target
        assertEquals(8.0, plan.steps.first().stake, tolerance)
    }

    @Test
    fun `losing every step compounds the outstanding loss`() {
        val plan = RecoveryCalculator.plan(
            baseStake = 1.0,
            lossesToRecover = 5.0,
            winProbability = 0.5,
            maxSteps = 3,
            maxTotalRisk = 10_000.0
        )
        // target = 10% of 5.00 = 0.50, and p=0.5 gives r=1.0, so each rung is (outstanding + 0.50).
        // Rung 1 stakes 5.50 against an outstanding 5.00 -> -10.50; that leaves 10.50 outstanding,
        // so rung 2 stakes 11.00 -> -21.50.
        assertEquals(0.50, plan.targetProfit, tolerance)
        assertEquals(-10.50, plan.steps[0].runningPnlIfLoss, tolerance)
        assertEquals(-21.50, plan.steps[1].runningPnlIfLoss, tolerance)
    }

    @Test
    fun `expected value is negative at every rung and reported honestly`() {
        val plan = RecoveryCalculator.plan(
            baseStake = 1.0,
            lossesToRecover = 5.0,
            winProbability = 0.5,
            maxSteps = 3,
            maxTotalRisk = 10_000.0
        )
        // At fair pricing every step is worth exactly -stake in expectation, so the ladder can
        // never be a way to make money. The plan must surface that rather than imply profit.
        plan.steps.forEach { assertTrue("step ${it.step} EV should be negative", it.expectedNet < 0) }
        assertTrue(plan.expectedNetAfterFullRecovery < 0)
    }

    // -------------------------------------------------------------------- infeasibility

    @Test
    fun `recovery is reported infeasible when the ladder breaches the risk cap`() {
        val plan = RecoveryCalculator.plan(
            baseStake = 1.0,
            lossesToRecover = 5.0,
            winProbability = 0.5,
            maxSteps = 3,
            maxTotalRisk = 20.0
        )
        assertFalse(plan.isFeasible)
        assertTrue(
            "the reason should name the limit that was breached",
            plan.infeasibleReason!!.contains("20.00")
        )
    }

    @Test
    fun `recovery is feasible when the whole ladder fits under the cap`() {
        val plan = RecoveryCalculator.plan(
            baseStake = 1.0,
            lossesToRecover = 5.0,
            winProbability = 0.5,
            maxSteps = 3,
            maxTotalRisk = 500.0
        )
        assertTrue(plan.isFeasible)
        assertNull(plan.infeasibleReason)
    }

    @Test
    fun `differs recovery explodes and is refused rather than presented as workable`() {
        // Differs wins 9 times in 10, so its fair payout ratio is only 0.111. Clearing 5.00 of
        // losses plus a 0.50 premium then needs a 49.50 stake, and the next rung is ~495. This is
        // the case that most needs to be flagged: it looks like a small number until you compound it.
        val plan = RecoveryCalculator.plan(
            baseStake = 1.0,
            lossesToRecover = 5.0,
            winProbability = RecoveryCalculator.differsWinProbability(),
            maxSteps = 3,
            maxTotalRisk = 20.0
        )
        assertFalse(plan.isFeasible)
        assertEquals(49.50, plan.steps[0].stake, 0.01)
        assertTrue("second rung should be an order of magnitude worse", plan.steps[1].stake > 400)
    }

    @Test
    fun `cumulative risk is the sum of every rung`() {
        val plan = RecoveryCalculator.plan(
            baseStake = 1.0,
            lossesToRecover = 5.0,
            winProbability = 0.5,
            maxSteps = 3,
            maxTotalRisk = 10_000.0
        )
        val sum = plan.steps.sumOf { it.stake }
        assertEquals(sum, plan.totalRiskIfAllLose, tolerance)
        // Rungs of 5.50, 11.00 and 22.00 at r=1.0 against a 5.00 hole.
        assertEquals(38.50, plan.totalRiskIfAllLose, 0.01)
    }

    // --------------------------------------------------------------------- no-loss case

    @Test
    fun `with nothing to recover the plan is a single base stake`() {
        val plan = RecoveryCalculator.plan(
            baseStake = 2.0,
            lossesToRecover = 0.0,
            winProbability = 0.5,
            maxSteps = 5,
            maxTotalRisk = 100.0
        )
        assertEquals(1, plan.steps.size)
        assertEquals(2.0, plan.steps.first().stake, tolerance)
        assertTrue(plan.isFeasible)
    }

    @Test
    fun `a base stake above the risk cap is itself infeasible`() {
        val plan = RecoveryCalculator.plan(
            baseStake = 50.0,
            lossesToRecover = 0.0,
            winProbability = 0.5,
            maxSteps = 1,
            maxTotalRisk = 10.0
        )
        assertFalse(plan.isFeasible)
        assertTrue(plan.infeasibleReason!!.contains("50.00"))
    }

    // -------------------------------------------------------------- minimum-stake floor

    @Test
    fun `a tiny recovery is floored at the venue minimum stake`() {
        // 0.01 of losses at r=0.111 would ask for 0.09, below the 0.35 minimum, so the plan must
        // not emit a stake the venue would reject.
        val plan = RecoveryCalculator.plan(
            baseStake = 1.0,
            lossesToRecover = 0.01,
            winProbability = RecoveryCalculator.differsWinProbability(),
            maxSteps = 1,
            maxTotalRisk = 100.0
        )
        assertTrue(
            "stake ${plan.steps.first().stake} is below the venue minimum",
            plan.steps.first().stake >= RecoveryCalculator.DEFAULT_MIN_STAKE
        )
    }

    // --------------------------------------------------------------- argument guarding

    @Test(expected = IllegalArgumentException::class)
    fun `a zero base stake is rejected`() {
        RecoveryCalculator.plan(0.0, 1.0, winProbability = 0.5, maxSteps = 1, maxTotalRisk = 10.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero step cap is rejected`() {
        RecoveryCalculator.plan(1.0, 1.0, winProbability = 0.5, maxSteps = 0, maxTotalRisk = 10.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative losses are rejected`() {
        RecoveryCalculator.plan(1.0, -5.0, winProbability = 0.5, maxSteps = 1, maxTotalRisk = 10.0)
    }

    // --------------------------------------------------------------- live-quote pricing

    @Test
    fun `the payout ratio is read off the live proposal`() {
        // A $1.00 contract paying $1.85 returns $0.85 on a win, i.e. 0.85 profit per unit staked.
        // The sentinel stands in for null so a null cannot pass this assertion.
        assertEquals(0.85, RecoveryCalculator.payoutRatioFromQuote(1.85, 1.00) ?: -1.0, tolerance)
    }

    @Test
    fun `an unusable quote is refused rather than divided by`() {
        assertNull(RecoveryCalculator.payoutRatioFromQuote(1.85, 0.0))
        assertNull(RecoveryCalculator.payoutRatioFromQuote(1.85, -1.0))
        // A payout at or below the price pays nothing, so the ratio is not a small number — it is
        // undefined, and treating it as 0 would size the next rung at infinity.
        assertNull(RecoveryCalculator.payoutRatioFromQuote(1.00, 1.00))
        assertNull(RecoveryCalculator.payoutRatioFromQuote(0.50, 1.00))
        assertNull(RecoveryCalculator.payoutRatioFromQuote(Double.NaN, 1.00))
    }

    @Test
    fun `the live quote overrides the theoretical ratio`() {
        // Barrier 4 Over is p=0.5, a fair ratio of 1.0, so the theoretical sizing stakes the hole plus its
        // 0.40 premium = $4.40. If the venue actually pays only 0.3 per unit staked, $4.40 returns
        // $1.32 and the hole is still there. The live quote is the number that must win.
        val theoretical = RecoveryCalculator.plan(
            baseStake = 1.0, lossesToRecover = 4.0, winProbability = 0.5,
            maxSteps = 1, maxTotalRisk = 10_000.0
        )
        val live = RecoveryCalculator.plan(
            baseStake = 1.0, lossesToRecover = 4.0, winProbability = 0.5,
            maxSteps = 1, maxTotalRisk = 10_000.0, livePayoutRatio = 0.3
        )
        assertEquals(4.40, theoretical.steps.first().stake, tolerance)
        assertEquals((4.0 + 0.4) / 0.3, live.steps.first().stake, 0.01)
    }

    @Test
    fun `a nonsense live ratio is ignored in favour of the theoretical one`() {
        val plan = RecoveryCalculator.plan(
            baseStake = 1.0, lossesToRecover = 4.0, winProbability = 0.5,
            maxSteps = 1, maxTotalRisk = 10_000.0, livePayoutRatio = -2.0
        )
        assertEquals(4.40, plan.steps.first().stake, tolerance)
    }

    // -------------------------------------------------------- the rung actually used

    /**
     * Walks a real losing streak and checks the account level when the ladder finally wins.
     *
     * This is the assertion the whole recovery system exists to make, and it could not be written
     * before because the sizing was a private method reading private engine fields.
     */
    @Test
    fun `a win on the ladder levels the account and banks ten percent of the losses`() {
        val baseStake = 0.35
        val payoutRatio = 0.43
        var outstanding = 0.0
        var streak = 0

        // The first trade is an ordinary Differs entry that loses.
        outstanding += baseStake
        streak = 1

        var won = false
        repeat(8) {
            if (won) return@repeat
            val rung = recoveryRungStake(
                baseStake = baseStake, outstanding = outstanding, streak = streak,
                recoveryDigit = 2, isOver = true, livePayoutRatio = payoutRatio
            )
            // This rung loses.
            outstanding += rung
            streak++
            // The next one wins.
            val winning = recoveryRungStake(
                baseStake = baseStake, outstanding = outstanding, streak = streak,
                recoveryDigit = 2, isOver = true, livePayoutRatio = payoutRatio
            )
            if (winning * payoutRatio >= outstanding * (1.0 + RecoveryCalculator.RECOVERY_TARGET_RATIO)) {
                won = true
            }
        }

        assertTrue("the ladder never produced a rung that would have won", won)
    }

    /**
     * The regression this extraction exists to pin.
     *
     * There used to be a `coerceIn(baseStake, baseStake * 2^(streak+1))` around the sized rung. On
     * a payout ratio below 1 the correct rung climbs faster than doubling, so the clamp bound from
     * step 3 and every "recovered" win left the account short. This walks both ladders side by side
     * and asserts the old one loses money where the new one makes it.
     */
    @Test
    fun `the removed doubling ceiling is the difference between levelling and staying down`() {
        val baseStake = 0.35
        val payoutRatio = 0.43

        fun walk(applyDoublingCeiling: Boolean): Double {
            var outstanding = baseStake // the opening Differs trade lost
            var streak = 1
            repeat(6) {
                val sized = recoveryRungStake(
                    baseStake = baseStake, outstanding = outstanding, streak = streak,
                    recoveryDigit = 2, isOver = true, livePayoutRatio = payoutRatio
                )
                val stake = if (applyDoublingCeiling) {
                    sized.coerceIn(baseStake, baseStake * Math.pow(2.0, (streak + 1).toDouble()))
                } else {
                    sized
                }
                outstanding += stake
                streak++
                val next = recoveryRungStake(
                    baseStake = baseStake, outstanding = outstanding, streak = streak,
                    recoveryDigit = 2, isOver = true, livePayoutRatio = payoutRatio
                )
                val cappedNext = if (applyDoublingCeiling) {
                    next.coerceIn(baseStake, baseStake * Math.pow(2.0, (streak + 1).toDouble()))
                } else {
                    next
                }
                // The next rung wins, so take it.
                return cappedNext * payoutRatio - outstanding
            }
            return Double.NaN
        }

        val withoutCeiling = walk(applyDoublingCeiling = false)
        val withCeiling = walk(applyDoublingCeiling = true)

        assertTrue(
            "the capped ladder should have under-recovered, got $withCeiling",
            withCeiling < 0.0
        )
        assertTrue(
            "the current ladder should level the account, got $withoutCeiling",
            withoutCeiling >= 0.0
        )
    }

    @Test
    fun `a rung is never smaller than the base stake`() {
        // A shallow hole at a generous payout can size below the base stake; taking a smaller bet
        // into a bigger hole is never the intent.
        listOf(0.0, 0.01, 0.1, 0.35).forEach { outstanding ->
            val rung = recoveryRungStake(
                baseStake = 0.35, outstanding = outstanding, streak = 1,
                recoveryDigit = 4, isOver = true, livePayoutRatio = 2.0
            )
            assertTrue("rung $rung below base for outstanding $outstanding", rung >= 0.35)
        }
    }

    @Test
    fun `a rung is always rounded to what the venue accepts`() {
        listOf(0, 1, 2, 3, 5).forEach { digit ->
            val rung = recoveryRungStake(
                baseStake = 0.35, outstanding = 3.33, streak = 2,
                recoveryDigit = digit, isOver = true
            )
            assertEquals(
                "rung $rung carries more than two decimal places",
                rung,
                roundToVenuePrecision(rung),
                0.0
            )
        }
    }

    // -------------------------------------------------------- the parity rung

    @Test
    fun `a parity rung is sized on the even odd prior when no quote is available`() {
        // No live quote: the plan uses the theoretical 0.5-prior, payout ratio 1.0, so a rung for
        // outstanding 0.35 + 10% target is 0.385 — which the double-precision half-up rounding
        // stores as 0.3849999..., landing on 0.38 rather than 0.39.
        val rung = parityRecoveryRungStake(baseStake = 0.35, outstanding = 0.35, streak = 1)
        assertEquals(0.38, rung, 0.001)
    }

    @Test
    fun `a parity rung is sized on the live quote when one is available`() {
        // A DIGITEVEN quote paying 0.43 per unit: rung = (0.35 + 0.035) / 0.43 = 0.895, rounded 0.90.
        val rung = parityRecoveryRungStake(
            baseStake = 0.35, outstanding = 0.35, streak = 1, livePayoutRatio = 0.43
        )
        assertEquals(0.90, rung, 0.001)
    }

    @Test
    fun `a parity win levels the account and banks ten percent of the losses`() {
        val baseStake = 0.35
        val payoutRatio = 0.43
        var outstanding = 0.0
        var streak = 0

        outstanding += baseStake // the opening parity entry lost
        streak = 1

        var won = false
        repeat(8) {
            if (won) return@repeat
            val rung = parityRecoveryRungStake(
                baseStake = baseStake, outstanding = outstanding, streak = streak,
                livePayoutRatio = payoutRatio
            )
            // This rung loses.
            outstanding += rung
            streak++
            // The next one wins.
            val winning = parityRecoveryRungStake(
                baseStake = baseStake, outstanding = outstanding, streak = streak,
                livePayoutRatio = payoutRatio
            )
            if (winning * payoutRatio >= outstanding * (1.0 + RecoveryCalculator.RECOVERY_TARGET_RATIO)) {
                won = true
            }
        }

        assertTrue("the parity ladder never produced a rung that would have won", won)
    }

    @Test
    fun `a parity rung is never smaller than the base stake`() {
        listOf(0.0, 0.01, 0.1, 0.35).forEach { outstanding ->
            val rung = parityRecoveryRungStake(
                baseStake = 0.35, outstanding = outstanding, streak = 1, livePayoutRatio = 2.0
            )
            assertTrue("rung $rung below base for outstanding $outstanding", rung >= 0.35)
        }
    }
}
