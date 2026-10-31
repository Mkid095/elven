package com.tickedge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The published shape of a strategy's run.
 *
 * These are the figures the notification, the strategy card and the strategy list all read, so
 * they have to be right in the one place they are produced. A label that misreports a loss as a
 * profit is worse than no label at all.
 */
class StrategyRunStateTest {

    private fun state(
        status: RunStatus = RunStatus.SCANNING,
        trades: Int = 0,
        wins: Int = 0,
        losses: Int = 0,
        netPnl: Double = 0.0,
        consecutiveLosses: Int = 0,
        activeTradeSummary: String? = null
    ) = StrategyRunState(
        strategyId = "differs_with_recovery",
        strategyName = "Differs + Over/Under Recovery",
        status = status,
        trades = trades,
        wins = wins,
        losses = losses,
        netPnl = netPnl,
        consecutiveLosses = consecutiveLosses,
        activeTradeSummary = activeTradeSummary
    )

    @Test
    fun `a profit is shown with a plus sign`() {
        assertEquals("+$3.20", state(netPnl = 3.2).pnlLabel)
    }

    @Test
    fun `a loss is shown with a minus sign`() {
        assertEquals("-$1.10", state(netPnl = -1.1).pnlLabel)
    }

    @Test
    fun `zero is neither a profit nor a loss`() {
        assertEquals("+$0.00", state(netPnl = 0.0).pnlLabel)
    }

    @Test
    fun `one trade reads as one trade`() {
        assertEquals("1 trade", state(trades = 1).tradesLabel)
    }

    @Test
    fun `several trades read as trades`() {
        assertEquals("7 trades", state(trades = 7).tradesLabel)
    }

    @Test
    fun `no settled trades have no record`() {
        assertEquals(null, state(trades = 0).recordLabel)
    }

    @Test
    fun `the record counts wins and losses`() {
        assertEquals("2 wins · 1 loss", state(trades = 3, wins = 2, losses = 1).recordLabel)
    }

    @Test
    fun `one win reads as one win`() {
        assertEquals("1 win · 0 losses", state(trades = 1, wins = 1, losses = 0).recordLabel)
    }

    @Test
    fun `only the running statuses count as running`() {
        assertTrue(state(status = RunStatus.SCANNING).isRunning)
        assertTrue(state(status = RunStatus.IN_TRADE).isRunning)
        assertTrue(state(status = RunStatus.RECOVERING).isRunning)
        assertFalse(state(status = RunStatus.IDLE).isRunning)
        assertFalse(state(status = RunStatus.STOPPED).isRunning)
    }

    @Test
    fun `the headline leads with the active trade when there is one`() {
        val running = state(
            status = RunStatus.IN_TRADE,
            trades = 3,
            activeTradeSummary = "Volatility 25 · differs from 2 · $0.35"
        )
        assertEquals("Volatility 25 · differs from 2 · $0.35", running.headline())
    }

    @Test
    fun `the headline falls back to the status and figures`() {
        val scanning = state(status = RunStatus.SCANNING, trades = 4, netPnl = -1.1)
        assertEquals("Scanning · 4 trades · -$1.10", scanning.headline())
    }

    @Test
    fun `every status has a label for the notification`() {
        RunStatus.values().forEach { status ->
            assertTrue(
                "status $status has no label",
                status.label.isNotBlank()
            )
        }
    }
}
