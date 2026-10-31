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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entry sweep: every subscribed market evaluated in one pass, each judged on the probability
 * its own contract pays out on.
 *
 * These numbers decide which market a real-money order goes into, so each expectation is derived by
 * hand from a window with known digit counts rather than read back off the implementation.
 */
class MarketSweepMathTest {

    private fun frequencies(vararg counts: Pair<Int, Int>): List<DigitFrequency> {
        val total = counts.sumOf { it.second }.coerceAtLeast(1)
        val byDigit = counts.toMap()
        return (0..9).map { d ->
            val c = byDigit[d] ?: 0
            DigitFrequency(d, c, c.toDouble() / total * 100.0, TrendDirection.EQUAL)
        }
    }

    private fun settings(
        threshold: Double = 70.0,
        operator: ComparisonOperator = ComparisonOperator.GREATER_THAN,
        digit: Int = 4
    ) = TradeSettings(
        targetDigit = digit,
        recoveryDigit = 4,
        comparisonOperator = operator,
        thresholdPercent = threshold,
        digitSelectionMode = DigitSelectionMode.FIXED,
        stakeAmount = 0.35
    )

    private fun recoverySettings(
        operator: ComparisonOperator = ComparisonOperator.GREATER_THAN,
        threshold: Double = 55.0
    ) = TradeSettings(
        targetDigit = 4,
        recoveryDigit = 4,
        recoveryDirection = "OVER",
        comparisonOperator = ComparisonOperator.GREATER_THAN,
        thresholdPercent = 70.0,
        recoveryComparisonOperator = operator,
        recoveryThresholdPercent = threshold,
        digitSelectionMode = DigitSelectionMode.FIXED,
        stakeAmount = 0.35
    )

    private fun state(symbol: String, freqs: List<DigitFrequency>, sampleSize: Int = 100) =
        MarketState(
            symbol = symbol,
            displayName = SupportedMarkets.find(symbol).displayName,
            shortName = SupportedMarkets.find(symbol).shortName,
            pipSize = 3,
            digitFrequencies = freqs,
            sampleSize = sampleSize
        )

    // 4 is hot: 30 of 100 ticks.
    private val hotFour = frequencies(
        0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 30, 5 to 7, 6 to 7, 7 to 7, 8 to 7, 9 to 10
    )
    private val uniform = frequencies(*(0..9).map { it to 10 }.toTypedArray())
    // 4 never appears at all, so it differs 100% and over/under splits 55/45.
    private val coldFour = frequencies(
        0 to 12, 1 to 11, 2 to 11, 3 to 11, 4 to 0, 5 to 11, 6 to 11, 7 to 11, 8 to 11, 9 to 11
    )

    private fun sweep(
        s: TradeSettings = settings(),
        settingsWithRecovery: TradeSettings? = null,
        isRecovery: Boolean = false,
        excludeSymbol: String? = null,
        randomMarkets: Boolean = true,
        stateFor: (String) -> MarketState? = { state(it, uniform) },
        digitFor: (String) -> Int = { 4 },
        coolOff: (TradeEntry) -> CooldownState? = { null },
        rotation: MarketRotation? = null
    ) = sweepMarkets(
        // `settingsWithRecovery` patches ONLY the recovery pair onto `s`. Passing it as a whole
        // object instead would silently replace the Differs condition too, which is the very thing
        // the recovery tests need to hold fixed — that is what made an early version of the
        // "differs still uses the Differs condition" test pass for the wrong reason.
        settings = settingsWithRecovery?.let { s.copy(
            recoveryComparisonOperator = it.recoveryComparisonOperator,
            recoveryThresholdPercent = it.recoveryThresholdPercent
        ) } ?: s,
        config = SystemConfig(randomMarkets = randomMarkets, maxTradesPerMarket = 3),
        isRecovery = isRecovery,
        excludeSymbol = excludeSymbol,
        digitFor = digitFor,
        getMarketState = stateFor,
        coolOff = coolOff,
        rotation = rotation
    )

    // ------------------------------------------------------------ overPercentFor / underPercentFor

    @Test
    fun `over under and equals partition the window with no overlap`() {
        val over = DigitAnalysisEngine.overPercentFor(hotFour, 4)
        val under = DigitAnalysisEngine.underPercentFor(hotFour, 4)
        val equals = hotFour.first { it.digit == 4 }.percentage

        assertEquals(38.0, over, 0.001)
        assertEquals(32.0, under, 0.001)
        assertEquals(100.0, over + under + equals, 0.001)
    }

    @Test
    fun `over and under on a uniform window are 50 and 40 at barrier 4`() {
        assertEquals(50.0, DigitAnalysisEngine.overPercentFor(uniform, 4), 0.001)
        assertEquals(40.0, DigitAnalysisEngine.underPercentFor(uniform, 4), 0.001)
    }

    @Test
    fun `barriers at the extremes collapse one side to zero`() {
        assertEquals(0.0, DigitAnalysisEngine.underPercentFor(uniform, 0), 0.001)
        assertEquals(0.0, DigitAnalysisEngine.overPercentFor(uniform, 9), 0.001)
        assertEquals(90.0, DigitAnalysisEngine.overPercentFor(uniform, 0), 0.001)
    }

    // ------------------------------------------------------------- observedPercentForTrade

    @Test
    fun `a Differs read is judged on differs percent regardless of the recovery direction`() {
        assertEquals(
            70.0,
            DigitAnalysisEngine.observedPercentForTrade(hotFour, 4, isRecovery = false, isOver = true),
            0.001
        )
        assertEquals(
            70.0,
            DigitAnalysisEngine.observedPercentForTrade(hotFour, 4, isRecovery = false, isOver = false),
            0.001
        )
    }

    @Test
    fun `a recovery read is judged on the payout probability of its own contract`() {
        assertEquals(
            38.0,
            DigitAnalysisEngine.observedPercentForTrade(hotFour, 4, isRecovery = true, isOver = true),
            0.001
        )
        assertEquals(
            32.0,
            DigitAnalysisEngine.observedPercentForTrade(hotFour, 4, isRecovery = true, isOver = false),
            0.001
        )
    }

    /**
     * The regression this file exists for. Before, the recovery path skipped the entry condition
     * entirely and fired unconditionally; now the same window and threshold can admit a Differs
     * read and refuse the Over/Under read built from it.
     */
    @Test
    fun `one threshold can admit a Differs read and reject the Over read from the same window`() {
        val threshold = 60.0
        val differs = DigitAnalysisEngine.observedPercentForTrade(hotFour, 4, false, true)
        val over = DigitAnalysisEngine.observedPercentForTrade(hotFour, 4, true, true)

        assertTrue(
            "differs $differs should clear $threshold",
            DigitAnalysisEngine.evaluateCondition(differs, threshold, ComparisonOperator.GREATER_THAN, 100)
        )
        assertFalse(
            "over $over should not clear $threshold",
            DigitAnalysisEngine.evaluateCondition(over, threshold, ComparisonOperator.GREATER_THAN, 100)
        )
    }

    // ------------------------------------------------------------------------- the sweep itself

    @Test
    fun `every subscribed market is evaluated, not one random market`() {
        val s = sweep()
        assertEquals(SupportedMarkets.ALL.size, s.readings.size)
        assertEquals(5, s.evaluatedCount)
        s.readings.forEach {
            assertNotNull("${it.symbol} should have been priced", it.observedPercent)
        }
    }

    @Test
    fun `a market short of data is reported unevaluated rather than as zero percent`() {
        val s = sweep(stateFor = { symbol ->
            if (symbol == "R_25") state(symbol, uniform, sampleSize = 3) else state(symbol, uniform)
        })
        val cold = s.readings.first { it.symbol == "R_25" }

        assertFalse("a 3-tick window must not qualify", cold.qualifies)
        assertNull("no percent can honestly be claimed from 3 ticks", cold.observedPercent)
        assertTrue("the reason should say what is missing, got '${cold.reason}'",
            cold.reason.contains("ticks"))
    }

    @Test
    fun `a market with no ticks at all is distinguished from one that measured zero`() {
        val s = sweep(stateFor = { symbol -> if (symbol == "R_50") null else state(symbol, uniform) })
        val missing = s.readings.first { it.symbol == "R_50" }

        assertNull(missing.observedPercent)
        assertEquals("no tick data yet", missing.reason)
    }

    @Test
    fun `when nothing clears the threshold there is no candidate`() {
        // Differs cannot exceed 100 and only reaches it for a digit absent from the window, so a
        // uniform window sits at exactly 90 and never clears 95.
        val s = sweep(settings(threshold = 95.0))

        assertEquals(5, s.evaluatedCount)
        assertEquals(0, s.qualifyingCount)
        assertNull(s.candidate)
    }

    /**
     * The behaviour that was missing entirely: a qualifying market is found even though the sweep
     * visits every market, instead of being missed while one random market is tested per second.
     */
    @Test
    fun `a cold digit in one market qualifies while the same digit elsewhere does not`() {
        val s = sweep(
            settings(threshold = 70.0),
            stateFor = { symbol ->
                state(symbol, if (symbol == "R_10") coldFour else hotFour)
            }
        )

        val cold = s.readings.first { it.symbol == "R_10" }
        val warm = s.readings.first { it.symbol == "R_25" }

        assertEquals("digit 4 never appears in R_10", 100.0, cold.observedPercent!!, 0.001)
        assertTrue(cold.qualifies)
        assertEquals(70.0, warm.observedPercent!!, 0.001)
        assertFalse("70 does not clear a strict >70", warm.qualifies)
        assertEquals("R_10", s.candidate?.symbol)
    }

    @Test
    fun `every qualifying market is offered, and the pick is one of them`() {
        val s = sweep(
            settings(threshold = 95.0),
            stateFor = { symbol ->
                state(symbol, if (symbol in setOf("R_10", "R_50", "R_100")) coldFour else uniform)
            }
        )
        assertEquals(3, s.qualifyingCount)
        assertTrue(s.candidate?.symbol in setOf("R_10", "R_50", "R_100"))
    }

    @Test
    fun `the market just traded is not chosen again while another qualifier exists`() {
        // Three markets clear the threshold and R_50 is the one that just carried a trade. Whichever
        // way the draw goes it must not land back on R_50.
        repeat(50) {
            val s = sweep(
                settings(threshold = 95.0),
                excludeSymbol = "R_50",
                stateFor = { symbol ->
                    state(symbol, if (symbol in setOf("R_10", "R_50", "R_100")) coldFour else uniform)
                }
            )
            // The rule is "not R_50", not "must be one of these two" — the draw is free to pick either
            // alternative. Asserting the forbidden value directly is what actually pins the rule.
            // No message overloads on this JUnit version, so the diagnostic is built into the
            // compared values for a failure to be readable.
            assertNotEquals(
                "chose ${s.candidate?.symbol}, which is the market just traded",
                "R_50",
                s.candidate?.symbol
            )
            assertNull("nothing was passed over, so nothing should be reported held back", s.heldBack)
        }
    }

    /**
     * The case that produced the consecutive same-market trades reported from the device.
     *
     * This previously *took* the repeat, on the reasoning that stalling was worse. With a
     * threshold near the Differs baseline one market qualifying alone is common, so that
 * *exception* was in fact the normal path — the rule was effectively off in exactly the
     * configuration the user was running.
     */
    @Test
    fun `a sole qualifier that was just traded is held, not taken again`() {
        val s = sweep(
            settings(threshold = 95.0),
            excludeSymbol = "R_50",
            stateFor = { symbol -> state(symbol, if (symbol == "R_50") coldFour else uniform) }
        )
        assertEquals(1, s.qualifyingCount)
        assertNull("trading the sole qualifier again is exactly the repeat being prevented", s.candidate)
        assertEquals("R_50", s.heldBack?.symbol)
    }

    /** Once a different market qualifies, the hold releases and trading resumes. */
    @Test
    fun `holding ends as soon as a second market qualifies`() {
        val s = sweep(
            settings(threshold = 95.0),
            excludeSymbol = "R_50",
            stateFor = { symbol ->
                state(symbol, if (symbol == "R_50" || symbol == "R_100") coldFour else uniform)
            }
        )
        assertEquals(2, s.qualifyingCount)
        assertEquals("R_100", s.candidate?.symbol)
        assertNull(s.heldBack)
    }

    @Test
    fun `nothing is excluded before the first trade`() {
        val s = sweep(
            settings(threshold = 95.0),
            excludeSymbol = null,
            stateFor = { symbol ->
                state(symbol, if (symbol in setOf("R_10", "R_50", "R_100")) coldFour else uniform)
            }
        )
        assertTrue(s.candidate?.symbol in setOf("R_10", "R_50", "R_100"))
        assertNull(s.heldBack)
    }

    @Test
    fun `excluding a market that does not qualify changes nothing`() {
        val s = sweep(
            settings(threshold = 95.0),
            excludeSymbol = "R_25",
            stateFor = { symbol ->
                state(symbol, if (symbol in setOf("R_10", "R_50", "R_100")) coldFour else uniform)
            }
        )
        assertEquals(3, s.qualifyingCount)
        assertTrue(s.candidate?.symbol in setOf("R_10", "R_50", "R_100"))
        assertNull(s.heldBack)
    }

    /** The digit a market will trade is the one judged, not always the configured one. */
    @Test
    fun `random mode judges each market on the digit drawn for it`() {
        val s = sweep(
            settings(threshold = 95.0, digit = 9),
            stateFor = { symbol -> state(symbol, if (symbol == "R_25") coldFour else uniform) },
            digitFor = { symbol -> if (symbol == "R_25") 4 else 9 }
        )
        // Digit 9 is 10% of a uniform window, so it differs 90 and never clears 95 — except the one
        // market the engine drew digit 4 for, where digit 4 never appears and differs is 100.
        s.readings.filter { it.symbol != "R_25" }.forEach {
            assertEquals("${it.symbol} should be judged on digit 9", 9, it.digit)
            assertEquals(90.0, it.observedPercent!!, 0.001)
            assertFalse(it.qualifies)
        }
        val drawn = s.readings.first { it.symbol == "R_25" }
        assertEquals(4, drawn.digit)
        assertEquals(100.0, drawn.observedPercent!!, 0.001)
        assertEquals("R_25", s.candidate?.symbol)
    }

    /**
     * Recovery judges Over/Under readings against the RECOVERY condition, not the Differs one.
     *
     * This is the behaviour the split exists for. Both contract types were previously tested
     * against `thresholdPercent`, so a Differs threshold — which has to sit above the ~90%
     * P(≠ digit) baseline to be meaningful — was being applied to a ~50% recovery scale. At a
     * realistic Differs threshold of 92 nothing recovery reads can ever clear it, which is why
     * recovery previously only ever fired through the forced-placement fallback.
     */
    @Test
    fun `recovery uses its own threshold, not the Differs one`() {
        val s = sweep(
            // Differs threshold deliberately set to something recovery readings could never meet.
            settings(threshold = 92.0),
            isRecovery = true,
            stateFor = { symbol -> state(symbol, coldFour) }
        )
        // coldFour: over 4 = 55%. The recovery condition is the separate default, >55, and
        // 55 does not clear a strict >55 — so this must NOT qualify despite the Differs
        // threshold being irrelevant.
        assertEquals(55.0, s.readings.first().observedPercent!!, 0.001)
        assertEquals(0, s.qualifyingCount)
        assertNull(s.candidate)
    }

    @Test
    fun `recovery readings qualify when the recovery condition is met`() {
        val s = sweep(
            settings(threshold = 92.0),
            settingsWithRecovery = recoverySettings(operator = ComparisonOperator.GREATER_OR_EQUAL, threshold = 55.0),
            isRecovery = true,
            stateFor = { symbol -> state(symbol, coldFour) }
        )
        // Over 4 = 55%, which clears >=55 in every market, so the whole field qualifies even
        // though the Differs threshold of 92 would have blocked every one of them.
        assertEquals(55.0, s.readings.first().observedPercent!!, 0.001)
        assertEquals(5, s.qualifyingCount)
        assertTrue(s.readings.all { it.qualifies })
    }

    /** A below-test on the recovery scale must reject a high reading, not accept it. */
    @Test
    fun `a below recovery condition reverses which readings qualify`() {
        val s = sweep(
            settings(threshold = 92.0),
            settingsWithRecovery = recoverySettings(operator = ComparisonOperator.LESS_THAN, threshold = 60.0),
            isRecovery = true,
            stateFor = { symbol -> state(symbol, coldFour) }
        )
        // Over 4 = 55%, which does clear a strict <60.
        assertEquals(5, s.qualifyingCount)

        val strict = sweep(
            settings(threshold = 92.0),
            settingsWithRecovery = recoverySettings(operator = ComparisonOperator.LESS_THAN, threshold = 50.0),
            isRecovery = true,
            stateFor = { symbol -> state(symbol, coldFour) }
        )
        assertEquals(0, strict.qualifyingCount)
    }

    /**
     * The margin drives the progress bars and the "best opportunity" pick, so it must read as
     * positive when the condition is met even under a below-test. `observed - threshold` inverts
     * that, which would make the engine prefer the market least likely to pay out.
     */
    @Test
    fun `margin reads positive when a below-test is met`() {
        val s = sweep(
            settings(threshold = 92.0),
            settingsWithRecovery = recoverySettings(operator = ComparisonOperator.LESS_THAN, threshold = 60.0),
            isRecovery = true,
            stateFor = { symbol -> state(symbol, coldFour) }
        )
        val met = s.readings.first()
        assertTrue("a satisfied below-test must report a positive margin", met.marginPercent!! > 0.0)
    }

    @Test
    fun `margin reads negative when a below-test fails`() {
        val s = sweep(
            settings(threshold = 92.0),
            settingsWithRecovery = recoverySettings(operator = ComparisonOperator.LESS_THAN, threshold = 50.0),
            isRecovery = true,
            stateFor = { symbol -> state(symbol, coldFour) }
        )
        val missed = s.readings.first()
        assertFalse("55 is not below 50, so the margin must read negative", missed.marginPercent!! > 0.0)
    }

    /** The Differs condition must be untouched by the recovery split. */
    @Test
    fun `differs readings still use the Differs condition`() {
        val s = sweep(
            settings(threshold = 95.0),
            settingsWithRecovery = recoverySettings(operator = ComparisonOperator.LESS_THAN, threshold = 1.0),
            stateFor = { symbol ->
                state(symbol, if (symbol in setOf("R_10", "R_50", "R_100")) coldFour else uniform)
            }
        )
        // The recovery condition (<1%) is wildly permissive and must not leak into the Differs
        // sweep: only the three markets clearing 95% on differs may qualify.
        assertEquals(3, s.qualifyingCount)
    }

    // ------------------------------------------------------------ post-loss cool-off

    private fun coolOff(vararg entries: Pair<String, Int>) = { entry: TradeEntry ->
        entries.firstOrNull { it.first == entry.symbol && it.second == entry.digit }
            ?.let { CooldownState(tradesRemaining = 5) }
    }

    /**
     * The user's reported case: Vol 25 kept qualifying and kept losing, so its statistics alone can
     * never be the reason to leave it alone. A cool-off has to be able to veto a market that is
     * showing a qualifying percentage.
     */
    @Test
    fun `a market on a cool-off does not qualify even when its reading clears the threshold`() {
        val s = sweep(
            settings(threshold = 95.0),
            stateFor = { symbol -> state(symbol, coldFour) },
            coolOff = coolOff("R_25" to 4)
        )

        val parked = s.readings.first { it.symbol == "R_25" }
        assertEquals("R_25 still measures a qualifying 100%", 100.0, parked.observedPercent!!, 0.001)
        assertFalse("a cool-off has to be able to veto a qualifying reading", parked.qualifies)
        assertEquals(5, parked.coolOffRemaining)
        assertTrue(
            "the reason has to say why, got '${parked.reason}'",
            parked.reason.contains("cooling off")
        )
        assertEquals(1, s.coolingOffCount)
    }

    @Test
    fun `a cool-off on one market leaves the others tradeable`() {
        val s = sweep(
            settings(threshold = 95.0),
            stateFor = { symbol -> state(symbol, coldFour) },
            coolOff = coolOff("R_25" to 4)
        )

        assertEquals(4, s.qualifyingCount)
        assertNotEquals("R_25", s.candidate?.symbol)
    }

    @Test
    fun `cooling off every market leaves nothing to trade`() {
        val s = sweep(
            settings(threshold = 95.0),
            stateFor = { symbol -> state(symbol, coldFour) },
            coolOff = coolOff(*SupportedMarkets.ALL.map { it.symbol to 4 }.toTypedArray())
        )

        assertEquals(0, s.qualifyingCount)
        assertNull(s.candidate)
        assertEquals(5, s.coolingOffCount)
    }

    /** Nothing is restricted when the tracker knows nothing, which is the normal first-run state. */
    @Test
    fun `no cool-off means nothing is restricted`() {
        val s = sweep(settings(threshold = 95.0), stateFor = { symbol -> state(symbol, coldFour) })

        assertEquals(5, s.qualifyingCount)
        assertEquals(0, s.coolingOffCount)
        assertTrue(s.readings.all { it.coolOffRemaining == 0 })
    }

    /**
     * Recovery is gated on its own contract, so a Differs cool-off must not silently park the
     * recovery rung the martingale ladder is waiting on.
     */
    @Test
    fun `a differs cool-off does not park the recovery sweep`() {
        val differsOnly = { entry: TradeEntry ->
            if (entry.tradeType == com.tickedge.data.model.TradeType.DIFFERS &&
                entry.symbol == "R_25" && entry.digit == 4
            ) CooldownState(tradesRemaining = 5) else null
        }

        val s = sweep(
            settings(threshold = 92.0),
            settingsWithRecovery = recoverySettings(
                operator = ComparisonOperator.GREATER_OR_EQUAL, threshold = 55.0
            ),
            isRecovery = true,
            stateFor = { symbol -> state(symbol, coldFour) },
            coolOff = differsOnly
        )

        assertEquals(5, s.qualifyingCount)
        assertEquals(0, s.coolingOffCount)
    }

    /** ...but a recovery cool-off on the same market and digit does apply, on the recovery sweep. */
    @Test
    fun `a recovery cool-off parks the recovery sweep`() {
        val recoveryOnly = { entry: TradeEntry ->
            if (entry.tradeType == com.tickedge.data.model.TradeType.OVER_UNDER &&
                entry.symbol == "R_25" && entry.digit == 4
            ) CooldownState(tradesRemaining = 3) else null
        }

        val s = sweep(
            settings(threshold = 92.0),
            settingsWithRecovery = recoverySettings(
                operator = ComparisonOperator.GREATER_OR_EQUAL, threshold = 55.0
            ),
            isRecovery = true,
            stateFor = { symbol -> state(symbol, coldFour) },
            coolOff = recoveryOnly
        )

        assertEquals(4, s.qualifyingCount)
        assertEquals(3, s.readings.first { it.symbol == "R_25" }.coolOffRemaining)
    }
}