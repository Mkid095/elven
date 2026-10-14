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
    fun `the default target is ten percent of the base stake`() {
        val plan = RecoveryCalculator.plan(
            baseStake = 20.0,
            lossesToRecover = 3.0,
            winProbability = 0.5,
            maxSteps = 2,
            maxTotalRisk = 10_000.0
        )
        assertEquals(2.0, plan.targetProfit, tolerance)
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
        // runningPnlIfLoss must equal -(outstanding + stake) at each rung.
        assertEquals(-10.10, plan.steps[0].runningPnlIfLoss, tolerance)
        assertEquals(-20.30, plan.steps[1].runningPnlIfLoss, tolerance)
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
        // losses then needs a 45.90 stake, and the next rung is ~459. This is the case that most
        // needs to be flagged: it looks like a small number until you compound it.
        val plan = RecoveryCalculator.plan(
            baseStake = 1.0,
            lossesToRecover = 5.0,
            winProbability = RecoveryCalculator.differsWinProbability(),
            maxSteps = 3,
            maxTotalRisk = 20.0
        )
        assertFalse(plan.isFeasible)
        assertEquals(45.90, plan.steps[0].stake, 0.01)
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
        assertEquals(35.70, plan.totalRiskIfAllLose, 0.01)
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
}
