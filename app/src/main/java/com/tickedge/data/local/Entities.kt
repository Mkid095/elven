package com.tickedge.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local profile + settings. Balances are NOT seeded here: live balances come from the real
 * Deriv accounts (see DerivClient). Fields default to neutral/zero.
 */
@Entity(tableName = "profiles")
data class UserProfileEntity(
    @PrimaryKey val id: String = "default_trader",
    val displayName: String = "",
    val email: String = "",
    val simulatedBalance: Double = 0.0,
    val liveBalance: Double = 0.0,
    val demoBalance: Double = 0.0,
    val activeAccount: String = "DEMO",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Long = 1L,
    val userId: String = "default_trader",
    val windowSize: Int = 30,
    val selectedDigit: Int = 2,
    val recoveryDigit: Int = 4,
    val recoveryDirection: String = "OVER",
    val comparisonOperator: String = ">",
    val thresholdPercent: Double = 70.0,
    val minimumSampleSize: Int = 10,
    val stakeAmount: Double = 1.00,
    val takeProfit: Double = 0.50,
    val stopLoss: Double = 5.00,
    val durationUnit: String = "Ticks",
    val durationValue: Int = 5,
    val martingaleEnabled: Boolean = true,
    val tradeType: String = "Differs",
    val randomMarkets: Boolean = true,
    val maxTradesPerMarket: Int = 3,
    val maxDailyLoss: Double = 20.0,
    val stopTradingOnLimit: Boolean = true,
    val realArmed: Boolean = false,
    val requirePerOrderConfirm: Boolean = true,
    val maxMartingaleSteps: Int = 3,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "analysis_events")
data class AnalysisEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "default_trader",
    val marketSymbol: String,
    val targetDigit: Int,
    val windowSize: Int,
    val sampleSize: Int,
    val occurrenceCount: Int,
    val observedPercent: Double,
    val comparisonOperator: String,
    val thresholdPercent: Double,
    val conditionMet: Boolean,
    val eventTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "simulation_sessions")
data class SimulationSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "default_trader",
    val initialSimulatedBalance: Double = 0.0,
    val currentSimulatedBalance: Double = 0.0,
    val status: String = "ACTIVE",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val totalTrades: Int = 0,
    val winCount: Int = 0,
    val lossCount: Int = 0
)

@Entity(tableName = "simulation_positions")
data class SimulationPositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long = 1L,
    val marketSymbol: String,
    val marketName: String,
    val scenarioType: String,
    val simulatedStake: Double,
    val simulatedPayout: Double,
    val simulatedNetResult: Double,
    val outcome: String, // "WON", "LOST", "PENDING"
    val targetDigit: Int,
    val entryQuote: Double,
    val exitQuote: Double,
    val exitDigit: Int,
    val openedAt: Long,
    val settledAt: Long,
    val accountId: String = "",
    val contractId: String = "",
    val isReal: Boolean = false
)
