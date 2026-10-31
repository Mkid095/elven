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
        SimulationPositionEntity::class,
        StrategySettingsEntity::class
    ],
    version = 16,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
    abstract fun userSettingsDao(): UserSettingsDao
    abstract fun analysisEventDao(): AnalysisEventDao
    abstract fun simulationDao(): SimulationDao
    abstract fun strategySettingsDao(): StrategySettingsDao

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
                    .addMigrations(*ALL_MIGRATIONS.toTypedArray())
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
                        `windowSize` INTEGER NOT NULL DEFAULT 100,
                        `selectedDigit` INTEGER NOT NULL DEFAULT 2,
                        `recoveryDigit` INTEGER NOT NULL DEFAULT 4,
                        `recoveryDirection` TEXT NOT NULL DEFAULT 'OVER',
                        `comparisonOperator` TEXT NOT NULL DEFAULT '>',
                        `thresholdPercent` REAL NOT NULL DEFAULT 70.0,
                        `minimumSampleSize` INTEGER NOT NULL DEFAULT 10,
                        `stakeAmount` REAL NOT NULL DEFAULT 0.35,
                        `takeProfit` REAL NOT NULL DEFAULT 10.0,
                        `stopLoss` REAL NOT NULL DEFAULT 10.0,
                        `durationUnit` TEXT NOT NULL DEFAULT 'Ticks',
                        `durationValue` INTEGER NOT NULL DEFAULT 1,
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

        /**
         * v5 -> v6: add the analysis-metric column to `user_settings` and `analysis_events`.
         *
         * ## Why this one renames first
         *
         * [MIGRATION_4_TO_5] relies on `CREATE TABLE IF NOT EXISTS` plus
         * `INSERT INTO t SELECT ... FROM t`. That only works on a *fresh* install where the table
         * does not yet exist. On a real upgrade the table is already there, so the CREATE is a
         * no-op and the INSERT selects from the very table it writes into — leaving the old schema
         * in place and failing Room's validation on open.
         *
         * The correct sequence is: rename the old table aside, create the new one, copy, drop the
         * backup. That is what this does.
         *
         * `analysis_events` predates the metric column, so rows copied from the backup are tagged
         * `equals_pct`: those events really were recorded against the target digit's raw frequency
         * before the differs fix. Labelling them `differs_pct` would silently rewrite their meaning.
         */
        private val MIGRATION_5_TO_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Purely additive: one new column per table.
                //
                // ALTER TABLE ADD COLUMN is used deliberately instead of the rename-recreate-copy
                // dance. Recreating is only necessary when a column is dropped, renamed or retyped.
                // Recreating here silently broke two things that are easy to miss:
                //   - the recreated user_settings DDL omitted the FOREIGN KEY to profiles, and
                //     Room validates foreign keys, not just columns;
                //   - `CREATE INDEX IF NOT EXISTS index_user_settings_userId` became a no-op,
                //     because the index name was still taken by the index attached to the renamed
                //     backup table, so the new table ended up with no index at all.
                // ADD COLUMN preserves the existing foreign keys, indices and every row untouched.
                //
                // analysis_events is backfilled 'equals_pct' because those rows really do hold the
                // target digit's raw frequency, recorded before the differs fix. Labelling them
                // 'differs_pct' would rewrite their meaning silently.
                db.execSQL(
                    "ALTER TABLE `user_settings` ADD COLUMN `analysisMetric` TEXT NOT NULL DEFAULT 'differs_pct'"
                )
                db.execSQL(
                    "ALTER TABLE `analysis_events` ADD COLUMN `analysisMetric` TEXT NOT NULL DEFAULT 'equals_pct'"
                )
            }
        }
        /**
         * v6 -> v7: record whether the Differs target digit is fixed or randomly redrawn.
         *
         * Additive, so ALTER TABLE ADD COLUMN — recreating the table for an added column silently
         * drops foreign keys and no-ops the index (see MIGRATION_5_TO_6's note for the full story
         * of how that shipped as a launch crash).
         */
        private val MIGRATION_6_TO_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `user_settings` ADD COLUMN `digitSelectionMode` TEXT NOT NULL DEFAULT 'fixed'"
                )
            }
        }
        /**
         * v7 -> v8: record which account and strategy a session ran on.
         *
         * Additive, so ALTER TABLE ADD COLUMN throughout (see MIGRATION_5_TO_6 for why recreating a
         * table for an added column is a trap).
         *
         * Existing rows get empty defaults: they predate session tracking entirely, so there was no
         * account or strategy to record for them and inventing one would misreport history.
         */
        private val MIGRATION_7_TO_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `simulation_sessions` ADD COLUMN `accountId` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `simulation_sessions` ADD COLUMN `isReal` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `simulation_sessions` ADD COLUMN `strategy` TEXT NOT NULL DEFAULT 'Differs'"
                )
                db.execSQL(
                    "ALTER TABLE `simulation_sessions` ADD COLUMN `targetDigit` INTEGER NOT NULL DEFAULT 2"
                )
                // The session-scoped queries all filter on sessionId, which had no index.
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_simulation_positions_sessionId` ON `simulation_positions` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_simulation_positions_userId_sessionId` ON `simulation_positions` (`userId`, `sessionId`)")
            }
        }

        /**
         * v8 -> v9: track which trades have been uploaded to FIDScript.
         *
         * Additive, so ALTER TABLE ADD COLUMN — recreating the table for an added column drops
         * foreign keys and no-ops the index (see MIGRATION_5_TO_6's note for how that shipped as a
         * launch crash).
         *
         * Defaulting to 0 marks every existing trade as un-uploaded, which is deliberate and not a
         * convenience: those rows predate the cloud history entirely, so the first sync has to send
         * them rather than assume they are there.
         */
        private val MIGRATION_8_TO_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `simulation_positions` ADD COLUMN `syncedToCloud` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v9 -> v10: give the Over/Under recovery its own entry condition.
         *
         * Additive, so ALTER TABLE ADD COLUMN throughout (see MIGRATION_5_TO_6's note for how
         * recreating the table for added columns shipped as a launch crash).
         *
         * The defaults are the ones in [UserSettingsEntity], and they are what the app would have
         * used anyway: existing rows never had a recovery condition of their own, they were being
         * evaluated against the Differs one, which for any usable Differs threshold (>90%) is a
         * comparison no ~50%-scale recovery reading can satisfy. So the seeded default is what the
         * user would get by configuring it themselves, not an invention.
         */
        private val MIGRATION_9_TO_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `user_settings` ADD COLUMN `recoveryComparisonOperator` TEXT NOT NULL DEFAULT '>'"
                )
                db.execSQL(
                    "ALTER TABLE `user_settings` ADD COLUMN `recoveryThresholdPercent` REAL NOT NULL DEFAULT 55.0"
                )
            }
        }

        /**
         * v10 → v11: the trade-detail snapshot columns.
         *
         * `ALTER TABLE ADD COLUMN`, not a recreate. Recreating `simulation_positions` would drop
         * its foreign keys to `profiles` and collide with the existing index names — the same trap
         * the earlier additive migrations avoided.
         *
         * Defaults matter here. `payoutRatio` is nullable and the others default to 0/0.0, so every
         * trade already in the database stays readable and reads as "not recorded" rather than as
         * a return rate of zero, which would render as a 0% return and look like a real figure.
         */
        private val MIGRATION_10_TO_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `simulation_positions` ADD COLUMN `askPrice` REAL NOT NULL DEFAULT 0.0"
                )
                db.execSQL(
                    "ALTER TABLE `simulation_positions` ADD COLUMN `payoutRatio` REAL"
                )
                db.execSQL(
                    "ALTER TABLE `simulation_positions` ADD COLUMN `recoveryStep` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `simulation_positions` ADD COLUMN `outstandingBefore` REAL NOT NULL DEFAULT 0.0"
                )
            }
        }

        /**
         * v11 → v12: per-session realised results.
         *
         * Additive, for the same reason as every migration before it. Sessions closed before this
         * keep 0.0 across the new columns, which reads as "no recorded result" rather than as a
         * break-even run — the distinction matters, because a run that genuinely netted nothing and
         * a run whose result was never captured are not the same fact.
         */
        private val MIGRATION_11_TO_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `simulation_sessions` ADD COLUMN `netProfit` REAL NOT NULL DEFAULT 0.0"
                )
                db.execSQL(
                    "ALTER TABLE `simulation_sessions` ADD COLUMN `grossProfit` REAL NOT NULL DEFAULT 0.0"
                )
                db.execSQL(
                    "ALTER TABLE `simulation_sessions` ADD COLUMN `grossLoss` REAL NOT NULL DEFAULT 0.0"
                )
                db.execSQL(
                    "ALTER TABLE `simulation_sessions` ADD COLUMN `totalStaked` REAL NOT NULL DEFAULT 0.0"
                )
            }
        }

        /**
         * v12 → v13: the strategy the user last ran.
         *
         * Additive, like every migration before it. The default is the strategy that has always
         * been the app's only one, so an existing user opens on the behaviour they are used to
         * rather than on an empty screen.
         */
        private val MIGRATION_12_TO_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `user_settings` ADD COLUMN `selectedStrategyId` TEXT NOT NULL DEFAULT 'differs_with_recovery'"
                )
            }
        }

        /**
         * Version 14: persist the live-risk acknowledgement.
         *
         * It lived in memory only, so a fresh process armed live silently — `setRealArmed(true)`
         * saw `liveRiskAcknowledged` false, showed the dialog, and the next restart showed it
         * again, while a restart *while* on live re-armed it with no question at all. The
         * acknowledgement is the point, so it must survive with the settings.
         */
        private val MIGRATION_13_TO_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `user_settings` ADD COLUMN `liveRiskAcknowledged` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Version 15: persist the Even/Odd strategy's entry settings.
         *
         * The three columns steer the parity entry: which class to trade (auto / even / odd),
         * whether the threshold is an above- or a below-test, and the threshold itself. The
         * defaults are what [TradeSettings] ships with, so an existing user who has never
         * touched the Even/Odd card gets a working Auto + above-55% strategy out of the box.
         *
         * Additive, like every migration before it — never a table recreate, which would drop
         * the foreign keys and the index names the earlier migrations built.
         */
        private val MIGRATION_14_TO_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `user_settings` ADD COLUMN `evenOddMode` TEXT NOT NULL DEFAULT 'auto'"
                )
                db.execSQL(
                    "ALTER TABLE `user_settings` ADD COLUMN `evenOddOperator` TEXT NOT NULL DEFAULT '>'"
                )
                db.execSQL(
                    "ALTER TABLE `user_settings` ADD COLUMN `evenOddThreshold` REAL NOT NULL DEFAULT 55.0"
                )
            }
        }

        /**
         * Version 16: split the shared settings row into one complete blob per strategy, and stamp
         * every trade and session with the strategy that produced it.
         *
         * The Differs and Even/Odd strategies previously lived in one [UserSettingsEntity] row, so
         * each strategy's knobs (stake, TP/SL, entry thresholds) were one shared set. This creates
         * `strategy_settings` — one row per (userId, strategyId) — and seeds each strategy's row
         * from the legacy values: an upgrade carries the user's configured numbers into *both*
         * strategies' blobs, which is the intended "split the shared settings" outcome.
         *
         * The `EXISTS (…profiles…)` guard on the seed is load-bearing, not defensive: the
         * production `user_settings` table has no foreign key to `profiles` (the v5 recreate
         * omitted it — see [MIGRATION_5_TO_6]), so a settings row can exist for a user whose
         * profile row does not. `strategy_settings` *does* carry the FK, and copying such a row
         * would abort the whole migration mid-upgrade.
         *
         * `strategyId` on positions and sessions backfills from `scenarioType` / the `strategy`
         * name: the only scenario that belongs to the Even/Odd strategy is "Even / Odd"
         * ([com.tickedge.data.model.TradeType.EVEN_ODD].displayName) — Differs entries and their
         * Over/Under recovery rungs both belong to the Differs strategy.
         */
        private val MIGRATION_15_TO_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `strategy_settings` (
                        `userId` TEXT NOT NULL,
                        `strategyId` TEXT NOT NULL,
                        `differsDigit` INTEGER NOT NULL DEFAULT 2,
                        `differsDigitMode` TEXT NOT NULL DEFAULT 'random',
                        `differsOperator` TEXT NOT NULL DEFAULT '<',
                        `differsThreshold` REAL NOT NULL DEFAULT 85.0,
                        `recoveryDirection` TEXT NOT NULL DEFAULT 'OVER',
                        `recoveryDigit` INTEGER NOT NULL DEFAULT 2,
                        `recoveryOperator` TEXT NOT NULL DEFAULT '>',
                        `recoveryThreshold` REAL NOT NULL DEFAULT 80.0,
                        `evenOddMode` TEXT NOT NULL DEFAULT 'auto',
                        `evenOddOperator` TEXT NOT NULL DEFAULT '>',
                        `evenOddThreshold` REAL NOT NULL DEFAULT 55.0,
                        `stakeAmount` REAL NOT NULL DEFAULT 0.35,
                        `takeProfit` REAL NOT NULL DEFAULT 10.0,
                        `stopLoss` REAL NOT NULL DEFAULT 10.0,
                        `durationUnit` TEXT NOT NULL DEFAULT 'Ticks',
                        `durationValue` INTEGER NOT NULL DEFAULT 1,
                        `martingaleEnabled` INTEGER NOT NULL DEFAULT 1,
                        `analysisWindow` INTEGER NOT NULL DEFAULT 100,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (`userId`, `strategyId`),
                        FOREIGN KEY (`userId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_strategy_settings_userId` ON `strategy_settings` (`userId`)"
                )

                fun seedLegacyRows(strategyId: String) {
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO `strategy_settings`
                            (`userId`, `strategyId`, `differsDigit`, `differsDigitMode`, `differsOperator`,
                             `differsThreshold`, `recoveryDirection`, `recoveryDigit`, `recoveryOperator`,
                             `recoveryThreshold`, `evenOddMode`, `evenOddOperator`, `evenOddThreshold`,
                             `stakeAmount`, `takeProfit`, `stopLoss`, `durationUnit`, `durationValue`,
                             `martingaleEnabled`, `analysisWindow`, `updatedAt`)
                        SELECT `userId`, '$strategyId', `selectedDigit`, `digitSelectionMode`,
                               `comparisonOperator`, `thresholdPercent`, `recoveryDirection`,
                               `recoveryDigit`, `recoveryComparisonOperator`, `recoveryThresholdPercent`,
                               `evenOddMode`, `evenOddOperator`, `evenOddThreshold`,
                               `stakeAmount`, `takeProfit`, `stopLoss`, `durationUnit`, `durationValue`,
                               `martingaleEnabled`, `windowSize`, `updatedAt`
                        FROM `user_settings`
                        WHERE EXISTS (SELECT 1 FROM `profiles` p WHERE p.`id` = `user_settings`.`userId`)
                        """.trimIndent()
                    )
                }
                seedLegacyRows("differs_with_recovery")
                seedLegacyRows("even_odd")

                db.execSQL(
                    "ALTER TABLE `simulation_positions` ADD COLUMN `strategyId` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "UPDATE `simulation_positions` SET `strategyId` = 'even_odd' " +
                        "WHERE `strategyId` = '' AND `scenarioType` LIKE '%Even%'"
                )
                db.execSQL(
                    "UPDATE `simulation_positions` SET `strategyId` = 'differs_with_recovery' " +
                        "WHERE `strategyId` = ''"
                )
                db.execSQL(
                    "ALTER TABLE `simulation_sessions` ADD COLUMN `strategyId` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "UPDATE `simulation_sessions` SET `strategyId` = 'even_odd' " +
                        "WHERE `strategyId` = '' AND (`strategy` = 'Even / Odd' OR `strategy` LIKE '%Even%')"
                )
                db.execSQL(
                    "UPDATE `simulation_sessions` SET `strategyId` = 'differs_with_recovery' " +
                        "WHERE `strategyId` = ''"
                )
            }
        }

        /**
         * Every migration, in order.
         *
         * Exposed so the migration chain can be tested against the exported schemas without a
         * device. The individual migrations stay private — this is the list the builder registers,
         * and the test drives the same one the app does, rather than a copy that could drift from
         * it.
         *
         * Declared *after* the migrations themselves because Kotlin initialises properties in
         * declaration order, and a list built from not-yet-initialised values throws.
         */
        internal val ALL_MIGRATIONS: List<Migration> = listOf(
            MIGRATION_4_TO_5, MIGRATION_5_TO_6, MIGRATION_6_TO_7, MIGRATION_7_TO_8,
            MIGRATION_8_TO_9, MIGRATION_9_TO_10, MIGRATION_10_TO_11, MIGRATION_11_TO_12,
            MIGRATION_12_TO_13, MIGRATION_13_TO_14, MIGRATION_14_TO_15, MIGRATION_15_TO_16
        )
    }
}
