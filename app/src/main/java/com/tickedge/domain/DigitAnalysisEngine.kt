package com.tickedge.domain

import com.tickedge.data.model.AnalysisMetric
import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.DigitFrequency
import com.tickedge.data.model.OverUnderStats
import com.tickedge.data.model.TickObservation
import com.tickedge.data.model.TrendDirection
import java.util.Locale

object DigitAnalysisEngine {

    /**
     * Extracts the authoritative last digit of a price quote based on its pip size.
     * Preserves trailing zeros (e.g., 1450.20 with pipSize 2 yields '0').
     */
    fun extractLastDigit(quote: Double, pipSize: Int): Int {
        val formatted = formatQuote(quote, pipSize)
        val lastChar = formatted.lastOrNull { it.isDigit() } ?: '0'
        return lastChar.digitToInt()
    }

    /**
     * Authoritative decimal string representation for display and digit extraction.
     */
    fun formatQuote(quote: Double, pipSize: Int): String {
        val safePip = if (pipSize < 0) 0 else pipSize
        return String.format(Locale.US, "%.${safePip}f", quote)
    }

    /**
     * Creates a TickObservation from raw epoch and quote values.
     */
    fun createTickObservation(
        epoch: Long,
        quote: Double,
        pipSize: Int,
        priceDiff: Double = 0.0
    ): TickObservation {
        val formatted = formatQuote(quote, pipSize)
        val digit = extractLastDigit(quote, pipSize)
        return TickObservation(
            epoch = epoch,
            quote = quote,
            pipSize = pipSize,
            formattedQuote = formatted,
            lastDigit = digit,
            priceDiff = priceDiff
        )
    }

    /**
     * Computes the 10-digit frequency distribution for a given buffer of ticks.
     * Compares against previous frequencies to establish trend direction.
     */
    fun computeFrequencies(
        ticks: List<TickObservation>,
        previousFrequencies: List<DigitFrequency>? = null
    ): List<DigitFrequency> {
        val totalValid = ticks.size
        if (totalValid == 0) {
            return (0..9).map { DigitFrequency(it, 0, 0.0, TrendDirection.EQUAL) }
        }

        val counts = IntArray(10)
        for (tick in ticks) {
            val d = tick.lastDigit
            if (d in 0..9) {
                counts[d]++
            }
        }

        val prevMap = previousFrequencies?.associateBy { it.digit }

        return (0..9).map { digit ->
            val count = counts[digit]
            val pct = (count.toDouble() / totalValid.toDouble()) * 100.0
            val prevPct = prevMap?.get(digit)?.percentage ?: pct
            val trend = when {
                pct > prevPct + 0.01 -> TrendDirection.UP
                pct < prevPct - 0.01 -> TrendDirection.DOWN
                else -> TrendDirection.EQUAL
            }
            DigitFrequency(
                digit = digit,
                count = count,
                percentage = pct,
                trend = trend
            )
        }
    }

    /**
     * Computes Over and Under statistics for digits 0 through 9 across the selected tick window.
     * Over: digits strictly greater than d
     * Under: digits strictly less than d
     * Matches: digits equal to d
     */
    fun computeOverUnderStats(ticks: List<TickObservation>): List<OverUnderStats> {
        val total = ticks.size.coerceAtLeast(1).toDouble()

        return (0..9).map { d ->
            val overCount = ticks.count { it.lastDigit > d }
            val underCount = ticks.count { it.lastDigit < d }
            val matchCount = ticks.count { it.lastDigit == d }

            OverUnderStats(
                digit = d,
                overCount = overCount,
                overPercentage = (overCount / total) * 100.0,
                underCount = underCount,
                underPercentage = (underCount / total) * 100.0,
                matchCount = matchCount,
                matchPercentage = (matchCount / total) * 100.0
            )
        }
    }

    /**
     * Differs percentage for [digit], read from already-computed frequencies.
     *
     * Deliberately does not re-scan the tick window: `TradingEngine` calls this on every poll tick
     * for every subscribed market, where re-counting [MarketState.ticks] would turn a cheap lookup
     * into an O(window) pass per market per second.
     *
     * Returns 100.0 for an unknown digit — no observed ticks equal it, so nothing differs from it
     * either, and the contract is as safe as the data can justify claiming.
     */
    fun differsPercentFor(frequencies: List<DigitFrequency>, digit: Int): Double {
        val freq = frequencies.firstOrNull { it.digit == digit } ?: return 100.0
        return 100.0 - freq.percentage
    }

    /**
     * Resolves the observed percentage for the configured [AnalysisMetric].
     *
     * Single funnel so the trading engine and the analysis-event log can never disagree about what
     * the threshold was compared against.
     */
    fun observedPercentFor(
        frequencies: List<DigitFrequency>,
        targetDigit: Int,
        metric: AnalysisMetric
    ): Double = when (metric) {
        AnalysisMetric.DIFFERS_PERCENT -> differsPercentFor(frequencies, targetDigit)
        AnalysisMetric.EQUALS_PERCENT ->
            frequencies.firstOrNull { it.digit == targetDigit }?.percentage ?: 0.0
    }

    /**
     * Evaluates whether a statistical threshold condition is satisfied.
     * Adheres strictly to comparison semantics.
     */
    fun evaluateCondition(
        observedPercent: Double,
        thresholdPercent: Double,
        operator: ComparisonOperator,
        sampleSize: Int,
        minimumSampleSize: Int = 10
    ): Boolean {
        if (sampleSize < minimumSampleSize) return false
        return when (operator) {
            ComparisonOperator.GREATER_THAN -> observedPercent > thresholdPercent
            ComparisonOperator.GREATER_OR_EQUAL -> observedPercent >= thresholdPercent
            ComparisonOperator.LESS_THAN -> observedPercent < thresholdPercent
            ComparisonOperator.LESS_OR_EQUAL -> observedPercent <= thresholdPercent
        }
    }
}
