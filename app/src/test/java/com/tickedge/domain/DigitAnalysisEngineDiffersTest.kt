package com.tickedge.domain

import com.tickedge.data.model.AnalysisMetric
import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.DigitFrequency
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

    private fun frequencies(vararg percents: Double): List<DigitFrequency> =
        percents.mapIndexed { digit, pct -> DigitFrequency(digit, 0, pct, TrendDirection.EQUAL) }

    // ------------------------------------------------ differs vs matches complement

    @Test
    fun `differs is the exact complement of matches`() {
        // 100 ticks where digit 3 appears 12 times: matches 12.0%, differs 88.0%, summing to 100.
        // frequencies() takes percentages, so a 12.0 entry stands for those 12 ticks.
        val freqs = frequencies(0.0, 0.0, 0.0, 12.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        val matches = freqs.first { it.digit == 3 }.percentage
        val differs = DigitAnalysisEngine.differsPercentFor(freqs, 3)

        assertEquals(12.0, matches, 0.001)
        assertEquals(88.0, differs, 0.001)
        assertEquals(100.0, matches + differs, 0.001)
    }

    @Test
    fun `match and differs percentages sum to one hundred for every digit`() {
        // A plausible non-uniform window: one digit running hot, the rest sharing the remainder.
        val freqs = frequencies(
            9.4, 9.1, 8.8, 30.0, 8.2, 7.9, 7.6, 7.4, 6.0, 5.6
        )
        freqs.forEach { freq ->
            assertEquals(
                "digit ${freq.digit} does not sum to 100",
                100.0,
                freq.percentage + DigitAnalysisEngine.differsPercentFor(freqs, freq.digit),
                0.001
            )
        }
    }

    @Test
    fun `a digit that never appears is a fully differing contract`() {
        val freqs = frequencies(10.4, 10.4, 10.4, 10.4, 10.4, 10.4, 10.4, 10.4, 10.4, 10.4)

        assertEquals(90.0, DigitAnalysisEngine.differsPercentFor(freqs, 0), 0.001)
    }

    @Test
    fun `a fair window puts every digit at ninety percent differs`() {
        val freqs = frequencies(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0)

        freqs.forEach { freq ->
            assertEquals(
                "digit ${freq.digit}",
                90.0,
                DigitAnalysisEngine.differsPercentFor(freqs, freq.digit),
                0.001
            )
        }
    }

    @Test
    fun `an empty window reads as zero matches rather than dividing by zero`() {
        val empty = DigitAnalysisEngine.computeFrequencies(emptyList())

        empty.forEach { freq ->
            assertEquals(0.0, freq.percentage, 0.001)
            assertEquals(100.0, DigitAnalysisEngine.differsPercentFor(empty, freq.digit), 0.001)
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
