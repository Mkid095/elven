package com.tickedge.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UserProfileEntity::class,
        UserSettingsEntity::class,
        AnalysisEventEntity::class,
        SimulationSessionEntity::class,
        SimulationPositionEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
    abstract fun userSettingsDao(): UserSettingsDao
    abstract fun analysisEventDao(): AnalysisEventDao
    abstract fun simulationDao(): SimulationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tickedge_database"
                )
                    .addMigrations(MIGRATION_4_TO_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Migration from schema v4 (single-user, destructive) → v5 (multi-user, userId scoping).
         *
         * ## Legacy data handling strategy
         *
         * All existing records (which were created by the single-user "default_trader" session)
         * are attributed to the legacy user id `"default_trader"`. This preserves all historical
         * data while establishing the user-scoped model. The first authenticated user with a
         * real Deriv identity will have their own separate records.
         *
         * Steps:
         * 1. Create `profiles` table with new schema (id is now the stable user key).
         *    Insert a row for `"default_trader"` with current profile defaults.
         * 2. Add `userId` column to `user_settings` (NOT NULL, default "default_trader").
         *    The legacy row (id=1) becomes userId="default_trader".
         * 3. Add `userId` column to `analysis_events` (NOT NULL, default "default_trader").
         * 4. Add `userId` column to `simulation_sessions` (NOT NULL, default "default_trader").
         * 5. Add `userId` column to `simulation_positions` (NOT NULL, default "default_trader").
         * 6. Create indexes on `userId` columns.
         * 7. Add foreign keys referencing `profiles(id)`.
         *
         * Note: We recreate tables rather than use raw ALTER TABLE because Room requires
         * the full table definition to match the entity for verification.
         */
        private val MIGRATION_4_TO_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val legacyUser = "default_trader"

                // 1. Create new profiles table and insert legacy profile
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `profiles` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `displayName` TEXT NOT NULL DEFAULT '',
                        `email` TEXT NOT NULL DEFAULT '',
                        `simulatedBalance` REAL NOT NULL DEFAULT 0.0,
                        `liveBalance` REAL NOT NULL DEFAULT 0.0,
                        `demoBalance` REAL NOT NULL DEFAULT 0.0,
                        `activeAccount` TEXT NOT NULL DEFAULT 'DEMO',
                        `createdAt` INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO `profiles` (`id`, `displayName`, `email`, `simulatedBalance`,
                        `liveBalance`, `demoBalance`, `activeAccount`, `createdAt`)
                    VALUES ('$legacyUser', '', '', 0.0, 0.0, 0.0, 'DEMO', ${System.currentTimeMillis()})
                """)

                // 2. Create new user_settings table with composite PK (userId, id) + insert legacy row
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `user_settings` (
                        `id` INTEGER NOT NULL DEFAULT 1,
                        `userId` TEXT NOT NULL DEFAULT '$legacyUser',
                        `windowSize` INTEGER NOT NULL DEFAULT 30,
                        `selectedDigit` INTEGER NOT NULL DEFAULT 2,
                        `recoveryDigit` INTEGER NOT NULL DEFAULT 4,
                        `recoveryDirection` TEXT NOT NULL DEFAULT 'OVER',
                        `comparisonOperator` TEXT NOT NULL DEFAULT '>',
                        `thresholdPercent` REAL NOT NULL DEFAULT 70.0,
                        `minimumSampleSize` INTEGER NOT NULL DEFAULT 10,
                        `stakeAmount` REAL NOT NULL DEFAULT 1.0,
                        `takeProfit` REAL NOT NULL DEFAULT 0.5,
                        `stopLoss` REAL NOT NULL DEFAULT 5.0,
                        `durationUnit` TEXT NOT NULL DEFAULT 'Ticks',
                        `durationValue` INTEGER NOT NULL DEFAULT 5,
                        `martingaleEnabled` INTEGER NOT NULL DEFAULT 1,
                        `tradeType` TEXT NOT NULL DEFAULT 'Differs',
                        `randomMarkets` INTEGER NOT NULL DEFAULT 1,
                        `maxTradesPerMarket` INTEGER NOT NULL DEFAULT 3,
                        `maxDailyLoss` REAL NOT NULL DEFAULT 20.0,
                        `stopTradingOnLimit` INTEGER NOT NULL DEFAULT 1,
                        `realArmed` INTEGER NOT NULL DEFAULT 0,
                        `requirePerOrderConfirm` INTEGER NOT NULL DEFAULT 1,
                        `maxMartingaleSteps` INTEGER NOT NULL DEFAULT 3,
                        `updatedAt` INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()},
                        PRIMARY KEY (`userId`, `id`)
                    )
                """.trimIndent())
                // Copy existing settings into new schema with legacy userId
                db.execSQL("""
                    INSERT OR IGNORE INTO `user_settings`
                        (`id`, `userId`, `windowSize`, `selectedDigit`, `recoveryDigit`,
                         `recoveryDirection`, `comparisonOperator`, `thresholdPercent`,
                         `minimumSampleSize`, `stakeAmount`, `takeProfit`, `stopLoss`,
                         `durationUnit`, `durationValue`, `martingaleEnabled`, `tradeType`,
                         `randomMarkets`, `maxTradesPerMarket`, `maxDailyLoss`,
                         `stopTradingOnLimit`, `realArmed`, `requirePerOrderConfirm`,
                         `maxMartingaleSteps`, `updatedAt`)
                    SELECT `id`, '$legacyUser', `windowSize`, `selectedDigit`, `recoveryDigit`,
                           `recoveryDirection`, `comparisonOperator`, `thresholdPercent`,
                           `minimumSampleSize`, `stakeAmount`, `takeProfit`, `stopLoss`,
                           `durationUnit`, `durationValue`, `martingaleEnabled`, `tradeType`,
                           `randomMarkets`, `maxTradesPerMarket`, `maxDailyLoss`,
                           `stopTradingOnLimit`, `realArmed`, `requirePerOrderConfirm`,
                           `maxMartingaleSteps`, `updatedAt`
                    FROM `user_settings`
                    WHERE `id` = 1
                """)

                // 3. Create new analysis_events table + copy data
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `analysis_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userId` TEXT NOT NULL DEFAULT '$legacyUser',
                        `marketSymbol` TEXT NOT NULL,
                        `targetDigit` INTEGER NOT NULL,
                        `windowSize` INTEGER NOT NULL,
                        `sampleSize` INTEGER NOT NULL,
                        `occurrenceCount` INTEGER NOT NULL,
                        `observedPercent` REAL NOT NULL,
                        `comparisonOperator` TEXT NOT NULL,
                        `thresholdPercent` REAL NOT NULL,
                        `conditionMet` INTEGER NOT NULL,
                        `eventTimestamp` INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()},
                        FOREIGN KEY (`userId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_analysis_events_userId` ON `analysis_events` (`userId`)")
                db.execSQL("""
                    INSERT OR IGNORE INTO `analysis_events`
                        (`userId`, `marketSymbol`, `targetDigit`, `windowSize`, `sampleSize`,
                         `occurrenceCount`, `observedPercent`, `comparisonOperator`,
                         `thresholdPercent`, `conditionMet`, `eventTimestamp`)
                    SELECT '$legacyUser', `marketSymbol`, `targetDigit`, `windowSize`, `sampleSize`,
                           `occurrenceCount`, `observedPercent`, `comparisonOperator`,
                           `thresholdPercent`, `conditionMet`, `eventTimestamp`
                    FROM `analysis_events`
                """)

                // 4. Create new simulation_sessions table + copy data
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `simulation_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userId` TEXT NOT NULL DEFAULT '$legacyUser',
                        `initialSimulatedBalance` REAL NOT NULL DEFAULT 0.0,
                        `currentSimulatedBalance` REAL NOT NULL DEFAULT 0.0,
                        `status` TEXT NOT NULL DEFAULT 'ACTIVE',
                        `startedAt` INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()},
                        `endedAt` INTEGER,
                        `totalTrades` INTEGER NOT NULL DEFAULT 0,
                        `winCount` INTEGER NOT NULL DEFAULT 0,
                        `lossCount` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (`userId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_simulation_sessions_userId` ON `simulation_sessions` (`userId`)")
                db.execSQL("""
                    INSERT OR IGNORE INTO `simulation_sessions`
                        (`userId`, `initialSimulatedBalance`, `currentSimulatedBalance`, `status`,
                         `startedAt`, `endedAt`, `totalTrades`, `winCount`, `lossCount`)
                    SELECT '$legacyUser', `initialSimulatedBalance`, `currentSimulatedBalance`, `status`,
                           `startedAt`, `endedAt`, `totalTrades`, `winCount`, `lossCount`
                    FROM `simulation_sessions`
                """)

                // 5. Create new simulation_positions table + copy data
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `simulation_positions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userId` TEXT NOT NULL DEFAULT '$legacyUser',
                        `sessionId` INTEGER NOT NULL DEFAULT 1,
                        `marketSymbol` TEXT NOT NULL,
                        `marketName` TEXT NOT NULL,
                        `scenarioType` TEXT NOT NULL,
                        `simulatedStake` REAL NOT NULL DEFAULT 0.0,
                        `simulatedPayout` REAL NOT NULL DEFAULT 0.0,
                        `simulatedNetResult` REAL NOT NULL DEFAULT 0.0,
                        `outcome` TEXT NOT NULL,
                        `targetDigit` INTEGER NOT NULL,
                        `entryQuote` REAL NOT NULL DEFAULT 0.0,
                        `exitQuote` REAL NOT NULL DEFAULT 0.0,
                        `exitDigit` INTEGER NOT NULL DEFAULT -1,
                        `openedAt` INTEGER NOT NULL,
                        `settledAt` INTEGER NOT NULL,
                        `accountId` TEXT NOT NULL DEFAULT '',
                        `contractId` TEXT NOT NULL DEFAULT '',
                        `isReal` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (`userId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_simulation_positions_userId` ON `simulation_positions` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_simulation_positions_accountId` ON `simulation_positions` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_simulation_positions_userId_accountId` ON `simulation_positions` (`userId`, `accountId`)")
                db.execSQL("""
                    INSERT OR IGNORE INTO `simulation_positions`
                        (`userId`, `sessionId`, `marketSymbol`, `marketName`, `scenarioType`,
                         `simulatedStake`, `simulatedPayout`, `simulatedNetResult`, `outcome`,
                         `targetDigit`, `entryQuote`, `exitQuote`, `exitDigit`,
                         `openedAt`, `settledAt`, `accountId`, `contractId`, `isReal`)
                    SELECT '$legacyUser', `sessionId`, `marketSymbol`, `marketName`, `scenarioType`,
                           `simulatedStake`, `simulatedPayout`, `simulatedNetResult`, `outcome`,
                           `targetDigit`, `entryQuote`, `exitQuote`, `exitDigit`,
                           `openedAt`, `settledAt`, `accountId`, `contractId`, `isReal`
                    FROM `simulation_positions`
                """)
            }
        }
    }
}
