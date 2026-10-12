package com.tickedge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    fun getProfile(id: String = "default_trader"): Flow<UserProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateProfile(profile: UserProfileEntity)

    @Query("UPDATE profiles SET simulatedBalance = :newBalance WHERE id = :id")
    suspend fun updateBalance(id: String = "default_trader", newBalance: Double)

    @Query("UPDATE profiles SET activeAccount = :accountType WHERE id = :id")
    suspend fun updateActiveAccount(id: String = "default_trader", accountType: String)
}

@Dao
interface UserSettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 1 LIMIT 1")
    fun getSettings(): Flow<UserSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: UserSettingsEntity)
}

@Dao
interface AnalysisEventDao {
    @Query("SELECT * FROM analysis_events ORDER BY eventTimestamp DESC LIMIT 100")
    fun getAllEvents(): Flow<List<AnalysisEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: AnalysisEventEntity): Long

    @Query("DELETE FROM analysis_events")
    suspend fun clearEvents()
}

@Dao
interface SimulationDao {
    @Query("SELECT * FROM simulation_positions ORDER BY settledAt DESC LIMIT 200")
    fun getAllPositions(): Flow<List<SimulationPositionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPosition(position: SimulationPositionEntity): Long

    @Query("SELECT * FROM simulation_sessions ORDER BY startedAt DESC LIMIT 1")
    fun getLatestSession(): Flow<SimulationSessionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SimulationSessionEntity): Long

    @Update
    suspend fun updateSession(session: SimulationSessionEntity)

    @Query("DELETE FROM simulation_positions")
    suspend fun clearPositions()
}
