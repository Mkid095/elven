package com.tickedge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two loss measures behind the stop loss and the daily limit.
 *
 * The failure these cover was reported on device: the stop loss kept counting after the recovery
 * ladder had won the money back, so the bot stopped itself on a loss the account no longer had.
 *
 * Every expectation is a fixed sequence of settlements, because the whole point is what the
 * *sequence* does to the counters.
 */
class LossAccountingTest {

    @Test
    fun `a loss counts against both measures`() {
        val accounting = LossAccounting()
        accounting.recordLoss(1.50)

        assertEquals(1.50, accounting.streakLoss, 0.0001)
        assertEquals(1.50, accounting.dailyLoss, 0.0001)
    }

    @Test
    fun `a win resets the streak and reduces the day`() {
        val accounting = LossAccounting()
        accounting.recordLoss(1.50)
        accounting.recordWin(1.50)

        // The streak is over, so the stop loss starts again.
        assertEquals(0.0, accounting.streakLoss, 0.0001)
        // The win recovered the day's loss, so the net daily figure is back to zero.
        assertEquals(0.0, accounting.dailyLoss, 0.0001)
    }

    @Test
    fun `a partial win reduces but does not clear the day`() {
        // The recovery ladder's case: a loss is mostly recovered but not fully — the remainder
        // stands against the limit, and only the remainder.
        val accounting = LossAccounting()
        accounting.recordLoss(20.00)
        accounting.recordWin(6.00)

        assertEquals(14.00, accounting.dailyLoss, 0.0001)
        assertFalse("still under the 20 limit", accounting.isDailyLimitHit(20.00))
        assertTrue("at the 14 limit", accounting.isDailyLimitHit(14.00))
    }

    @Test
    fun `a win larger than the day's loss floors the daily figure at zero`() {
        val accounting = LossAccounting()
        accounting.recordLoss(5.00)
        accounting.recordWin(12.00)

        assertEquals(0.0, accounting.dailyLoss, 0.0001)
    }

    @Test
    fun `a recovered loss does not reach the stop loss`() {
        // The reported case, exactly: a $2 loss, then a recovery win that banks the money back.
        // The stop loss is $2, so under the old single-counter behaviour the run stopped on a loss
        // the account had already recovered.
        val accounting = LossAccounting()
        accounting.recordLoss(2.00)

        assertTrue("the loss itself should reach the stop loss", accounting.isStopLossHit(2.00))

        accounting.recordWin(2.00)

        assertFalse(
            "a recovered loss must not count against the stop loss",
            accounting.isStopLossHit(2.00)
        )
    }

    @Test
    fun `a win between two losses clears the streak and the recovered day`() {
        val accounting = LossAccounting()
        accounting.recordLoss(1.00)
        accounting.recordWin(0.40)
        accounting.recordLoss(0.50)

        // The new streak stands on its own: the second loss is 0.50 deep, not 1.50.
        assertEquals(0.50, accounting.streakLoss, 0.0001)
        // The day keeps the net: 1.00 lost, 0.40 recovered, 0.50 lost again.
        assertEquals(1.10, accounting.dailyLoss, 0.0001)
    }

    @Test
    fun `losses in the same streak accumulate`() {
        val accounting = LossAccounting()
        accounting.recordLoss(1.00)
        accounting.recordLoss(1.50)

        assertEquals(2.50, accounting.streakLoss, 0.0001)
    }

    @Test
    fun `ending a run resets the streak but not the day`() {
        val accounting = LossAccounting()
        accounting.recordLoss(3.00)
        accounting.endRun()

        assertEquals(0.0, accounting.streakLoss, 0.0001)
        // Ending a run does not give the money back, so the daily budget is untouched. It used to
        // be reset here, which meant stopping and restarting bought a fresh daily allowance.
        assertEquals(3.00, accounting.dailyLoss, 0.0001)
    }

    @Test
    fun `a new day clears the daily total but not the streak`() {
        val accounting = LossAccounting()
        accounting.recordLoss(5.00)
        accounting.rolloverDay()

        assertEquals(0.0, accounting.dailyLoss, 0.0001)
        // The streak is a run's property, not a day's, so midnight does not touch it.
        assertEquals(5.00, accounting.streakLoss, 0.0001)
    }

    @Test
    fun `a zero or negative loss is ignored`() {
        val accounting = LossAccounting()
        accounting.recordLoss(0.0)
        accounting.recordLoss(-5.0)

        assertEquals(0.0, accounting.streakLoss, 0.0001)
        assertEquals(0.0, accounting.dailyLoss, 0.0001)
    }

    @Test
    fun `the limits are reached at the threshold`() {
        val accounting = LossAccounting()
        accounting.recordLoss(9.99)
        assertFalse(accounting.isStopLossHit(10.0))

        accounting.recordLoss(0.01)
        assertTrue(accounting.isStopLossHit(10.0))
    }
}
