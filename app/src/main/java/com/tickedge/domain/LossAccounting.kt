package com.tickedge.domain

/**
 * The two loss measures the risk limits read.
 *
 * They used to be one counter, and that was the bug. A single accumulator served both the **stop
 * loss** and the **daily limit**, and it only ever grew — so a loss that the recovery ladder had
 * already won back still counted against the stop loss. The bot would stop itself for money the
 * account no longer had: the ladder's entire purpose is to chase a loss, and then the stop loss
 * charged the user for it a second time.
 *
 * The two questions are genuinely different, so they get different answers:
 *
 *  - **How deep is this run in the hole right now?** The stop loss. Measured on the losing streak
 *    in progress. A win ends the streak, so a recovered loss is a recovered loss and the measure
 *    starts again.
 *  - **How much has this account lost today?** The daily limit. Measured on everything lost since
 *    midnight. A win does not undo it — the money was lost, even if it was earned back later.
 *
 * Kept as its own class rather than as two fields on the trading engine so the rules can be tested
 * without a socket, a database and a coroutine.
 */
class LossAccounting {

    /** The cost of the losing streak in progress. Reset by a win. */
    var streakLoss: Double = 0.0
        private set

    /** Everything lost since the day rolled over. Never reduced by a win. */
    var dailyLoss: Double = 0.0
        private set

    /**
     * Books a loss against both measures.
     *
     * The amount is the loss on the trade — the stake plus any fee, which is what actually left
     * the account. The *net* figure is not used: a contract that paid out less than it cost is
     * still a loss of the whole cost, and using the net would understate the damage.
     */
    fun recordLoss(amount: Double) {
        if (amount <= 0.0) return
        streakLoss += amount
        dailyLoss += amount
    }

    /**
     * Books a win.
     *
     * The streak ends, so the stop loss starts again from zero. The day's total is untouched: the
     * loss that prompted the recovery was real money and the daily limit is a budget, not a
     * running scoreboard.
     */
    fun recordWin() {
        streakLoss = 0.0
    }

    /**
     * Ends the run without ending the day.
     *
     * The streak is over because the run is over, so the stop loss applies afresh to the next run.
     * The day's total is deliberately left alone — ending a run does not give the money back, and
     * resetting it here would let a user buy a fresh daily budget by stopping and restarting.
     */
    fun endRun() {
        streakLoss = 0.0
    }

    /** Midnight passed. The day's budget starts again; the streak is untouched. */
    fun rolloverDay() {
        dailyLoss = 0.0
    }

    /** Whether the stop loss has been reached. */
    fun isStopLossHit(stopLoss: Double): Boolean = streakLoss >= stopLoss

    /** Whether the daily budget has been reached. */
    fun isDailyLimitHit(dailyLimit: Double): Boolean = dailyLoss >= dailyLimit
}
