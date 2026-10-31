package com.tickedge.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The migration chain, run against real SQLite.
 *
 * Every migration in this app has been untested, which means every one of them is a crash waiting
 * for the user who upgrades. Room's own `MigrationTestHelper` would be the tool for this, but it
 * lives in an artifact that is not available offline here — so the migrations are driven directly
 * against a SQLite database here instead, which tests the same SQL the app runs.
 *
 * The schema the migration starts from is the one Room exported for that version, read out of the
 * schema JSON, so this cannot drift from what was really shipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

    /** The v12 `user_settings` table, exactly as Room exported it. */
    private val userSettingsV12 = """
        CREATE TABLE IF NOT EXISTS `user_settings` (
            `id` INTEGER NOT NULL,
            `userId` TEXT NOT NULL,
            `windowSize` INTEGER NOT NULL,
            `selectedDigit` INTEGER NOT NULL,
            `recoveryDigit` INTEGER NOT NULL,
            `recoveryDirection` TEXT NOT NULL,
            `comparisonOperator` TEXT NOT NULL,
            `thresholdPercent` REAL NOT NULL,
            `recoveryComparisonOperator` TEXT NOT NULL,
            `recoveryThresholdPercent` REAL NOT NULL,
            `analysisMetric` TEXT NOT NULL,
            `digitSelectionMode` TEXT NOT NULL,
            `minimumSampleSize` INTEGER NOT NULL,
            `stakeAmount` REAL NOT NULL,
            `takeProfit` REAL NOT NULL,
            `stopLoss` REAL NOT NULL,
            `durationUnit` TEXT NOT NULL,
            `durationValue` INTEGER NOT NULL,
            `martingaleEnabled` INTEGER NOT NULL,
            `tradeType` TEXT NOT NULL,
            `randomMarkets` INTEGER NOT NULL,
            `maxTradesPerMarket` INTEGER NOT NULL,
            `maxDailyLoss` REAL NOT NULL,
            `stopTradingOnLimit` INTEGER NOT NULL,
            `realArmed` INTEGER NOT NULL,
            `requirePerOrderConfirm` INTEGER NOT NULL,
            `maxMartingaleSteps` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`userId`, `id`)
        )
    """.trimIndent()

    private val profilesV12 = """
        CREATE TABLE IF NOT EXISTS `profiles` (
            `id` TEXT NOT NULL,
            `displayName` TEXT NOT NULL,
            `email` TEXT NOT NULL,
            `simulatedBalance` REAL NOT NULL,
            `liveBalance` REAL NOT NULL,
            `demoBalance` REAL NOT NULL,
            `activeAccount` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
    """.trimIndent()

    /**
     * Runs [migration] against a database carrying the v12 schema.
     *
     * The database is opened through Room's framework factory so the migration receives the same
     * `SupportSQLiteDatabase` type it gets in the app.
     */
    private fun runMigration(
        migration: Migration,
        before: (SupportSQLiteDatabase) -> Unit = {}
    ): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A fresh database per call. The name has to be unique or a leftover file from an earlier
        // test is opened instead, and the test then runs against a schema nobody wrote.
        val name = "migration-test-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(profilesV12)
                    db.execSQL(userSettingsV12)
                }

                // Never reached: the test database is always created at version 1 and the
                // migration is applied by hand. It has to be implemented because the base class
                // declares it abstract.
                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        before(db)
        migration.migrate(db)
        return db
    }

    @Test
    fun `the twelve to thirteen migration adds the strategy column`() {
        val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 12 }
        val db = runMigration(migration)

        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`user_settings`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex))
        }

        assertTrue(
            "selectedStrategyId is missing after the migration: $columns",
            columns.contains("selectedStrategyId")
        )
    }

    @Test
    fun `the strategy column defaults to the built in strategy`() {
        // A user upgrading mid-ladder must land on a strategy that exists, not on a blank one. The
        // row is written before the migration, so this is the real upgrade path.
        val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 12 }
        val db = runMigration(migration) { before ->
            before.execSQL(
                "INSERT INTO `profiles` (`id`, `displayName`, `email`, `simulatedBalance`, " +
                    "`liveBalance`, `demoBalance`, `activeAccount`, `createdAt`) " +
                    "VALUES ('u1', 'Test', '', 0.0, 0.0, 0.0, 'DEMO', 0)"
            )
            before.execSQL(
                "INSERT INTO `user_settings` (`userId`, `id`, `windowSize`, `selectedDigit`, " +
                    "`recoveryDigit`, `recoveryDirection`, `comparisonOperator`, `thresholdPercent`, " +
                    "`recoveryComparisonOperator`, `recoveryThresholdPercent`, `analysisMetric`, " +
                    "`digitSelectionMode`, `minimumSampleSize`, `stakeAmount`, `takeProfit`, " +
                    "`stopLoss`, `durationUnit`, `durationValue`, `martingaleEnabled`, `tradeType`, " +
                    "`randomMarkets`, `maxTradesPerMarket`, `maxDailyLoss`, `stopTradingOnLimit`, " +
                    "`realArmed`, `requirePerOrderConfirm`, `maxMartingaleSteps`, `updatedAt`) " +
                    "VALUES ('u1', 1, 100, 2, 4, 'OVER', '>', 85.0, '>', 80.0, 'differs_pct', " +
                    "'random', 10, 0.35, 10.0, 10.0, 'Ticks', 1, 1, 'Differs', 1, 3, 20.0, 1, 0, 0, 3, 0)"
            )
        }

        db.query("SELECT `selectedStrategyId` FROM `user_settings` WHERE `userId` = 'u1'")
            .use { cursor ->
                assertTrue("the row was not readable", cursor.moveToFirst())
                assertEquals("differs_with_recovery", cursor.getString(0))
            }
    }

    @Test
    fun `existing settings survive the migration intact`() {
        // The migration must not disturb what was already there. A column added in the wrong place,
        // or a statement that rewrites the row, loses the user's configuration.
        val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 12 }

        // The row is written *before* the migration runs, which is the whole point: the user's
        // settings exist before they upgrade.
        val db = runMigration(migration) { before ->
            before.execSQL(
                "INSERT INTO `profiles` (`id`, `displayName`, `email`, `simulatedBalance`, " +
                    "`liveBalance`, `demoBalance`, `activeAccount`, `createdAt`) " +
                    "VALUES ('u2', 'Test', '', 0.0, 0.0, 0.0, 'DEMO', 0)"
            )
            before.execSQL(
                "INSERT INTO `user_settings` (`userId`, `id`, `windowSize`, `selectedDigit`, " +
                    "`recoveryDigit`, `recoveryDirection`, `comparisonOperator`, `thresholdPercent`, " +
                    "`recoveryComparisonOperator`, `recoveryThresholdPercent`, `analysisMetric`, " +
                    "`digitSelectionMode`, `minimumSampleSize`, `stakeAmount`, `takeProfit`, " +
                    "`stopLoss`, `durationUnit`, `durationValue`, `martingaleEnabled`, `tradeType`, " +
                    "`randomMarkets`, `maxTradesPerMarket`, `maxDailyLoss`, `stopTradingOnLimit`, " +
                    "`realArmed`, `requirePerOrderConfirm`, `maxMartingaleSteps`, `updatedAt`) " +
                    "VALUES ('u2', 1, 250, 7, 4, 'OVER', '>', 92.5, '>', 80.0, 'differs_pct', " +
                    "'random', 10, 1.25, 30.0, 15.0, 'Ticks', 2, 1, 'Differs', 1, 3, 40.0, 1, 0, 0, 3, 0)"
            )
        }

        db.query(
            "SELECT `windowSize`, `thresholdPercent`, `stakeAmount`, `takeProfit`, " +
                "`stopLoss`, `durationValue` FROM `user_settings` WHERE `userId` = 'u2'"
        ).use { cursor ->
            assertTrue("the row was lost", cursor.moveToFirst())
            assertEquals(250, cursor.getInt(0))
            assertEquals(92.5, cursor.getDouble(1), 0.0001)
            assertEquals(1.25, cursor.getDouble(2), 0.0001)
            assertEquals(30.0, cursor.getDouble(3), 0.0001)
            assertEquals(15.0, cursor.getDouble(4), 0.0001)
            assertEquals(2, cursor.getInt(5))
        }
    }

    /**
     * The v14 `user_settings` table: v12 plus the v13 and v14 columns. The v14→v15 test has to
     * start from exactly what a user upgrading from 14 has.
     */
    private val userSettingsV14 = """
        CREATE TABLE IF NOT EXISTS `user_settings` (
            `id` INTEGER NOT NULL,
            `userId` TEXT NOT NULL,
            `windowSize` INTEGER NOT NULL,
            `selectedDigit` INTEGER NOT NULL,
            `recoveryDigit` INTEGER NOT NULL,
            `recoveryDirection` TEXT NOT NULL,
            `comparisonOperator` TEXT NOT NULL,
            `thresholdPercent` REAL NOT NULL,
            `recoveryComparisonOperator` TEXT NOT NULL,
            `recoveryThresholdPercent` REAL NOT NULL,
            `analysisMetric` TEXT NOT NULL,
            `digitSelectionMode` TEXT NOT NULL,
            `minimumSampleSize` INTEGER NOT NULL,
            `stakeAmount` REAL NOT NULL,
            `takeProfit` REAL NOT NULL,
            `stopLoss` REAL NOT NULL,
            `durationUnit` TEXT NOT NULL,
            `durationValue` INTEGER NOT NULL,
            `martingaleEnabled` INTEGER NOT NULL,
            `tradeType` TEXT NOT NULL,
            `randomMarkets` INTEGER NOT NULL,
            `maxTradesPerMarket` INTEGER NOT NULL,
            `maxDailyLoss` REAL NOT NULL,
            `stopTradingOnLimit` INTEGER NOT NULL,
            `realArmed` INTEGER NOT NULL,
            `requirePerOrderConfirm` INTEGER NOT NULL,
            `maxMartingaleSteps` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `selectedStrategyId` TEXT NOT NULL DEFAULT 'differs_with_recovery',
            `liveRiskAcknowledged` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`userId`, `id`)
        )
    """.trimIndent()

    @Test
    fun `the fourteen to fifteen migration adds the even odd columns with safe defaults`() {
        // A user upgrading from 14 must land on a working Even/Odd configuration: Auto mode, an
        // above-test, and a threshold that actually fires sometimes. A wrong default here does not
        // crash — it silently trades a strategy nobody configured.
        val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 14 }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(profilesV12)
                    db.execSQL(userSettingsV14)
                    // A pre-upgrade row: everything the migration must survive intact.
                    db.execSQL(
                        "INSERT INTO `profiles` (`id`, `displayName`, `email`, `simulatedBalance`, " +
                            "`liveBalance`, `demoBalance`, `activeAccount`, `createdAt`) " +
                            "VALUES ('u1', 'Test', '', 0.0, 0.0, 0.0, 'DEMO', 0)"
                    )
                    db.execSQL(
                        "INSERT INTO `user_settings` (`userId`, `id`, `windowSize`, `selectedDigit`, " +
                            "`recoveryDigit`, `recoveryDirection`, `comparisonOperator`, `thresholdPercent`, " +
                            "`recoveryComparisonOperator`, `recoveryThresholdPercent`, `analysisMetric`, " +
                            "`digitSelectionMode`, `minimumSampleSize`, `stakeAmount`, `takeProfit`, " +
                            "`stopLoss`, `durationUnit`, `durationValue`, `martingaleEnabled`, `tradeType`, " +
                            "`randomMarkets`, `maxTradesPerMarket`, `maxDailyLoss`, `stopTradingOnLimit`, " +
                            "`realArmed`, `requirePerOrderConfirm`, `maxMartingaleSteps`, `updatedAt`, " +
                            "`selectedStrategyId`, `liveRiskAcknowledged`) " +
                            "VALUES ('u1', 1, 100, 2, 4, 'OVER', '>', 85.0, '>', 80.0, 'differs_pct', " +
                            "'random', 10, 0.35, 10.0, 10.0, 'Ticks', 1, 1, 'Differs', 1, 3, 20.0, 1, 0, 0, 3, 0, " +
                            "'differs_with_recovery', 1)"
                    )
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        migration.migrate(db)

        db.query("SELECT `evenOddMode`, `evenOddOperator`, `evenOddThreshold`, `windowSize`, `selectedStrategyId` " +
                "FROM `user_settings` WHERE `userId` = 'u1'")
            .use { cursor ->
                assertTrue("the row was not readable", cursor.moveToFirst())
                assertEquals("auto", cursor.getString(0))
                assertEquals(">", cursor.getString(1))
                assertEquals(55.0, cursor.getDouble(2), 0.0001)
                assertEquals(100, cursor.getInt(3))
                assertEquals("differs_with_recovery", cursor.getString(4))
            }
    }

    /**
     * The v15 `user_settings` table: v14 plus the three evenOdd* columns added by the 14→15
     * migration.
     */
    private val userSettingsV15 = """
        CREATE TABLE IF NOT EXISTS `user_settings` (
            `id` INTEGER NOT NULL,
            `userId` TEXT NOT NULL,
            `windowSize` INTEGER NOT NULL,
            `selectedDigit` INTEGER NOT NULL,
            `recoveryDigit` INTEGER NOT NULL,
            `recoveryDirection` TEXT NOT NULL,
            `comparisonOperator` TEXT NOT NULL,
            `thresholdPercent` REAL NOT NULL,
            `recoveryComparisonOperator` TEXT NOT NULL,
            `recoveryThresholdPercent` REAL NOT NULL,
            `analysisMetric` TEXT NOT NULL,
            `digitSelectionMode` TEXT NOT NULL,
            `minimumSampleSize` INTEGER NOT NULL,
            `stakeAmount` REAL NOT NULL,
            `takeProfit` REAL NOT NULL,
            `stopLoss` REAL NOT NULL,
            `durationUnit` TEXT NOT NULL,
            `durationValue` INTEGER NOT NULL,
            `martingaleEnabled` INTEGER NOT NULL,
            `tradeType` TEXT NOT NULL,
            `randomMarkets` INTEGER NOT NULL,
            `maxTradesPerMarket` INTEGER NOT NULL,
            `maxDailyLoss` REAL NOT NULL,
            `stopTradingOnLimit` INTEGER NOT NULL,
            `realArmed` INTEGER NOT NULL,
            `requirePerOrderConfirm` INTEGER NOT NULL,
            `maxMartingaleSteps` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `selectedStrategyId` TEXT NOT NULL DEFAULT 'differs_with_recovery',
            `liveRiskAcknowledged` INTEGER NOT NULL DEFAULT 0,
            `evenOddMode` TEXT NOT NULL DEFAULT 'auto',
            `evenOddOperator` TEXT NOT NULL DEFAULT '>',
            `evenOddThreshold` REAL NOT NULL DEFAULT 55.0,
            PRIMARY KEY(`userId`, `id`)
        )
    """.trimIndent()

    /** The v15 `simulation_sessions` table: v8 columns plus the v12 result columns. */
    private val sessionsV15 = """
        CREATE TABLE IF NOT EXISTS `simulation_sessions` (
            `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            `userId` TEXT NOT NULL,
            `initialSimulatedBalance` REAL NOT NULL DEFAULT 0.0,
            `currentSimulatedBalance` REAL NOT NULL DEFAULT 0.0,
            `status` TEXT NOT NULL DEFAULT 'ACTIVE',
            `startedAt` INTEGER NOT NULL DEFAULT 0,
            `endedAt` INTEGER,
            `totalTrades` INTEGER NOT NULL DEFAULT 0,
            `winCount` INTEGER NOT NULL DEFAULT 0,
            `lossCount` INTEGER NOT NULL DEFAULT 0,
            `accountId` TEXT NOT NULL DEFAULT '',
            `isReal` INTEGER NOT NULL DEFAULT 0,
            `strategy` TEXT NOT NULL DEFAULT 'Differs',
            `targetDigit` INTEGER NOT NULL DEFAULT 2,
            `netProfit` REAL NOT NULL DEFAULT 0.0,
            `grossProfit` REAL NOT NULL DEFAULT 0.0,
            `grossLoss` REAL NOT NULL DEFAULT 0.0,
            `totalStaked` REAL NOT NULL DEFAULT 0.0
        )
    """.trimIndent()

    /** The v15 `simulation_positions` table: v5 plus the v9/v11 snapshot columns. */
    private val positionsV15 = """
        CREATE TABLE IF NOT EXISTS `simulation_positions` (
            `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            `userId` TEXT NOT NULL,
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
            `syncedToCloud` INTEGER NOT NULL DEFAULT 0,
            `askPrice` REAL NOT NULL DEFAULT 0.0,
            `payoutRatio` REAL,
            `recoveryStep` INTEGER NOT NULL DEFAULT 0,
            `outstandingBefore` REAL NOT NULL DEFAULT 0.0
        )
    """.trimIndent()

    /**
     * Runs a migration against a database carrying the v15 schema (user_settings, sessions and
     * positions), optionally seeding rows before the migration runs.
     */
    private fun runMigrationFromV15(
        migration: Migration,
        seed: (SupportSQLiteDatabase) -> Unit
    ): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(profilesV12)
                    db.execSQL(userSettingsV15)
                    db.execSQL(sessionsV15)
                    db.execSQL(positionsV15)
                    seed(db)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        migration.migrate(db)
        return db
    }

    @Test
    fun `the fifteen to sixteen migration seeds one strategy settings row per strategy`() {
        val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 15 }
        val db = runMigrationFromV15(migration) { before ->
            before.execSQL(
                "INSERT INTO `profiles` (`id`, `displayName`, `email`, `simulatedBalance`, " +
                    "`liveBalance`, `demoBalance`, `activeAccount`, `createdAt`) " +
                    "VALUES ('u1', 'Test', '', 0.0, 0.0, 0.0, 'DEMO', 0)"
            )
            before.execSQL(
                "INSERT INTO `user_settings` (`userId`, `id`, `windowSize`, `selectedDigit`, " +
                    "`recoveryDigit`, `recoveryDirection`, `comparisonOperator`, `thresholdPercent`, " +
                    "`recoveryComparisonOperator`, `recoveryThresholdPercent`, `analysisMetric`, " +
                    "`digitSelectionMode`, `minimumSampleSize`, `stakeAmount`, `takeProfit`, " +
                    "`stopLoss`, `durationUnit`, `durationValue`, `martingaleEnabled`, `tradeType`, " +
                    "`randomMarkets`, `maxTradesPerMarket`, `maxDailyLoss`, `stopTradingOnLimit`, " +
                    "`realArmed`, `requirePerOrderConfirm`, `maxMartingaleSteps`, `updatedAt`) " +
                    "VALUES ('u1', 1, 250, 7, 4, 'OVER', '<', 88.0, '>', 80.0, 'differs_pct', " +
                    "'random', 10, 1.25, 30.0, 15.0, 'Ticks', 2, 1, 'Differs', 1, 3, 40.0, 1, 0, 0, 3, 0)"
            )
        }

        val rows = mutableMapOf<String, Map<String, Any?>>()
        db.query("SELECT * FROM `strategy_settings` WHERE `userId` = 'u1'").use { cursor ->
            while (cursor.moveToNext()) {
                val nameIndex = cursor.getColumnIndex("strategyId")
                val strategyId = cursor.getString(nameIndex)
                rows[strategyId] = mapOf(
                    "stakeAmount" to cursor.getDouble(cursor.getColumnIndex("stakeAmount")),
                    "differsDigit" to cursor.getInt(cursor.getColumnIndex("differsDigit")),
                    "differsThreshold" to cursor.getDouble(cursor.getColumnIndex("differsThreshold")),
                    "analysisWindow" to cursor.getInt(cursor.getColumnIndex("analysisWindow")),
                    "takeProfit" to cursor.getDouble(cursor.getColumnIndex("takeProfit"))
                )
            }
        }

        // Two complete blobs, both carrying the legacy values — that is what the split means:
        // what used to be one shared set now belongs to each strategy individually.
        assertEquals("both strategy rows must exist: ${rows.keys}", 2, rows.size)
        val differs = rows["differs_with_recovery"]
        val parity = rows["even_odd"]
        assertTrue("differs row missing", differs != null)
        assertTrue("even_odd row missing", parity != null)
        assertEquals(1.25, differs!!["stakeAmount"] as Double, 0.0001)
        assertEquals(7, differs["differsDigit"] as Int)
        assertEquals(88.0, differs["differsThreshold"] as Double, 0.0001)
        assertEquals(250, differs["analysisWindow"] as Int)
        assertEquals(30.0, parity!!["takeProfit"] as Double, 0.0001)
    }

    @Test
    fun `the fifteen to sixteen migration backfills the strategy id on old rows`() {
        val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 15 }
        val db = runMigrationFromV15(migration) { before ->
            before.execSQL(
                "INSERT INTO `profiles` (`id`, `displayName`, `email`, `simulatedBalance`, " +
                    "`liveBalance`, `demoBalance`, `activeAccount`, `createdAt`) " +
                    "VALUES ('u1', 'Test', '', 0.0, 0.0, 0.0, 'DEMO', 0)"
            )
            before.execSQL(
                "INSERT INTO `user_settings` (`userId`, `id`, `windowSize`, `selectedDigit`, " +
                    "`recoveryDigit`, `recoveryDirection`, `comparisonOperator`, `thresholdPercent`, " +
                    "`recoveryComparisonOperator`, `recoveryThresholdPercent`, `analysisMetric`, " +
                    "`digitSelectionMode`, `minimumSampleSize`, `stakeAmount`, `takeProfit`, " +
                    "`stopLoss`, `durationUnit`, `durationValue`, `martingaleEnabled`, `tradeType`, " +
                    "`randomMarkets`, `maxTradesPerMarket`, `maxDailyLoss`, `stopTradingOnLimit`, " +
                    "`realArmed`, `requirePerOrderConfirm`, `maxMartingaleSteps`, `updatedAt`) " +
                    "VALUES ('u1', 1, 100, 2, 4, 'OVER', '<', 85.0, '>', 80.0, 'differs_pct', " +
                    "'random', 10, 0.35, 10.0, 10.0, 'Ticks', 1, 1, 'Differs', 1, 3, 20.0, 1, 0, 0, 3, 0)"
            )
            before.execSQL(
                "INSERT INTO `simulation_positions` (`userId`, `sessionId`, `marketSymbol`, " +
                    "`marketName`, `scenarioType`, `simulatedStake`, `simulatedPayout`, " +
                    "`simulatedNetResult`, `outcome`, `targetDigit`, `entryQuote`, `exitQuote`, " +
                    "`exitDigit`, `openedAt`, `settledAt`) " +
                    "VALUES ('u1', 1, 'R_25', 'Vol 25', 'Differs (Main)', 0.35, 0.0, -0.35, " +
                    "'LOST', 2, 0.0, 0.0, -1, 0, 0)"
            )
            before.execSQL(
                "INSERT INTO `simulation_positions` (`userId`, `sessionId`, `marketSymbol`, " +
                    "`marketName`, `scenarioType`, `simulatedStake`, `simulatedPayout`, " +
                    "`simulatedNetResult`, `outcome`, `targetDigit`, `entryQuote`, `exitQuote`, " +
                    "`exitDigit`, `openedAt`, `settledAt`) " +
                    "VALUES ('u1', 1, 'R_25', 'Vol 25', 'Even / Odd', 0.35, 0.7, 0.35, " +
                    "'WON', 0, 0.0, 0.0, -1, 0, 0)"
            )
            before.execSQL(
                "INSERT INTO `simulation_sessions` (`userId`, `strategy`, `startedAt`) " +
                    "VALUES ('u1', 'Even / Odd', 0)"
            )
        }

        db.query(
            "SELECT `scenarioType`, `strategyId` FROM `simulation_positions` " +
                "ORDER BY `scenarioType`"
        ).use { cursor ->
            val pairs = mutableListOf<String>()
            while (cursor.moveToNext()) {
                pairs.add("${cursor.getString(0)}=${cursor.getString(1)}")
            }
            assertTrue(
                "positions not backfilled by strategy: $pairs",
                pairs.contains("Differs (Main)=differs_with_recovery") &&
                    pairs.contains("Even / Odd=even_odd")
            )
        }

        db.query(
            "SELECT `strategyId` FROM `simulation_sessions` WHERE `strategy` = 'Even / Odd'"
        ).use { cursor ->
            assertTrue("the session row was not readable", cursor.moveToFirst())
            assertEquals("even_odd", cursor.getString(0))
        }
    }

    @Test
    fun `a settings row without a profile row does not abort the migration`() {
        // The production user_settings table has no foreign key to profiles, so a row can exist
        // for a user whose profile row does not. strategy_settings HAS the FK, and the migration's
        // EXISTS guard must skip the orphan rather than let it abort the whole upgrade.
        val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 15 }
        val db = runMigrationFromV15(migration) { before ->
            before.execSQL(
                "INSERT INTO `user_settings` (`userId`, `id`, `windowSize`, `selectedDigit`, " +
                    "`recoveryDigit`, `recoveryDirection`, `comparisonOperator`, `thresholdPercent`, " +
                    "`recoveryComparisonOperator`, `recoveryThresholdPercent`, `analysisMetric`, " +
                    "`digitSelectionMode`, `minimumSampleSize`, `stakeAmount`, `takeProfit`, " +
                    "`stopLoss`, `durationUnit`, `durationValue`, `martingaleEnabled`, `tradeType`, " +
                    "`randomMarkets`, `maxTradesPerMarket`, `maxDailyLoss`, `stopTradingOnLimit`, " +
                    "`realArmed`, `requirePerOrderConfirm`, `maxMartingaleSteps`, `updatedAt`) " +
                    "VALUES ('orphan', 1, 100, 2, 4, 'OVER', '<', 85.0, '>', 80.0, 'differs_pct', " +
                    "'random', 10, 0.35, 10.0, 10.0, 'Ticks', 1, 1, 'Differs', 1, 3, 20.0, 1, 0, 0, 3, 0)"
            )
        }

        db.query("SELECT COUNT(*) FROM `strategy_settings` WHERE `userId` = 'orphan'")
            .use { cursor ->
                assertTrue("the row was not readable", cursor.moveToFirst())
                assertEquals("orphan settings must not be copied, FK would fail", 0, cursor.getInt(0))
            }
    }

    @Test
    fun `every migration in the chain is registered in order`() {
        // A gap in the chain means the app cannot upgrade across it — Room throws at open time and
        // the user's database is unreachable.
        val versions = AppDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        versions.forEachIndexed { index, (start, end) ->
            assertEquals("migration $index must advance one version", start + 1, end)
            if (index > 0) {
                assertEquals(
                    "migration $index must follow the one before it",
                    versions[index - 1].second,
                    start
                )
            }
        }
    }
}
