package com.tickedge.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local profile per user. Balances are NOT seeded here: live balances come from the real
 * Deriv accounts (see DerivClient). Fields default to neutral/zero.
 *
 * @param id Stable user identity (e.g. Deriv OAuth `open_id` or `sub` claim)
 */
@Entity(tableName = "profiles")
data class UserProfileEntity(
    @PrimaryKey
    val id: String,
    val displayName: String = "",
    val email: String = "",
    val simulatedBalance: Double = 0.0,
    val liveBalance: Double = 0.0,
    val demoBalance: Double = 0.0,
    val activeAccount: String = "DEMO",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Per-user settings. The composite primary key (userId, id) ensures each user has exactly one
 * settings row while allowing the legacy singleton pattern to coexist during migration.
 *
 * @param id Always 1L — maintained for legacy compatibility during migration
 * @param userId Stable user identity matching profiles.id
 */
@Entity(
    tableName = "user_settings",
    primaryKeys = ["userId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = UserProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("userId")]
)
data class UserSettingsEntity(
    val id: Long = 1L,
    val userId: String,
    val windowSize: Int = 100,
    val selectedDigit: Int = 2,
    val recoveryDigit: Int = 4,
    val recoveryDirection: String = "OVER",
    val comparisonOperator: String = ">",
    val thresholdPercent: Double = 70.0,
    /** Which probability `thresholdPercent` is compared against; see `AnalysisMetric`. */
    val analysisMetric: String = "differs_pct",
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

/**
 * Record of when a digit analysis condition was met for a market.
 *
 * @param id Auto-generated; not used for querying
 * @param userId Stable user identity
 */
@Entity(
    tableName = "analysis_events",
    foreignKeys = [
        ForeignKey(
            entity = UserProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("userId")]
)
data class AnalysisEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: String,
    val marketSymbol: String,
    val targetDigit: Int,
    val windowSize: Int,
    val sampleSize: Int,
    val occurrenceCount: Int,
    val observedPercent: Double,
    /**
     * Which metric `observedPercent` holds. Without this, rows recorded before the differs fix
     * would silently change meaning: the same column stores equals-% in old rows and differs-%
     * in new ones, and nothing on the row says which.
     */
    val analysisMetric: String = "differs_pct",
    val comparisonOperator: String,
    val thresholdPercent: Double,
    val conditionMet: Boolean,
    val eventTimestamp: Long = System.currentTimeMillis()
)

/**
 * A trading session. Sessions are isolated per user.
 *
 * @param id Auto-generated
 * @param userId Stable user identity
 */
@Entity(
    tableName = "simulation_sessions",
    foreignKeys = [
        ForeignKey(
            entity = UserProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("userId")]
)
data class SimulationSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: String,
    val initialSimulatedBalance: Double = 0.0,
    val currentSimulatedBalance: Double = 0.0,
    val status: String = "ACTIVE",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val totalTrades: Int = 0,
    val winCount: Int = 0,
    val lossCount: Int = 0
)

/**
 * A settled or pending trade position. Isolated per user and per account.
 *
 * @param id Auto-generated
 * @param userId Stable user identity
 * @param accountId Deriv account id this trade was placed on
 */
@Entity(
    tableName = "simulation_positions",
    foreignKeys = [
        ForeignKey(
            entity = UserProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("userId"),
        Index("accountId"),
        Index("userId", "accountId")
    ]
)
data class SimulationPositionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: String,
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
