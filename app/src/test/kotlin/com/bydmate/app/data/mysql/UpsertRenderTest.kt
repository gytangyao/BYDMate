package com.bydmate.app.data.mysql

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
}
