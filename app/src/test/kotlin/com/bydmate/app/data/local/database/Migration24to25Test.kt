package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration24to25Test {

    private val dbName = "migration-test-24-25.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `24 to 25 adds the sample buffer and drops only the zero-duration drift row`() {
        helper.createDatabase(dbName, 24).use { old ->
            // The 2026-10-26 phantom: SOC 40 -> 47 heard four days later with no charger.
            old.execSQL(
                "INSERT INTO charges (id, start_ts, end_ts, soc_start, soc_end, kwh_charged, status, " +
                    "merged_count, cost_manual, detection_source) " +
                    "VALUES (4, 1793014925090, 1793014925090, 40, 47, 0.7056, 'COMPLETED', 0, 0, " +
                    "'autoservice_soc_estimate')"
            )
            // A real session must survive untouched, with its curve and snapshot.
            old.execSQL(
                "INSERT INTO charges (id, start_ts, end_ts, soc_start, soc_end, kwh_charged, status, " +
                    "merged_count, cost_manual, detection_source) " +
                    "VALUES (5, 1790000000000, 1790003600000, 48, 99, 37.179, 'COMPLETED', 0, 0, " +
                    "'autoservice_soc_fallback')"
            )
            old.execSQL("INSERT INTO charge_points (charge_id, timestamp) VALUES (5, 1790000000000)")
            old.execSQL(
                "INSERT INTO battery_snapshots (timestamp, charge_id, soc_start, soc_end, kwh_charged) " +
                    "VALUES (1793014925090, 4, 40, 47, 0.7056)"
            )
            old.execSQL(
                "INSERT INTO battery_snapshots (timestamp, charge_id, soc_start, soc_end, kwh_charged) " +
                    "VALUES (1790003600000, 5, 48, 99, 37.179)"
            )
            old.execSQL("INSERT INTO trip_tombstones (byd_id, start_ts) VALUES (42, 1000)")
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 25, true, AppModule.MIGRATION_24_25)

        migrated.query("SELECT id FROM charges").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(5L, c.getLong(0))
        }
        migrated.query("SELECT charge_id FROM battery_snapshots").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(5L, c.getLong(0))
        }
        migrated.query("SELECT charge_id FROM charge_points").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(5L, c.getLong(0))
        }
        migrated.query("SELECT start_ts FROM trip_tombstones").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(1000L, c.getLong(0))
        }

        migrated.execSQL(
            "INSERT INTO charge_samples (ts, power_kw, soc, bat_temp) VALUES (1790000123000, 3.4, 78, 27)"
        )
        migrated.query("SELECT ts, power_kw, soc, bat_temp FROM charge_samples").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(1790000123000L, c.getLong(0))
            assertEquals(3.4, c.getDouble(1), 1e-9)
            assertEquals(78, c.getInt(2))
            assertEquals(27, c.getInt(3))
        }
        migrated.close()
    }
}
