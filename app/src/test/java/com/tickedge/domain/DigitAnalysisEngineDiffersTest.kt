package com.tickedge.domain

import com.tickedge.data.model.AnalysisMetric
import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.DigitFrequency
import com.tickedge.data.model.TickObservation
import com.tickedge.data.model.TrendDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Differs maths.
 *
 * This matters more than ordinary unit tests: the bot places real `DIGITDIFF` contracts, and the
 * bug this suite pins is that the entry condition was comparing the target digit's *equals* rate
 * while the contract settles on *differs*. A test that only checks arithmetic would have passed
 * against the broken behaviour, so the regression cases below are written in terms of whether a
 * trade should fire, not just what the numbers are.
 */
class DigitAnalysisEngineDiffersTest {

    /** Minimal TickObservation — only `lastDigit` is read by the functions under test. */
    private fun tick(digit: Int) = TickObservation(
        epoch = 0L,
        quote = 0.0,
        pipSize = 3,
        formattedQuote = "0.000",
        lastDigit = digit
    )

    /** `counts[i]` ticks whose last digit is i. */
    private fun ticksFromCounts(vararg counts: Int): List<TickObservation> =
        counts.flatMapIndexed { digit, count -> List(count) { tick(digit) } }

    private fun frequencies(vararg percents: Double): List<DigitFrequency> =
        percents.mapIndexed { digit, pct -> DigitFrequency(digit, 0, pct, TrendDirection.EQUAL) }

    // ---------------------------------------------------------------- computeDiffersStats

    @Test
    fun `differs and match percentages are exact complements`() {
        // 100 ticks: digit 3 appears 12 times, the other 88 differ from it.
        // counts[i] is the count FOR DIGIT i, so digit 3's 12 sits at index 3.
        val ticks = ticksFromCounts(0, 0, 0, 12, 0, 0, 0, 0, 0, 88)
        val stats = DigitAnalysisEngine.computeDiffersStats(ticks).first { it.digit == 3 }

        assertEquals(12, stats.matchCount)
        assertEquals(88, stats.differsCount)
        assertEquals(12.0, stats.matchPercentage, 0.001)
        assertEquals(88.0, stats.differsPercentage, 0.001)
        assertEquals(100.0, stats.matchPercentage + stats.differsPercentage, 0.001)
    }

    @Test
    fun `differs counts sum to the window size for every digit`() {
        val ticks = ticksFromCounts(10, 0, 25, 0, 5, 60)
        val total = ticks.size

        DigitAnalysisEngine.computeDiffersStats(ticks).forEach { stat ->
            assertEquals(
                "digit ${stat.digit} differs+match should equal the window size",
                total,
                stat.differsCount + stat.matchCount
            )
        }
    }

    @Test
    fun `empty window yields zeroed stats instead of dividing by zero`() {
        val stats = DigitAnalysisEngine.computeDiffersStats(emptyList())

        assertEquals(10, stats.size)
        stats.forEach { stat ->
            assertEquals(0, stat.matchCount)
            assertEquals(0, stat.differsCount)
            // 0/1 coerced — must be a real 0.0, not NaN from 0/0.
            assertEquals(0.0, stat.matchPercentage, 0.001)
            assertEquals(0.0, stat.differsPercentage, 0.001)
        }
    }

    @Test
    fun `a uniform window puts every digit at ninety percent differs`() {
        // Ten digits, ten ticks each: the fair-distribution baseline.
        val stats = DigitAnalysisEngine.computeDiffersStats(ticksFromCounts(10, 10, 10, 10, 10, 10, 10, 10, 10, 10))

        stats.forEach { stat ->
            assertEquals("digit ${stat.digit}", 90.0, stat.differsPercentage, 0.001)
        }
    }

    // ------------------------------------------------------------- differsPercentFor

    @Test
    fun `differsPercentFor is the complement of the equals frequency`() {
        val freqs = frequencies(0.0, 0.0, 0.0, 12.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        assertEquals(88.0, DigitAnalysisEngine.differsPercentFor(freqs, 3), 0.001)
    }

    @Test
    fun `differsPercentFor treats an unobserved digit as fully differing`() {
        val freqs = frequencies(10.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        assertEquals(100.0, DigitAnalysisEngine.differsPercentFor(freqs, 7), 0.001)
    }

    // --------------------------------------------------------------- observedPercentFor

    @Test
    fun `metric selection routes to the opposite side of the coin`() {
        val freqs = frequencies(0.0, 0.0, 0.0, 12.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        assertEquals(88.0, DigitAnalysisEngine.observedPercentFor(freqs, 3, AnalysisMetric.DIFFERS_PERCENT), 0.001)
        assertEquals(12.0, DigitAnalysisEngine.observedPercentFor(freqs, 3, AnalysisMetric.EQUALS_PERCENT), 0.001)
    }

    // ------------------------------------------------------- the regression this fixes

    @Test
    fun `differs metric fires when the target digit is cold`() {
        // Target digit 3 at 4%: the Differs contract wins 96% of the time. Entering is correct.
        val freqs = frequencies(10.4, 10.2, 10.1, 4.0, 10.1, 10.1, 10.1, 10.1, 10.4, 10.4)
        val observed = DigitAnalysisEngine.observedPercentFor(freqs, 3, AnalysisMetric.DIFFERS_PERCENT)

        assertTrue(
            "a cold target digit must satisfy 'enter above 92%'",
            DigitAnalysisEngine.evaluateCondition(
                observedPercent = observed,
                thresholdPercent = 92.0,
                operator = ComparisonOperator.GREATER_THAN,
                sampleSize = 100,
                minimumSampleSize = 10
            )
        )
    }

    @Test
    fun `differs metric does not fire when the target digit is hot`() {
        // Target digit 3 at 30%: the contract wins only 70% of the time. Entering must be refused.
        val freqs = frequencies(7.8, 7.8, 7.8, 30.0, 7.8, 7.8, 7.8, 7.8, 7.8, 7.8)
        val observed = DigitAnalysisEngine.observedPercentFor(freqs, 3, AnalysisMetric.DIFFERS_PERCENT)

        assertFalse(
            "a hot target digit must NOT satisfy 'enter above 92%'",
            DigitAnalysisEngine.evaluateCondition(
                observedPercent = observed,
                thresholdPercent = 92.0,
                operator = ComparisonOperator.GREATER_THAN,
                sampleSize = 100,
                minimumSampleSize = 10
            )
        )
    }

    @Test
    fun `the two metrics disagree exactly where it matters`() {
        // The inverse-pair case: a threshold that the old (equals) logic entered on, and that the
        // differs logic correctly refuses. The target digit must sit ABOVE 50% for the two to
        // disagree at all — below that, equals% and differs% give the same verdict.
        val freqs = frequencies(4.0, 4.0, 4.0, 60.0, 4.0, 4.0, 4.0, 4.0, 4.0, 8.0)
        val threshold = 50.0

        assertTrue(
            "equals logic still enters here — the contract wins only 40% of the time",
            DigitAnalysisEngine.evaluateCondition(
                observedPercent = DigitAnalysisEngine.observedPercentFor(freqs, 3, AnalysisMetric.EQUALS_PERCENT),
                thresholdPercent = threshold,
                operator = ComparisonOperator.GREATER_THAN,
                sampleSize = 100
            )
        )
        assertFalse(
            "differs logic must refuse the same window — this is exactly the inverted-entry bug",
            DigitAnalysisEngine.evaluateCondition(
                observedPercent = DigitAnalysisEngine.observedPercentFor(freqs, 3, AnalysisMetric.DIFFERS_PERCENT),
                thresholdPercent = threshold,
                operator = ComparisonOperator.GREATER_THAN,
                sampleSize = 100
            )
        )
    }

    @Test
    fun `a short window never satisfies a threshold regardless of metric`() {
        val freqs = frequencies(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        assertFalse(
            DigitAnalysisEngine.evaluateCondition(
                observedPercent = 100.0,
                thresholdPercent = 92.0,
                operator = ComparisonOperator.GREATER_THAN,
                sampleSize = 9,
                minimumSampleSize = 10
            )
        )
    }
}
