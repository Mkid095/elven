package com.tickedge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Upsert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM profiles WHERE id = :userId LIMIT 1")
    fun getProfile(userId: String): Flow<UserProfileEntity?>

    /**
     * Ensure this user's profile row exists and carries these values.
     *
     * **`@Upsert`, never `@Insert(onConflict = REPLACE)`.** In SQLite `INSERT OR REPLACE` is not an
     * upsert — it is a `DELETE` of the conflicting row followed by an `INSERT`. Both
     * `simulation_positions` and `simulation_sessions` declare
     * `onDelete = ForeignKey.CASCADE` on `userId` → `profiles.id`, so replacing the profile deleted
     * the row and took **every trade and every session** with it. `ensureProfile()` runs at the top
     * of every `startTrading()`, which is why pressing Start appeared to wipe the history: it did
     * wipe it, at the database level, every single time.
     *
     * The cascade stays in place — it is the correct behaviour for a genuine sign-out, where the
     * user is leaving and their local history should go with them. What was wrong was a routine
     * per-session profile touch deleting the row rather than updating it.
     */
    @Upsert
    suspend fun insertOrUpdateProfile(profile: UserProfileEntity)

    @Query("UPDATE profiles SET simulatedBalance = :newBalance WHERE id = :userId")
    suspend fun updateBalance(userId: String, newBalance: Double)

    @Query("UPDATE profiles SET activeAccount = :accountType WHERE id = :userId")
    suspend fun updateActiveAccount(userId: String, accountType: String)

    /**
     * Sets the trader's display name.
     *
     * `UPDATE` (not an upsert) on purpose: it must never create a row if one does not exist yet,
     * and it must not delete-and-reinsert the way an `INSERT OR REPLACE` would — which, because the
     * profiles row is the foreign-key parent of every trade and session, would take the whole
     * history with it.
     */
    @Query("UPDATE profiles SET displayName = :displayName WHERE id = :userId")
    suspend fun updateDisplayName(userId: String, displayName: String)

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
interface StrategySettingsDao {
    /** Both strategies' blobs for one user, so a missing row can fall back to defaults in memory. */
    @Query("SELECT * FROM strategy_settings WHERE userId = :userId")
    suspend fun getForUser(userId: String): List<StrategySettingsEntity>

    /** One strategy's blob, or null if the user has never saved it on this device. */
    @Query("SELECT * FROM strategy_settings WHERE userId = :userId AND strategyId = :strategyId LIMIT 1")
    suspend fun get(userId: String, strategyId: String): StrategySettingsEntity?

    /**
     * `@Upsert`, never `INSERT OR REPLACE`: the row carries no children, but matching the
     * convention keeps the DAO unambiguously update-or-insert.
     */
    @Upsert
    suspend fun upsert(entity: StrategySettingsEntity)
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

    /** Every position on one account, so a clear can also remove them from the cloud. */
    @Query("SELECT * FROM simulation_positions WHERE userId = :userId AND accountId = :accountId")
    suspend fun getPositionsForUserAndAccount(
        userId: String,
        accountId: String
    ): List<SimulationPositionEntity>

    /** All positions regardless of user (for admin/debug). */
    @Query("SELECT * FROM simulation_positions ORDER BY settledAt DESC LIMIT 200")
    fun getAllPositions(): Flow<List<SimulationPositionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPosition(position: SimulationPositionEntity): Long

    /**
     * Settled trades that have not been uploaded to FIDScript yet, oldest first.
     *
     * This is the outbox the cloud upload drains. Ordered by id rather than settledAt so the drain
     * makes progress in the order trades happened even though they settle out of order.
     */
    @Query("SELECT * FROM simulation_positions WHERE userId = :userId AND syncedToCloud = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun getUnsyncedPositions(userId: String, limit: Int): List<SimulationPositionEntity>

    /** Mark trades as uploaded. Only called once the cloud write has actually succeeded. */
    @Query("UPDATE simulation_positions SET syncedToCloud = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    /** One trade by its Deriv contract id, used to keep a cloud pull from duplicating. */
    @Query("SELECT * FROM simulation_positions WHERE userId = :userId AND contractId = :contractId LIMIT 1")
    suspend fun getPositionByContractId(userId: String, contractId: String): SimulationPositionEntity?

    /** Latest session for a user. */
    @Query("SELECT * FROM simulation_sessions WHERE userId = :userId ORDER BY startedAt DESC LIMIT 1")
    fun getLatestSession(userId: String): Flow<SimulationSessionEntity?>

    /**
     * One session by id.
     *
     * Re-read after closing, so the row published to the UI carries the final `endedAt` and results
     * rather than the values it had when it opened.
     */
    @Query("SELECT * FROM simulation_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): SimulationSessionEntity?

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

    /**
     * Sessions for one account only, newest first.
     *
     * Demo and live results are different records and are never blended: switching to the live
     * account has to show the live account's history and nothing else, including showing nothing
     * when the live account has never traded. Filtering by `isReal` rather than `accountId` because
     * the account id changes with every Deriv login while the demo/live distinction does not.
     */
    @Query("SELECT * FROM simulation_sessions WHERE userId = :userId AND isReal = :isReal ORDER BY startedAt DESC")
    fun getSessionsForAccount(userId: String, isReal: Boolean): Flow<List<SimulationSessionEntity>>

    /** Trades for one account only, newest first. */
    @Query("SELECT * FROM simulation_positions WHERE userId = :userId AND isReal = :isReal ORDER BY settledAt DESC LIMIT :limit")
    fun getPositionsForAccount(userId: String, isReal: Boolean, limit: Int = 200): Flow<List<SimulationPositionEntity>>

    /** The newest session on one account, whether or not it was ever closed. */
    @Query("SELECT * FROM simulation_sessions WHERE userId = :userId AND isReal = :isReal ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatestSessionForAccount(userId: String, isReal: Boolean): SimulationSessionEntity?

    /**
     * Sessions on one account left open by a run that never ended — the app was killed, crashed,
     * or was force-stopped, so the engine's own close never ran.
     */
    @Query("SELECT * FROM simulation_sessions WHERE userId = :userId AND isReal = :isReal AND status = 'ACTIVE' ORDER BY startedAt ASC")
    suspend fun getStaleActiveSessions(userId: String, isReal: Boolean): List<SimulationSessionEntity>

    /**
     * Close a session with its realised results.
     *
     * Every figure is passed in rather than recomputed here, because the caller already has the
     * authoritative [SessionSummary] from SQL over the session's own trades. Writing a cached total
     * that can disagree with the trades justifying it is the failure this avoids.
     */
    @Query(
        """
        UPDATE simulation_sessions
        SET status = :status, endedAt = :endedAt, totalTrades = :totalTrades,
            winCount = :winCount, lossCount = :lossCount,
            netProfit = :netProfit, grossProfit = :grossProfit, grossLoss = :grossLoss,
            totalStaked = :totalStaked, currentSimulatedBalance = :endingBalance
        WHERE id = :sessionId
        """
    )
    suspend fun closeSession(
        sessionId: Long,
        status: String,
        endedAt: Long,
        totalTrades: Int,
        winCount: Int,
        lossCount: Int,
        netProfit: Double,
        grossProfit: Double,
        grossLoss: Double,
        totalStaked: Double,
        endingBalance: Double
    )

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
            COALESCE(SUM(simulatedStake), 0.0) AS totalStaked,
            COALESCE(SUM(CASE WHEN simulatedNetResult > 0 THEN simulatedNetResult ELSE 0 END), 0.0)
                AS grossProfit,
            COALESCE(SUM(CASE WHEN simulatedNetResult < 0 THEN -simulatedNetResult ELSE 0 END), 0.0)
                AS grossLoss
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

    /**
     * Delete positions for one account only.
     *
     * Scoping by account as well as user matters because demo and real results are not
     * interchangeable: a "clear my history" action that silently wiped the real account's record
     * while leaving the demo one would destroy exactly the data the user most needs.
     */
    @Query("DELETE FROM simulation_positions WHERE userId = :userId AND accountId = :accountId")
    suspend fun clearPositionsForUserAndAccount(userId: String, accountId: String)

    /** Delete all positions regardless of user. */
    @Query("DELETE FROM simulation_positions")
    suspend fun clearAllPositions()

    /** Delete all sessions for a user. */
    @Query("DELETE FROM simulation_sessions WHERE userId = :userId")
    suspend fun clearSessionsForUser(userId: String)

    /** How many trades a user has, for deciding whether stranded rows can safely be reclaimed. */
    @Query("SELECT COUNT(*) FROM simulation_positions WHERE userId = :userId")
    suspend fun countPositionsForUser(userId: String): Int

    /** How many sessions a user has. */
    @Query("SELECT COUNT(*) FROM simulation_sessions WHERE userId = :userId")
    suspend fun countSessionsForUser(userId: String): Int

    /**
     * Move a user's rows onto a different user id.
     *
     * Only ever called for rows stranded under the anonymous key while signed out — see
     * `MainViewModel.reclaimOrphanedHistory`. Updating rather than inserting keeps the original
     * row ids, so any cloud-sync bookkeeping keyed on them stays attached to the right trade.
     */
    @Query("UPDATE simulation_positions SET userId = :toUserId WHERE userId = :fromUserId")
    suspend fun reassignPositionUser(fromUserId: String, toUserId: String)

    @Query("UPDATE simulation_sessions SET userId = :toUserId WHERE userId = :fromUserId")
    suspend fun reassignSessionUser(fromUserId: String, toUserId: String)

    /** Both halves of [reassignPositionUser] and [reassignSessionUser] as one call site. */
    suspend fun reassignUser(fromUserId: String, toUserId: String) {
        reassignPositionUser(fromUserId, toUserId)
        reassignSessionUser(fromUserId, toUserId)
    }
}

/** Flat row backing [SimulationDao.getLastConfirmedTrade]. */
data class LastTradeRow(
    val simulatedNetResult: Double,
    val settledAt: Long,
    val marketName: String,
    val outcome: String
)
