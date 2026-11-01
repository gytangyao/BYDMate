package com.bydmate.app.data.mysql

import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rendered statement is what the hub executes, so the column / value / update-set alignment is
 * pinned here rather than against a live connection.
 */
class UpsertRenderTest {

    private fun row(id: Long, msg: String) = Upsert(
        table = "app_logs",
        columns = listOf("vehicle_id", "source", "ts", "level", "tag", "msg", "run_id"),
        values = listOf("byd-1", "car", id, "W", "T", msg, null),
        key = null,
    )

    private fun trip(id: Long) = Upsert(
        table = "trips",
        columns = listOf("id", "vehicle_id", "distance_km", "source", "row_version", "updated_at"),
        values = listOf(id, "byd-1", 12.5, "app", 1L, 999L),
        key = listOf("vehicle_id", "id"),
    )

    @Test fun `an append-only table renders a plain INSERT with one tuple per row`() {
        val sql = Upsert.render(listOf(row(1L, "first"), row(2L, "second")))
        assertTrue(sql, sql.startsWith("INSERT INTO `app_logs` ("))
        assertTrue(sql, sql.contains("('byd-1', 'car', 1, "))
        assertTrue(sql, sql.contains("('byd-1', 'car', 2, "))
        assertFalse("car_state and app_logs have no update set", sql.contains("ON DUPLICATE"))
    }

    /** NULL is a keyword in the tuple, not the string NULL and not an empty quote. */
    @Test fun `a null run id renders as the NULL keyword`() {
        assertTrue(Upsert.render(listOf(row(1L, "m"))).endsWith(", NULL)"))
    }

    @Test fun `an upsert updates every non-key column and bumps row_version itself`() {
        val sql = Upsert.render(listOf(trip(7L)))
        assertTrue(sql, sql.contains("ON DUPLICATE KEY UPDATE"))
        assertTrue(sql, sql.contains("`distance_km`=VALUES(`distance_km`)"))
        assertTrue(sql, sql.contains("`row_version`=`row_version`+1"))
        assertFalse(sql, sql.contains("`id`=VALUES"))
        assertFalse(sql, sql.contains("`vehicle_id`=VALUES"))
    }

    /** A batch is one column list; mixing tables would silently write the wrong shape. */
    @Test(expected = IllegalArgumentException::class) fun `a batch refuses rows from two different tables`() {
        Upsert.render(listOf(trip(1L), row(2L, "m")))
    }

    /**
     * A log line that tries to close its own quote and append a statement must end up as data: the
     * injected quote is doubled, so the tuple still holds exactly five string values.
     */
    @Test fun `a text value that looks like SQL stays inside its quotes`() {
        val sql = Upsert.render(listOf(row(1L, "ev" + '\'' + "); DROP TABLE trips; -- ")))
        assertTrue(sql, sql.contains("'ev''); DROP TABLE trips; -- '"))
        assertEquals(12, sql.count { it == '\'' })
    }

    /**
     * The rolling delete is the only thing keeping `app_logs` from growing forever, so what it is
     * allowed to touch is pinned: this vehicle, both writers, and a cutoff the server computes.
     */
    @Test fun `log pruning deletes both sources up to the server clock cutoff`() {
        val sql = MysqlUpsertSql.logPrune("byd-1", 30, 5_000)
        assertTrue(sql, sql.startsWith("DELETE FROM `app_logs`"))
        assertTrue(sql, sql.contains("`vehicle_id` = 'byd-1'"))
        assertFalse("must not spare the phone's rows", sql.contains("`source`"))
        assertTrue(sql, sql.contains("(UNIX_TIMESTAMP() - 2592000) * 1000"))
        assertTrue(sql, sql.endsWith("LIMIT 5000"))
    }

    /** An unescaped vehicle id would turn retention into whatever the settings field says. */
    @Test fun `log pruning quotes the vehicle id`() {
        val sql = MysqlUpsertSql.logPrune("ev''); DROP TABLE trips; -- '", 30, 5_000)
        assertTrue(sql, sql.contains("'ev''''"))
        assertTrue(sql, sql.endsWith("LIMIT 5000"))
    }

    /**
     * The live sample table grows at a fixed rate, so its ceiling is three months of rows, and the
     * filter has to stay on the indexed columns: `vehicle_id` plus `pushed_at`.
     */
    @Test fun `car state pruning keeps three months up to the batch limit`() {
        val sql = MysqlUpsertSql.carStatePrune("byd-1", 90, 1_000)
        assertTrue(sql, sql.startsWith("DELETE FROM `car_state`"))
        assertTrue(sql, sql.contains("`vehicle_id` = 'byd-1'"))
        assertTrue(sql, sql.contains("`pushed_at` < (UNIX_TIMESTAMP() - 7776000) * 1000"))
        assertTrue(sql, sql.endsWith("LIMIT 1000"))
    }

    @Test fun `car state pruning quotes the vehicle id`() {
        val sql = MysqlUpsertSql.carStatePrune("ev''); DROP TABLE car_state; -- '", 90, 1_000)
        assertTrue(sql, sql.contains("'ev''''"))
        assertTrue(sql, sql.endsWith("LIMIT 1000"))
    }

    /**
     * Points exist on the car before the hub first sees them, so the same id may be sent twice
     * after a failed sync: the upsert key is what keeps that from duplicating the trace.
     */
    @Test fun `a trip point is keyed by vehicle and local id`() {
        val sql = MysqlUpsertSql.tripPoints(
            TripPointEntity(id = 9L, tripId = 4L, timestamp = 123L, lat = 1.5, lon = 2.5, speedKmh = 30.0),
            "byd-1",
        ).render()
        assertTrue(sql, sql.startsWith("INSERT INTO `trip_points`"))
        assertTrue(sql, sql.contains("ON DUPLICATE KEY UPDATE"))
        assertTrue(sql, sql.contains("`trip_id`=VALUES(`trip_id`)"))
        assertFalse("the key columns must not be in the update set", sql.contains("`id`=VALUES"))
    }

    /**
     * The detail tables have no `row_version` column, so the shared renderer must not bump one:
     * naming it in the update set fails the whole batch on the hub and rolls back every table in
     * the same transaction.
     */
    @Test fun `a detail table upsert never mentions row_version`() {
        val sql = MysqlUpsertSql.tripPoints(
            TripPointEntity(id = 9L, tripId = 4L, timestamp = 123L, lat = 1.5, lon = 2.5),
            "byd-1",
        ).render()
        assertFalse(sql, sql.contains("row_version"))

        val write = MysqlUpsertSql.vehicleWriteLogs(
            VehicleWriteLogEntity(
                id = 1L, ts = 2L, actionName = "lock", dev = 3, fid = 4, requested = 1,
                readback = 1, status = 0, error = null, validated = true,
            ),
            "byd-1",
        ).render()
        assertFalse(write, write.contains("row_version"))
    }

    /** MySQL has no boolean type on this path: it must travel as an integer, not `true`/`false`. */
    @Test fun `a vehicle write ships its validated flag as an integer`() {
        fun sql(validated: Boolean) = MysqlUpsertSql.vehicleWriteLogs(
            VehicleWriteLogEntity(
                id = 1L, ts = 2L, actionName = "lock", dev = 3, fid = 4, requested = 1,
                readback = 1, status = 0, error = null, validated = validated,
            ),
            "byd-1",
        ).render()
        assertTrue(sql(true), sql(true).contains(", 1)"))
        assertTrue(sql(false), sql(false).contains(", 0)"))
    }

    /**
     * Each detail table has its own time column; pruning the wrong one would delete by an
     * unrelated clock. All six share the server cutoff and the batch ceiling.
     */
    @Test fun `detail pruning uses each table's own time column`() {
        val expected = mapOf(
            MysqlUpsertSql.tripPointsPrune("byd-1", 90, 2_000) to "`ts` <",
            MysqlUpsertSql.chargePointsPrune("byd-1", 90, 2_000) to "`ts` <",
            MysqlUpsertSql.idleDrainsPrune("byd-1", 90, 2_000) to "`start_ts` <",
            MysqlUpsertSql.ruleLogsPrune("byd-1", 90, 2_000) to "`triggered_at` <",
            MysqlUpsertSql.vehicleWriteLogsPrune("byd-1", 90, 2_000) to "`ts` <",
            MysqlUpsertSql.counterSnapshotsPrune("byd-1", 90, 2_000) to "`ts` <",
        )
        for ((sql, column) in expected) {
            assertTrue(sql, sql.startsWith("DELETE FROM `"))
            assertTrue(sql, sql.contains("`vehicle_id` = 'byd-1'"))
            assertTrue(sql, sql.contains("$column (UNIX_TIMESTAMP() - 7776000) * 1000"))
            assertTrue(sql, sql.endsWith("LIMIT 2000"))
        }
    }

    @Test fun `detail pruning quotes the vehicle id`() {
        val sql = MysqlUpsertSql.tripPointsPrune("ev''); DROP TABLE trip_points; -- '", 90, 2_000)
        assertTrue(sql, sql.contains("'ev''''"))
        assertTrue(sql, sql.endsWith("LIMIT 2000"))
    }
}
