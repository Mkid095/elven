package com.tickedge.domain

import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.DigitFrequency
import com.tickedge.data.model.DigitSelectionMode
import com.tickedge.data.model.MarketState
import com.tickedge.data.model.SupportedMarkets
import com.tickedge.data.model.SystemConfig
import com.tickedge.data.model.TradeSettings
import com.tickedge.data.model.TrendDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often each market is allowed to carry a trade.
 *
 * The failure these guard against was observed on device, not imagined: a run placed its orders
 * across only two or three of the five markets while the rest were never looked at again, and
 * nothing on screen said why. [MarketCooldownTest] covers the opposite rule — stepping away from a
 * market that keeps losing — so this file covers stepping away from one the bot keeps leaning on.
 *
 * Every expectation is a fixed sequence of settlements checked by hand, because the whole point is
 * what the *order* of trades does to the counts.
 */
class MarketRotationTest {

    private val symbols = SupportedMarkets.ALL.map { it.symbol }

    // ------------------------------------------------------------------ the tracker on its own

    @Test
    fun `counts only the trades still inside the window`() {
        val rotation = MarketRotation(maxTradesPerMarket = 3, windowTrades = 4)
        rotation.record("R_10")
        rotation.record("R_10")
        rotation.record("R_25")
        assertEquals(2, rotation.tradesInWindow("R_10"))

        // Two more trades push the first R_10 out of a four-trade window.
        rotation.record("R_50")
        rotation.record("R_75")
        assertEquals(1, rotation.tradesInWindow("R_10"))
    }

    @Test
    fun `a market is over frequency only once it reaches the ceiling`() {
        val rotation = MarketRotation(maxTradesPerMarket = 3, windowTrades = 12)
        repeat(2) { rotation.record("R_25") }
        assertFalse(rotation.isOverFrequency("R_25"))

        rotation.record("R_25")
        assertTrue(rotation.isOverFrequency("R_25"))
    }

    @Test
    fun `a market never traded is free`() {
        val rotation = MarketRotation(maxTradesPerMarket = 1)
        assertFalse(rotation.isOverFrequency("R_100"))
        assertEquals(0, rotation.tradesInWindow("R_100"))
        assertEquals(null, rotation.lastTraded("R_100"))
    }

    @Test
    fun `a ceiling of zero disables the rule rather than banning everything`() {
        // 0 would otherwise mean "every market is over frequency" and the sweep would fall through
        // to the single least-used candidate on every single pass.
        val rotation = MarketRotation(maxTradesPerMarket = 0)
        rotation.record("R_25")
        assertFalse(rotation.isOverFrequency("R_25"))
    }

    @Test
    fun `rotating away from one market lets it come back once others catch up`() {
        val rotation = MarketRotation(maxTradesPerMarket = 2, windowTrades = 4)
        rotation.record("R_25")
        rotation.record("R_25")
        assertTrue(rotation.isOverFrequency("R_25"))

        // A four-trade window evicts only once a fifth trade arrives. Two others are not enough:
        // the window still holds both R_25 trades, so it stays over frequency — which is the
        // correct behaviour, since it has only been traded twice in four trades.
        rotation.record("R_10")
        rotation.record("R_50")
        assertTrue(rotation.isOverFrequency("R_25"))

        rotation.record("R_75")
        assertEquals(1, rotation.tradesInWindow("R_25"))
        assertFalse(rotation.isOverFrequency("R_25"))
    }

    // ------------------------------------------------------------------ the sweep's pick

    private fun frequencies(vararg counts: Pair<Int, Int>): List<DigitFrequency> {
        val total = counts.sumOf { it.second }.coerceAtLeast(1)
        val byDigit = counts.toMap()
        return (0..9).map { d ->
            val c = byDigit[d] ?: 0
            DigitFrequency(d, c, c.toDouble() / total * 100.0, TrendDirection.EQUAL)
        }
    }

    /**
     * Every market qualifies against a `> 70%` threshold.
     *
     * 4 appears on 20 of 120 ticks, so P(≠ 4) = 100/120 = 83.3%. The counts are chosen so the
     * result is clearly clear of the threshold rather than exactly on it — a fixture landing on
     * 70.000 makes `GREATER_THAN` fail and every "all markets qualify" test silently vacuous.
     */
    private val allQualify = frequencies(
        0 to 20, 1 to 10, 2 to 10, 3 to 10, 4 to 20, 5 to 10, 6 to 10, 7 to 10, 8 to 10, 9 to 10
    )

    private val settings = TradeSettings(
        targetDigit = 4,
        recoveryDigit = 4,
        comparisonOperator = ComparisonOperator.GREATER_THAN,
        thresholdPercent = 70.0,
        digitSelectionMode = DigitSelectionMode.FIXED,
        stakeAmount = 0.35
    )

    private fun stateFor(symbol: String) = MarketState(
        symbol = symbol,
        displayName = SupportedMarkets.find(symbol).displayName,
        shortName = SupportedMarkets.find(symbol).shortName,
        pipSize = 3,
        digitFrequencies = allQualify,
        sampleSize = 100
    )

    private fun sweep(
        rotation: MarketRotation?,
        excludeSymbol: String? = null,
        randomMarkets: Boolean = true
    ) = sweepMarkets(
        settings = settings,
        config = SystemConfig(randomMarkets = randomMarkets, maxTradesPerMarket = 3),
        isRecovery = false,
        excludeSymbol = excludeSymbol,
        digitFor = { 4 },
        getMarketState = { stateFor(it) },
        rotation = rotation
    )

    @Test
    fun `a market over its share is never the pick`() {
        val rotation = MarketRotation(maxTradesPerMarket = 2, windowTrades = 12)
        repeat(2) { rotation.record("R_25") }

        repeat(20) {
            val pick = sweep(rotation, excludeSymbol = "R_25").candidate
            assertNotEquals("R_25 was traded ${rotation.tradesInWindow("R_25")} times", "R_25", pick?.symbol)
        }
    }

    @Test
    fun `the least used market is preferred over a more used one that also qualifies`() {
        val rotation = MarketRotation(maxTradesPerMarket = 3, windowTrades = 12)
        rotation.record("R_10")
        rotation.record("R_25")
        rotation.record("R_50")

        // R_10, R_25 and R_50 have each carried one. R_75 and R_100 have carried none, so the pick
        // has to be one of those two — never one of the three already used.
        repeat(20) {
            val picked = sweep(rotation).candidate?.symbol
            assertTrue(
                "expected an unused market, got $picked",
                picked == "R_75" || picked == "R_100"
            )
        }
    }

    @Test
    fun `the rule yields rather than forgoing a trade when every qualifier is over its share`() {
        val rotation = MarketRotation(maxTradesPerMarket = 1, windowTrades = 12)
        rotation.record("R_25")
        val result = sweepMarkets(
            settings = settings,
            config = SystemConfig(randomMarkets = true, maxTradesPerMarket = 1),
            isRecovery = false,
            excludeSymbol = null,
            digitFor = { 4 },
            getMarketState = { s -> if (s == "R_25") stateFor(s) else null },
            rotation = rotation
        )
        // Only one market has data, and it is over its share. A missing trade would be worse than a
        // repeated one, so the sweep still takes it.
        assertEquals("R_25", result.candidate?.symbol)
        assertTrue(result.rotationSkippedCount > 0)
    }

    @Test
    fun `without a rotation tracker the pick is unchanged`() {
        // Null means "no history yet" — every qualifying market stays equally eligible, which is
        // what the first sweep after a fresh install should look like.
        val result = sweep(rotation = null)
        assertEquals(5, result.qualifyingCount)
        assertEquals(0, result.rotationSkippedCount)
    }

    @Test
    fun `a run of twelve trades touches every market instead of two`() {
        // The shape of the observed failure: five markets all qualifying, and the field collapsing
        // onto a couple of them. Deterministic here because the pick narrows to the least-used.
        val rotation = MarketRotation(maxTradesPerMarket = 2, windowTrades = 12)
        val used = mutableListOf<String>()

        repeat(12) {
            val pick = sweep(rotation).candidate?.symbol
            assertTrue("no pick on trade ${used.size + 1}", pick != null)
            rotation.record(pick!!)
            used += pick
        }

        val distinct = used.toSet()
        assertEquals("only used: $used", symbols.size, distinct.size)
        // And no market runs away with it: the ceiling is 2 in a window of 12.
        symbols.forEach { symbol ->
            assertTrue(
                "$symbol carried ${rotation.tradesInWindow(symbol)} of the window",
                rotation.tradesInWindow(symbol) <= 3
            )
        }
    }
}
