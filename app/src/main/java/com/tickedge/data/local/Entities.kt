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
    /**
     * The Over/Under recovery condition, held apart from the Differs one above.
     *
     * Separate columns rather than a shared one because the two are measured on different scales —
     * P(≠ digit) runs near 90%, P(over barrier) near 50% — so a single threshold could only be
     * meaningful for one of them. See [TradeSettings.recoveryThresholdPercent].
     */
    val recoveryComparisonOperator: String = ">",
    val recoveryThresholdPercent: Double = 55.0,
    /** Which probability `thresholdPercent` is compared against; see `AnalysisMetric`. */
    val analysisMetric: String = "differs_pct",
    /** Whether the Differs target digit is fixed or redrawn per opportunity. */
    val digitSelectionMode: String = "fixed",
    val minimumSampleSize: Int = 10,
    val stakeAmount: Double = 0.35,
    val takeProfit: Double = 10.0,
    val stopLoss: Double = 10.0,
    val durationUnit: String = "Ticks",
    val durationValue: Int = 1,
    val martingaleEnabled: Boolean = true,
    val tradeType: String = "Differs",
    val randomMarkets: Boolean = true,
    val maxTradesPerMarket: Int = 3,
    val maxDailyLoss: Double = 20.0,
    val stopTradingOnLimit: Boolean = true,
    val realArmed: Boolean = false,
    val requirePerOrderConfirm: Boolean = false,
    val maxMartingaleSteps: Int = 3,
    /**
     * Which strategy the user last ran.
     *
     * Stored so the app opens on the strategy they chose instead of asking again every launch.
     * A strategy id that no longer exists falls back to the default rather than stranding them.
     */
    val selectedStrategyId: String = "differs_with_recovery",
    /**
     * Whether the user accepted the one-time live-account warning.
     *
     * Persisted rather than held in memory: the acknowledgement is a property of the person's
     * settings, not of the process, and a restart that silently re-armed live trading — or one
     * that re-asked on every launch — was the wrong behaviour on both sides of the switch.
     */
    val liveRiskAcknowledged: Boolean = false,
    /**
     * The Even/Odd strategy's entry class. Persisted as [EvenOddMode.symbol] so it survives a
     * rename of the enum's label without stranding the value on upgrade.
     */
    val evenOddMode: String = "auto",
    /** The Even/Odd entry operator, stored as [ComparisonOperator.symbol] like its Differs twin. */
    val evenOddOperator: String = ">",
    /** The Even/Odd entry threshold, in percentage points of the observed parity class. */
    val evenOddThreshold: Double = 55.0,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Per-strategy trade settings: one complete settings blob per user per strategy.
 *
 * The Differs and Even/Odd strategies used to share a single [UserSettingsEntity] row, so a
 * change to one strategy's stake or threshold silently re-tuned the other. Splitting the blobs
 * means each strategy carries its own full set of knobs — including stake, duration and stop
 * levels, which the user has to be able to isolate per strategy.
 *
 * This is purely additive: the legacy [UserSettingsEntity] row stays the store for globally
 * shared settings (selected strategy, risk flags, system config) and its now-stale trade
 * columns are simply no longer written. The 15→16 migration seeds one row per strategy from
 * the legacy values.
 */
@Entity(
    tableName = "strategy_settings",
    primaryKeys = ["userId", "strategyId"],
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
data class StrategySettingsEntity(
    val userId: String,
    /** A [com.tickedge.domain.StrategyCatalog] id, e.g. "differs_with_recovery" or "even_odd". */
    val strategyId: String,
    // ── Differs entry & recovery ─────────────────────────────────────────────
    val differsDigit: Int = 2,
    val differsDigitMode: String = "random",
    val differsOperator: String = "<",
    val differsThreshold: Double = 85.0,
    val recoveryDirection: String = "OVER",
    val recoveryDigit: Int = 2,
    val recoveryOperator: String = ">",
    val recoveryThreshold: Double = 80.0,
    // ── Even/Odd entry ────────────────────────────────────────────────────────
    val evenOddMode: String = "auto",
    val evenOddOperator: String = ">",
    val evenOddThreshold: Double = 55.0,
    // ── This strategy's own trade knobs ───────────────────────────────────────
    val stakeAmount: Double = 0.35,
    val takeProfit: Double = 10.0,
    val stopLoss: Double = 10.0,
    val durationUnit: String = "Ticks",
    val durationValue: Int = 1,
    val martingaleEnabled: Boolean = true,
    val analysisWindow: Int = 100,
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
    /**
     * Deriv account this session ran on. Sessions are scoped per account as well as per user, so
     * switching demo/real mid-run can never blend two accounts' results into one total.
     */
    val accountId: String = "",
    val isReal: Boolean = false,
    /**
     * Strategy label recorded at start, e.g. "Differs" — shown on the session results.
     *
     * Display name, for the results card. The machine-readable id lives in [strategyId] so
     * queries never have to match on a label that a rename could break.
     */
    val strategy: String = "Differs",
    /** [com.tickedge.domain.StrategyCatalog] id this session was started under; "" on legacy rows. */
    val strategyId: String = "",
    /** Position of the selected digit at session start, for reference in the results. */
    val targetDigit: Int = 2,
    /**
     * Account balance when the session opened, and when it closed.
     *
     * Read from the live Deriv account rather than assumed to be the starting stake or a running
     * arithmetic total. They were never written at all, which is why the session results card
     * showed $0.00 for both on every run.
     */
    val initialSimulatedBalance: Double = 0.0,
    val currentSimulatedBalance: Double = 0.0,
    val status: String = "ACTIVE",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val totalTrades: Int = 0,
    val winCount: Int = 0,
    val lossCount: Int = 0,
    /**
     * Realised profit for the session: wins minus losses, over settled trades only.
     *
     * This is the figure the user actually judges the run by, and it is derived by SQL from the
     * session's positions rather than accumulated as trades arrive — a running counter in the
     * engine drifts the moment a trade settles while the app is backgrounded, or is lost outright
     * if the process dies mid-run.
     */
    val netProfit: Double = 0.0,
    /** Sum of the winning trades' net results. Paired with [grossLoss] to show the shape of a run. */
    val grossProfit: Double = 0.0,
    /** Sum of the losing trades' net results as a positive magnitude, so it reads as a cost. */
    val grossLoss: Double = 0.0,
    /** Total staked across the session — the money that was actually at risk, not just the P&L. */
    val totalStaked: Double = 0.0
)

/**
 * Aggregated results for one session, computed from confirmed positions.
 *
 * Deliberately derived by SQL from `simulation_positions` rather than denormalised onto the session
 * row: a cached total can drift from the trades that justify it, and these numbers decide whether
 * the user stops or escalates.
 *
 * The most recent confirmed trade comes from its own query ([LastTradeRow]) rather than from here,
 * because it needs the row's market name and outcome alongside the amount.
 */
data class SessionSummary(
    val sessionId: Long = 0,
    val completedTrades: Int = 0,
    val winCount: Int = 0,
    val lossCount: Int = 0,
    val totalRealizedPnl: Double = 0.0,
    val totalStaked: Double = 0.0,
    /** Sum of winning trades' net results. */
    val grossProfit: Double = 0.0,
    /** Sum of losing trades' net results as a positive magnitude. */
    val grossLoss: Double = 0.0
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
        Index("sessionId"),
        Index("userId", "accountId"),
        Index("userId", "sessionId")
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
    val isReal: Boolean = false,
    /**
     * Whether this trade has reached FIDScript.
     *
     * The outbox flag for cross-device sync. It defaults to false so every existing row — and
     * every trade taken while the app was signed out — is picked up by the next upload rather than
     * assumed to be on the cloud already.
     */
    val syncedToCloud: Boolean = false,
    /**
     * What Deriv quoted as the ask price at the moment of purchase, i.e. what was actually paid.
     *
     * Stored rather than assumed equal to the stake: on Differs and Over/Under the price the venue
     * charges is the live quote, and it is what the profit identity is built on
     * (`payout - askPrice`). Without it a restored trade can show a net figure with no way to say
     * where it came from.
     */
    val askPrice: Double = 0.0,
    /**
     * Return rate: profit returned per unit staked on a win, from the live quote.
     *
     * Null means never recorded. A trade synced from an older build, or one taken before the live
     * quote was wired in, has no rate — and that is a fact about the record, so the detail screen
     * shows "Unavailable" rather than deriving one from numbers that were never captured.
     */
    val payoutRatio: Double? = null,
    /**
     * Which rung of the recovery ladder placed this trade, 0 for an ordinary entry.
     *
     * A **snapshot**, taken at placement. Recomputing it later would describe this trade in terms
     * of whatever the ladder looks like now, which is a different number entirely by the time
     * anyone reads the history.
     */
    val recoveryStep: Int = 0,
    /**
     * Loss already outstanding when this trade was placed — what the rung was trying to clear.
     *
     * Also a snapshot, for the same reason as [recoveryStep]. It is what turns a lone +$0.37 into
     * "this was rung 3, covering $1.25", which is the whole point of showing recovery context next
     * to the trade result.
     */
    val outstandingBefore: Double = 0.0,
    /**
     * [com.tickedge.domain.StrategyCatalog] id of the strategy that placed this trade.
     *
     * Recorded by the engine that made the trade rather than joined through the session, so a
     * trade is filterable by strategy on its own — the session only groups them, it is not what
     * says which strategy a trade came from. "" on rows written before the 15→16 migration,
     * which backfilled them from [scenarioType].
     */
    val strategyId: String = ""
)
