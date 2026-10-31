package com.tickedge.domain

import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.DigitFrequency
import com.tickedge.data.model.EvenOddMode
import com.tickedge.data.model.MarketState
import com.tickedge.data.model.SupportedMarkets
import com.tickedge.data.model.SystemConfig
import com.tickedge.data.model.TradeSettings
import com.tickedge.data.model.TrendDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Even/Odd entry sweep: which parity class a market's window points at, and which market a
 * DIGITEVEN/DIGITODD order goes into.
 *
 * The numbers decide which market a real-money order goes into, so each expectation is derived by
 * hand from a window with known digit counts rather than read back off the implementation.
 */
class EvenOddSweepTest {

    private fun frequencies(vararg counts: Pair<Int, Int>): List<DigitFrequency> {
        val total = counts.sumOf { it.second }.coerceAtLeast(1)
        val byDigit = counts.toMap()
        return (0..9).map { d ->
            val c = byDigit[d] ?: 0
            DigitFrequency(d, c, c.toDouble() / total * 100.0, TrendDirection.EQUAL)
        }
    }

    private fun state(symbol: String, freqs: List<DigitFrequency>, sampleSize: Int = 100) =
        MarketState(
            symbol = symbol,
            displayName = SupportedMarkets.find(symbol).displayName,
            shortName = SupportedMarkets.find(symbol).shortName,
            pipSize = 3,
            digitFrequencies = freqs,
            sampleSize = sampleSize
        )

    /**
     * Even-heavy window: even digits {0,2,4,6,8} carry 60 of 100 ticks, odd carry 40.
     * Counts: 0→12, 2→12, 4→12, 6→12, 8→12 (60), 1→8, 3→8, 5→8, 7→8, 9→8 (40).
     */
    private val evenHot = frequencies(
        0 to 12, 1 to 8, 2 to 12, 3 to 8, 4 to 12, 5 to 8, 6 to 12, 7 to 8, 8 to 12, 9 to 8
    )

    /** Odd-heavy window: even carry 40, odd carry 60. */
    private val oddHot = frequencies(
        0 to 8, 1 to 12, 2 to 8, 3 to 12, 4 to 8, 5 to 12, 6 to 8, 7 to 12, 8 to 8, 9 to 12
    )

    /** 50/50 window: neither side runs hot. */
    private val balanced = frequencies(*(0..9).map { it to 10 }.toTypedArray())

    private fun evenOddSettings(
        mode: EvenOddMode = EvenOddMode.AUTO,
        operator: ComparisonOperator = ComparisonOperator.GREATER_THAN,
        threshold: Double = 55.0
    ) = TradeSettings(
        evenOddMode = mode,
        evenOddOperator = operator,
        evenOddThreshold = threshold
    )

    private fun sweep(
        s: TradeSettings = evenOddSettings(),
        excludeSymbol: String? = null,
        randomMarkets: Boolean = true,
        stateFor: (String) -> MarketState? = { state(it, evenHot) },
        coolOff: (TradeEntry) -> CooldownState? = { null },
        rotation: MarketRotation? = null
    ) = sweepEvenOddMarkets(
        settings = s,
        config = SystemConfig(randomMarkets = randomMarkets, maxTradesPerMarket = 3),
        mode = s.evenOddMode,
        excludeSymbol = excludeSymbol,
        getMarketState = stateFor,
        coolOff = coolOff,
        rotation = rotation
    )

    // ------------------------------------------------------------- even/odd percentages

    @Test
    fun `even and odd percentages partition the window`() {
        assertEquals(60.0, DigitAnalysisEngine.evenPercentFor(evenHot), 0.001)
        assertEquals(40.0, DigitAnalysisEngine.oddPercentFor(evenHot), 0.001)
        assertEquals(
            100.0,
            DigitAnalysisEngine.evenPercentFor(evenHot) + DigitAnalysisEngine.oddPercentFor(evenHot),
            0.001
        )
    }

    @Test
    fun `parity percent for a class reads that class`() {
        assertEquals(60.0, DigitAnalysisEngine.parityPercentFor(evenHot, isEven = true), 0.001)
        assertEquals(40.0, DigitAnalysisEngine.parityPercentFor(evenHot, isEven = false), 0.001)
    }

    // ------------------------------------------------------------- class resolution

    @Test
    fun `auto above mode trades the hot side`() {
        // Even runs 60% in every market, so the hot side is Even everywhere, and 60 clears >55.
        val s = sweep(evenOddSettings(operator = ComparisonOperator.GREATER_THAN, threshold = 55.0))
        s.readings.forEach {
            assertEquals(PARITY_EVEN_SENTINEL, it.digit)
            assertEquals("Even", it.parityLabel)
            assertEquals(60.0, it.observedPercent!!, 0.001)
            assertTrue(it.qualifies)
        }
        assertNotNull(s.candidate)
    }

    @Test
    fun `auto below mode trades the cold side`() {
        // Even 60 / Odd 40: the cold side is Odd, and 40 clears a "below 45" test.
        val s = sweep(
            evenOddSettings(operator = ComparisonOperator.LESS_THAN, threshold = 45.0),
        )
        s.readings.forEach {
            assertEquals(PARITY_ODD_SENTINEL, it.digit)
            assertEquals("Odd", it.parityLabel)
            assertEquals(40.0, it.observedPercent!!, 0.001)
            assertTrue(it.qualifies)
        }
        assertNotNull(s.candidate)
    }

    @Test
    fun `a hot side that does not clear the threshold does not qualify`() {
        // Even 60 does not clear a strict >65, so nothing qualifies even though the numbers
        // name a side.
        val s = sweep(evenOddSettings(operator = ComparisonOperator.GREATER_THAN, threshold = 65.0))
        assertEquals(5, s.evaluatedCount)
        assertEquals(0, s.qualifyingCount)
        assertNull(s.candidate)
        s.readings.forEach {
            assertEquals(PARITY_EVEN_SENTINEL, it.digit)
            assertFalse(it.qualifies)
        }
    }

    @Test
    fun `a balanced window shows the even side and never qualifies on above`() {
        // 50/50: the hot side is a coin flip, and 50 never clears >55 — a balanced market is
        // exactly the "no signal" case the strategy should sit out.
        val s = sweep(
            evenOddSettings(operator = ComparisonOperator.GREATER_THAN, threshold = 55.0),
            stateFor = { state(it, balanced) }
        )
        assertEquals(0, s.qualifyingCount)
        s.readings.forEach { assertFalse(it.qualifies) }
    }

    @Test
    fun `fixed even mode judges only the even side`() {
        // Odd runs hot here, but the pinned mode still reads P(even) = 40, which clears "below 45".
        val s = sweep(
            evenOddSettings(mode = EvenOddMode.EVEN, operator = ComparisonOperator.LESS_THAN, threshold = 45.0),
            stateFor = { state(it, oddHot) }
        )
        s.readings.forEach {
            assertEquals(PARITY_EVEN_SENTINEL, it.digit)
            assertEquals(40.0, it.observedPercent!!, 0.001)
            assertTrue(it.qualifies)
        }
    }

    @Test
    fun `fixed odd mode is blind to the even side running hot`() {
        // Even is hot (60), but the pinned mode reads P(odd) = 40, which does not clear >55.
        val s = sweep(
            evenOddSettings(mode = EvenOddMode.ODD, operator = ComparisonOperator.GREATER_THAN, threshold = 55.0),
            stateFor = { state(it, evenHot) }
        )
        assertEquals(0, s.qualifyingCount)
        s.readings.forEach {
            assertEquals(PARITY_ODD_SENTINEL, it.digit)
            assertEquals(40.0, it.observedPercent!!, 0.001)
        }
    }

    // ------------------------------------------------------------- market pick

    @Test
    fun `every subscribed market is evaluated on its own window`() {
        // One market has even digits hot, one has odd hot; the two classes must end up on the
        // correct sides of 50, not both on one side.
        val s = sweep(
            stateFor = { symbol ->
                when (symbol) {
                    "R_10" -> state(symbol, evenHot)
                    "R_100" -> state(symbol, oddHot)
                    else -> state(symbol, balanced)
                }
            }
        )
        val evenSide = s.readings.first { it.symbol == "R_10" }
        val oddSide = s.readings.first { it.symbol == "R_100" }
        assertEquals(PARITY_EVEN_SENTINEL, evenSide.digit)
        assertEquals(PARITY_ODD_SENTINEL, oddSide.digit)
        assertEquals(60.0, evenSide.observedPercent!!, 0.001)
        assertEquals(60.0, oddSide.observedPercent!!, 0.001) // 60% of odd's own class
    }

    @Test
    fun `excluding the market just traded never lands back on it`() {
        repeat(50) {
            val s = sweep(excludeSymbol = "R_50")
            assertTrue(s.candidate != null)
            assertTrue(
                "no-repeat rule: R_50 just carried a trade but was picked again",
                s.candidate!!.symbol != "R_50"
            )
        }
    }

    @Test
    fun `a sole qualifier that was just traded is held, not taken again`() {
        // Only R_50 runs a hot side; the no-repeat rule holds rather than repeats it.
        val s = sweep(
            excludeSymbol = "R_50",
            stateFor = { symbol ->
                state(symbol, if (symbol == "R_50") evenHot else balanced)
            }
        )
        assertEquals(1, s.qualifyingCount)
        assertNull("trading the sole qualifier again is exactly the repeat being prevented", s.candidate)
        assertEquals("R_50", s.heldBack?.symbol)
    }

    // ------------------------------------------------------------- cool-off

    private fun parityCoolOff(vararg entries: Pair<String, Int>) = { entry: TradeEntry ->
        if (entry.tradeType == com.tickedge.data.model.TradeType.EVEN_ODD) {
            entries.firstOrNull { it.first == entry.symbol && it.second == entry.digit }
                ?.let { CooldownState(tradesRemaining = 5) }
        } else null
    }

    @Test
    fun `a cool-off on a market and class vetoes that reading`() {
        val s = sweep(
            coolOff = parityCoolOff("R_25" to PARITY_EVEN_SENTINEL)
        )
        val parked = s.readings.first { it.symbol == "R_25" }
        assertEquals("R_25 still measures a qualifying 60%", 60.0, parked.observedPercent!!, 0.001)
        assertFalse("a cool-off has to be able to veto a qualifying reading", parked.qualifies)
        assertEquals(5, parked.coolOffRemaining)
        assertEquals(4, s.qualifyingCount)
    }

    @Test
    fun `a differs cool-off does not park the parity sweep`() {
        val differsOnly = { entry: TradeEntry ->
            if (entry.tradeType == com.tickedge.data.model.TradeType.DIFFERS &&
                entry.symbol == "R_25"
            ) CooldownState(tradesRemaining = 5) else null
        }
        val s = sweep(coolOff = differsOnly)
        assertEquals(5, s.qualifyingCount)
        assertEquals(0, s.coolingOffCount)
    }
}
