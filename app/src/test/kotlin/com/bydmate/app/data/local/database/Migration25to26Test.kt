package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration25to26Test {

    private val dbName = "migration-test-25-26.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `25 to 26 adds battery power columns and keeps the charge buffer`() {
        helper.createDatabase(dbName, 25).use { old ->
            old.execSQL(
                "INSERT INTO trip_points (trip_id, timestamp, lat, lon, speed_kmh, altitude) " +
                    "VALUES (1, 1000, 53.5, 27.5, 42.0, 250.0)"
            )
            old.execSQL(
                "INSERT INTO trip_points (trip_id, timestamp, lat, lon, speed_kmh, altitude) " +
                    "VALUES (1, 2000, 53.6, 27.6, 55.0, 251.0)"
            )
            old.execSQL(
                "INSERT INTO charge_samples (ts, power_kw, soc, bat_temp) VALUES (1790000123000, 3.4, 78, 27)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 26, true, AppModule.MIGRATION_25_26)

        migrated.query(
            "SELECT trip_id, timestamp, lat, lon, speed_kmh, altitude, battery_power_w, battery_energy_wh, energy_run " +
                "FROM trip_points ORDER BY timestamp"
        ).use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals(1L, c.getLong(0)); assertEquals(1000L, c.getLong(1))
            assertEquals(53.5, c.getDouble(2), 1e-9); assertEquals(27.5, c.getDouble(3), 1e-9)
            assertEquals(42.0, c.getDouble(4), 1e-9); assertEquals(250.0, c.getDouble(5), 1e-9)
            assertTrue(c.isNull(6)); assertTrue(c.isNull(7)); assertTrue(c.isNull(8))
            c.moveToNext()
            assertEquals(1L, c.getLong(0)); assertEquals(2000L, c.getLong(1))
            assertEquals(53.6, c.getDouble(2), 1e-9); assertEquals(27.6, c.getDouble(3), 1e-9)
            assertEquals(55.0, c.getDouble(4), 1e-9); assertEquals(251.0, c.getDouble(5), 1e-9)
            assertTrue(c.isNull(6)); assertTrue(c.isNull(7)); assertTrue(c.isNull(8))
        }
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
