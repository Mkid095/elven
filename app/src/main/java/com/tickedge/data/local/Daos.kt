package com.tickedge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM profiles WHERE id = :userId LIMIT 1")
    fun getProfile(userId: String): Flow<UserProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateProfile(profile: UserProfileEntity)

    @Query("UPDATE profiles SET simulatedBalance = :newBalance WHERE id = :userId")
    suspend fun updateBalance(userId: String, newBalance: Double)

    @Query("UPDATE profiles SET activeAccount = :accountType WHERE id = :userId")
    suspend fun updateActiveAccount(userId: String, accountType: String)

    /** Delete all profiles. Used when clearing all user data. */
    @Query("DELETE FROM profiles")
    suspend fun deleteAll()
}

@Dao
interface UserSettingsDao {
    /**
     * Get the settings row for a specific user.
     * Returns null if no settings exist for this user yet — caller should use defaults.
     */
    @Query("SELECT * FROM user_settings WHERE userId = :userId AND id = 1 LIMIT 1")
    fun getSettings(userId: String): Flow<UserSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: UserSettingsEntity)

    /** Delete all settings for a user. */
    @Query("DELETE FROM user_settings WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)
}

@Dao
interface AnalysisEventDao {
    /**
     * All analysis events for a user, newest first.
     * @param userId Stable user identity
     * @param limit Maximum events to return (default 100)
     */
    @Query("SELECT * FROM analysis_events WHERE userId = :userId ORDER BY eventTimestamp DESC LIMIT :limit")
    fun getEventsForUser(userId: String, limit: Int = 100): Flow<List<AnalysisEventEntity>>

    /** All analysis events regardless of user (for admin/debug). */
    @Query("SELECT * FROM analysis_events ORDER BY eventTimestamp DESC LIMIT 100")
    fun getAllEvents(): Flow<List<AnalysisEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: AnalysisEventEntity): Long

    /** Delete all events for a user. */
    @Query("DELETE FROM analysis_events WHERE userId = :userId")
    suspend fun clearEventsForUser(userId: String)

    /** Delete all events regardless of user. */
    @Query("DELETE FROM analysis_events")
    suspend fun clearAllEvents()
}

@Dao
interface SimulationDao {
    /**
     * All settled positions for a user, newest first.
     * @param userId Stable user identity
     * @param limit Maximum positions to return (default 200)
     */
    @Query("SELECT * FROM simulation_positions WHERE userId = :userId ORDER BY settledAt DESC LIMIT :limit")
    fun getPositionsForUser(userId: String, limit: Int = 200): Flow<List<SimulationPositionEntity>>

    /** All positions regardless of user (for admin/debug). */
    @Query("SELECT * FROM simulation_positions ORDER BY settledAt DESC LIMIT 200")
    fun getAllPositions(): Flow<List<SimulationPositionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPosition(position: SimulationPositionEntity): Long

    /** Latest session for a user. */
    @Query("SELECT * FROM simulation_sessions WHERE userId = :userId ORDER BY startedAt DESC LIMIT 1")
    fun getLatestSession(userId: String): Flow<SimulationSessionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SimulationSessionEntity): Long

    @Update
    suspend fun updateSession(session: SimulationSessionEntity)

    /** Currently running session for a user, if any. */
    @Query("SELECT * FROM simulation_sessions WHERE userId = :userId AND status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    fun getActiveSession(userId: String): Flow<SimulationSessionEntity?>

    /** Every session for a user, newest first. */
    @Query("SELECT * FROM simulation_sessions WHERE userId = :userId ORDER BY startedAt DESC")
    fun getSessionsForUser(userId: String): Flow<List<SimulationSessionEntity>>

    /** Close a session. */
    @Query("UPDATE simulation_sessions SET status = :status, endedAt = :endedAt, totalTrades = :totalTrades, winCount = :winCount, lossCount = :lossCount WHERE id = :sessionId")
    suspend fun closeSession(sessionId: Long, status: String, endedAt: Long, totalTrades: Int, winCount: Int, lossCount: Int)

    /** Positions belonging to one session, newest first. */
    @Query("SELECT * FROM simulation_positions WHERE userId = :userId AND sessionId = :sessionId ORDER BY settledAt DESC")
    fun getPositionsForSession(userId: String, sessionId: Long): Flow<List<SimulationPositionEntity>>

    /**
     * Session totals, computed from confirmed outcomes only.
     *
     * `outcome IN ('WON','LOST')` excludes anything still PENDING, so an unsettled contract never
     * inflates the trade count or contributes a 0.0 to the P&L. Totals are derived from the
     * positions rather than cached on the session row, so they cannot drift from the trades that
     * justify them.
     */
    @Query(
        """
        SELECT
            :sessionId AS sessionId,
            COUNT(*) AS completedTrades,
            COALESCE(SUM(CASE WHEN outcome = 'WON' THEN 1 ELSE 0 END), 0) AS winCount,
            COALESCE(SUM(CASE WHEN outcome = 'LOST' THEN 1 ELSE 0 END), 0) AS lossCount,
            COALESCE(SUM(simulatedNetResult), 0.0) AS totalRealizedPnl,
            COALESCE(SUM(simulatedStake), 0.0) AS totalStaked
        FROM simulation_positions
        WHERE userId = :userId AND sessionId = :sessionId AND outcome IN ('WON','LOST')
        """
    )
    fun getSessionSummary(userId: String, sessionId: Long): Flow<SessionSummary>

    /**
     * The single most recent confirmed trade in a session.
     *
     * Uses `settledAt DESC` rather than row id because trades settle out of order relative to when
     * they were placed.
     */
    @Query(
        """
        SELECT simulatedNetResult, settledAt, marketName, outcome
        FROM simulation_positions
        WHERE userId = :userId AND sessionId = :sessionId AND outcome IN ('WON','LOST')
        ORDER BY settledAt DESC LIMIT 1
        """
    )
    fun getLastConfirmedTrade(userId: String, sessionId: Long): Flow<LastTradeRow?>

    /** Delete all positions for a user. */
    @Query("DELETE FROM simulation_positions WHERE userId = :userId")
    suspend fun clearPositionsForUser(userId: String)

    /** Delete all positions regardless of user. */
    @Query("DELETE FROM simulation_positions")
    suspend fun clearAllPositions()

    /** Delete all sessions for a user. */
    @Query("DELETE FROM simulation_sessions WHERE userId = :userId")
    suspend fun clearSessionsForUser(userId: String)
}

/** Flat row backing [SimulationDao.getLastConfirmedTrade]. */
data class LastTradeRow(
    val simulatedNetResult: Double,
    val settledAt: Long,
    val marketName: String,
    val outcome: String
)
