package com.tickedge.data.model

import java.util.Locale

enum class AccountType(val displayName: String) {
    DEMO("Demo Account"),
    LIVE("Live Account")
}

enum class ConnectionStatus(val label: String) {
    CONNECTING("Connecting"),
    LOADING_HISTORY("Loading History"),
    LIVE("Live"),
    RECONNECTING("Reconnecting"),
    STALE("Stale"),
    OFFLINE("Offline"),
    ERROR("Error")
}

enum class TrendDirection {
    UP, DOWN, EQUAL
}

enum class ComparisonOperator(val symbol: String, val label: String) {
    GREATER_THAN(">", "Above (strictly >)"),
    GREATER_OR_EQUAL(">=", "Above or equal (>=)"),
    LESS_THAN("<", "Below (strictly <)"),
    LESS_OR_EQUAL("<=", "Below or equal (<=)")
}

/**
 * Which probability the entry threshold is measured against.
 *
 * The app buys `DIGITDIFF`, which Deriv settles as a win when the last digit of the final tick is
 * NOT the chosen digit. So the probability that actually decides the contract is
 * `P(lastDigit != target)`, not `P(lastDigit == target)`. Measuring the wrong one inverts the
 * entry condition: a high equals-% means the contract is most likely to LOSE.
 *
 * [symbol] is persisted, so it must stay stable across renames.
 */
enum class AnalysisMetric(val symbol: String, val label: String) {
    DIFFERS_PERCENT("differs_pct", "Differs % (≠ digit)"),
    EQUALS_PERCENT("equals_pct", "Equals % (digit)")
}

/**
 * How the Differs target digit is chosen for each opportunity.
 *
 * [RANDOM] picks a digit first and *then* tests it against the threshold — it is not a licence to
 * trade. A randomly chosen digit that does not qualify produces no order, so random mode widens
 * which digits get considered rather than bypassing the entry condition.
 */
enum class DigitSelectionMode(val symbol: String, val label: String) {
    FIXED("fixed", "Fixed digit"),
    RANDOM("random", "Random digit")
}

enum class TradeType(val displayName: String) {
    DIFFERS("Differs (Main)"),
    OVER_UNDER("Over & Under (Recovery)")
}

enum class TradeDurationUnit(val label: String, val shortLabel: String) {
    TICKS("Ticks", "t"),
    SECONDS("Seconds", "s"),
    MINUTES("Minutes", "m")
}

enum class TradeOutcome {
    PENDING, WON, LOST
}

data class MarketDef(
    val symbol: String,
    val displayName: String,
    val shortName: String,
    val pipSize: Int,
    val basePrice: Double
)

object SupportedMarkets {
    val ALL = listOf(
        MarketDef("R_10", "Volatility 10 Index", "Vol 10", 3, 6245.120),
        MarketDef("R_25", "Volatility 25 Index", "Vol 25", 3, 2489.340),
        MarketDef("R_50", "Volatility 50 Index", "Vol 50", 4, 189.5420),
        MarketDef("R_75", "Volatility 75 Index", "Vol 75", 4, 76234.8100),
        MarketDef("R_100", "Volatility 100 Index", "Vol 100", 2, 4521.90)
    )

    fun find(symbol: String): MarketDef =
        ALL.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) } ?: ALL[0]
}

data class TickObservation(
    val epoch: Long,
    val quote: Double,
    val pipSize: Int,
    val formattedQuote: String,
    val lastDigit: Int,
    val priceDiff: Double = 0.0
)

data class DigitFrequency(
    val digit: Int,
    val count: Int,
    val percentage: Double,
    val trend: TrendDirection
)

data class OverUnderStats(
    val digit: Int,
    val overCount: Int,
    val overPercentage: Double,
    val underCount: Int,
    val underPercentage: Double,
    val matchCount: Int,
    val matchPercentage: Double
)

/**
 * Matches/Differs statistics for digits 0 through 9 across the selected tick window.
 *
 * [differsPercentage] is the probability that decides a `DIGITDIFF` contract: it wins when the
 * last digit of the final tick is not [digit]. [matchPercentage] is its exact complement.
 */
data class DiffersStats(
    val digit: Int,
    val matchCount: Int,
    val matchPercentage: Double,
    val differsCount: Int,
    val differsPercentage: Double
)

data class MarketState(
    val symbol: String,
    val displayName: String,
    val shortName: String,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
    val lastQuote: Double? = null,
    val lastFormattedQuote: String = "--",
    val lastEpoch: Long? = null,
    val lastDigit: Int? = null,
    val lastPriceDiff: Double = 0.0,
    val pipSize: Int = 3,
    val ticks: List<TickObservation> = emptyList(),
    val digitFrequencies: List<DigitFrequency> = (0..9).map { DigitFrequency(it, 0, 0.0, TrendDirection.EQUAL) },
    val sampleSize: Int = 0,
    val configuredWindowSize: Int = 100,
    val lastUpdateTimestamp: Long = 0L,
    val lastError: String? = null
)

data class TradeSettings(
    val targetDigit: Int = 2,
    val recoveryDigit: Int = 4,
    val recoveryDirection: String = "OVER", // "OVER" or "UNDER"
    val comparisonOperator: ComparisonOperator = ComparisonOperator.GREATER_THAN,
    val thresholdPercent: Double = 70.0,
    /** Which probability [thresholdPercent] is compared against. See [AnalysisMetric]. */
    val analysisMetric: AnalysisMetric = AnalysisMetric.DIFFERS_PERCENT,
    /** Whether [targetDigit] is fixed, or redrawn per market opportunity. See [DigitSelectionMode]. */
    val digitSelectionMode: DigitSelectionMode = DigitSelectionMode.FIXED,
    /** How many recent ticks the analysis is computed over (1..1000). */
    val analysisWindowSize: Int = 100,
    val stakeAmount: Double = 0.35,
    val takeProfit: Double = 10.0,
    val stopLoss: Double = 10.0,
    val durationUnit: TradeDurationUnit = TradeDurationUnit.TICKS,
    val durationValue: Int = 1,
    val martingaleEnabled: Boolean = true
)

data class MartingaleRiskStep(
    val step: Int,
    val contractType: String,
    val stake: Double,
    val cumulativeRisk: Double,
    val potentialGrossReturn: Double,
    val netProfitOnWin: Double,
    val winProbabilityEstimate: Double
)

data class SystemConfig(
    val randomMarkets: Boolean = true,
    val maxTradesPerMarket: Int = 3,
    val maxDailyLoss: Double = 20.0,
    val stopTradingOnLimit: Boolean = true
)

data class AnalysisEvent(
    val id: Long = 0,
    val userId: String = "user_default",
    val marketSymbol: String,
    val targetDigit: Int,
    val windowSize: Int,
    val sampleSize: Int,
    val occurrenceCount: Int,
    val observedPercent: Double,
    val comparisonOperator: ComparisonOperator,
    val thresholdPercent: Double,
    val conditionMet: Boolean,
    val eventTimestamp: Long
)

data class SimulatedTrade(
    val id: String,
    val marketSymbol: String,
    val marketName: String,
    val tradeType: TradeType,
    val targetDigit: Int,
    val stake: Double,
    val payout: Double,
    val netResult: Double,
    val outcome: TradeOutcome,
    val entryQuote: Double,
    val exitQuote: Double,
    val exitDigit: Int,
    val timestamp: Long,
    val durationText: String = "5 ticks",
    val accountId: String = "",
    val contractId: String = "",
    val currency: String = "USD",
    val isReal: Boolean = false,
    val note: String = ""
)
