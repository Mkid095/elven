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
