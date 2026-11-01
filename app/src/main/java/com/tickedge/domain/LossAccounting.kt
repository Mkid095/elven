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
 *  - **How much is this account net down today?** The daily limit. A *net* figure: a win that
 *    recovers the day's losses brings it back toward zero, because that is what the recovery
 *    ladder exists for. What stays lost stands against the limit; the budget still restarts at
 *    midnight.
 *
 * Kept as its own class rather than as two fields on the trading engine so the rules can be tested
 * without a socket, a database and a coroutine.
 */
class LossAccounting {

    /** The cost of the losing streak in progress. Reset by a win. */
    var streakLoss: Double = 0.0
        private set

    /**
     * The account's net position against the day's budget: everything lost since midnight minus
     * what wins recovered. A recovery win that closes the hole brings this back toward zero, which
     * is the point of the ladder; a loss that stays lost stands against the limit.
     */
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
     * The streak ends, so the stop loss starts again from zero. The daily figure drops by the
     * win's net profit — a loss the win recovered stops counting against the budget. That is what
     * the old behaviour got wrong: the limit would stay tripped on money the account had already
     * won back, so the recovery ladder existed only to trip the very limit it was recovering from.
     *
     * Floored at zero: a win bigger than the day's net loss means the day is in profit, and a
     * negative "loss" would read as the account gaining budget it was never given.
     */
    fun recordWin(winAmount: Double) {
        streakLoss = 0.0
        if (winAmount > 0.0) dailyLoss = (dailyLoss - winAmount).coerceAtLeast(0.0)
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

    /** Both measures to zero — an explicit "start fresh", distinct from a run boundary. */
    fun resetAll() {
        streakLoss = 0.0
        dailyLoss = 0.0
    }

    /** Whether the stop loss has been reached. */
    fun isStopLossHit(stopLoss: Double): Boolean = streakLoss >= stopLoss

    /** Whether the daily budget has been reached. */
    fun isDailyLimitHit(dailyLimit: Double): Boolean = dailyLoss >= dailyLimit
}
