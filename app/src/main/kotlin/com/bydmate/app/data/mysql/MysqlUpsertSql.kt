package com.bydmate.app.data.mysql

import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TripEntity

/**
 * Live dashboard reading the phone shows as "current state". Field-for-field the columns of
 * the `car_state` table. The car builds one of these from its in-memory [com.bydmate.app.data.remote.DiParsData]
 * each sync tick — SoH is not a Room column, so the caller supplies the newest computed value.
 */
data class CarStateSnapshot(
    val pushedAt: Long,
    val socPct: Double?,
    val rangeKm: Double?,
    val sohPct: Double?,
    val battTempC: Double?,
    val cellVMax: Double?,
    val cellVMin: Double?,
    val voltage12v: Double?,
    val exteriorTempC: Double?,
    val cabinTempC: Double?,
    val odometerKm: Double?,
    val totals: ChargingTotals,
    val rawJson: String?,
) {
    val lifetimeChargedKwh: Double? get() = totals.lifetimeKwh
    val chargedScope: String? get() = totals.scope
    val chargeCycles: Double? get() = totals.cycles
    val chargeCapacityKwh: Double? get() = totals.capacityKwh
}

/**
 * Maps a Room row onto the flat, JDBC-friendly column list the shared MySQL tables expect, and
 * renders the `INSERT … ON DUPLICATE KEY UPDATE` statement. Kept pure (no DB) so the exact
 * column ↔ value ↔ placeholder alignment is unit-testable without a live connection.
 */
internal object MysqlUpsertSql {

    /** The `app_logs.msg` column is VARCHAR(2048); a longer tail belongs to the next line. */
    const val MAX_LOG_MSG_CHARS = 2000

    fun trips(entity: TripEntity, vehicleId: String, now: Long): Upsert = Upsert(
        table = "trips",
        columns = listOf(
            "id", "vehicle_id", "start_ts", "end_ts", "distance_km", "kwh_consumed",
            "kwh_per_100km", "soc_start", "soc_end", "temp_avg_c", "avg_speed_kmh",
            "bat_temp_avg", "bat_temp_max", "bat_temp_min", "cost", "exterior_temp",
            "exterior_temp_end", "odometer_start_km", "odometer_end_km", "source", "byd_id",
            "fuel_liters", "hev_km", "fuel_total_start", "fuel_total_end",
            "mileage_hev_start", "mileage_hev_end", "fuel_cost", "row_version", "updated_at",
        ),
        values = listOf(
            entity.id, vehicleId, entity.startTs, entity.endTs, entity.distanceKm,
            entity.kwhConsumed, entity.kwhPer100km, entity.socStart, entity.socEnd,
            entity.tempAvgC, entity.avgSpeedKmh, entity.batTempAvg, entity.batTempMax,
            entity.batTempMin, entity.cost, entity.exteriorTemp, entity.exteriorTempEnd,
            entity.odometerStartKm, entity.odometerEndKm, entity.source, entity.bydId,
            entity.fuelLiters, entity.hevKm, entity.fuelTotalStart, entity.fuelTotalEnd,
            entity.mileageHevStart, entity.mileageHevEnd, entity.fuelCost, 1L, now,
        ),
        // PK columns (vehicle_id, id) are never in the update set; row_version self-increments so
        // the phone can tell a re-pushed edit from a first insert.
        key = listOf("vehicle_id", "id"),
    )

    fun charges(entity: ChargeEntity, vehicleId: String, now: Long): Upsert = Upsert(
        table = "charges",
        columns = listOf(
            "id", "vehicle_id", "start_ts", "end_ts", "soc_start", "soc_end", "kwh_charged",
            "kwh_charged_soc", "max_power_kw", "avg_power_kw", "type", "cost", "lat", "lon",
            "bat_temp_avg", "bat_temp_max", "bat_temp_min", "status", "cell_voltage_min",
            "cell_voltage_max", "voltage_12v", "exterior_temp", "merged_count",
            "lifetime_kwh_at_start", "lifetime_kwh_at_finish", "gun_state", "detection_source",
            "meter_kwh", "cost_manual", "row_version", "updated_at",
        ),
        values = listOf(
            entity.id, vehicleId, entity.startTs, entity.endTs, entity.socStart, entity.socEnd,
            entity.kwhCharged, entity.kwhChargedSoc, entity.maxPowerKw, entity.avgPowerKw,
            entity.type, entity.cost, entity.lat, entity.lon, entity.batTempAvg, entity.batTempMax,
            entity.batTempMin, entity.status, entity.cellVoltageMin, entity.cellVoltageMax,
            entity.voltage12v, entity.exteriorTemp, entity.mergedCount, entity.lifetimeKwhAtStart,
            entity.lifetimeKwhAtFinish, entity.gunState, entity.detectionSource, entity.meterKwh,
            if (entity.costManual) 1L else 0L, 1L, now,
        ),
        key = listOf("vehicle_id", "id"),
    )

    fun batterySnapshots(entity: BatterySnapshotEntity, vehicleId: String, now: Long): Upsert = Upsert(
        table = "battery_snapshots",
        columns = listOf(
            "id", "vehicle_id", "timestamp", "odometer_km", "soc_start", "soc_end",
            "kwh_charged", "calculated_capacity_kwh", "soh_percent", "cell_delta_v",
            "bat_temp_avg", "charge_id", "row_version", "updated_at",
        ),
        values = listOf(
            entity.id, vehicleId, entity.timestamp, entity.odometerKm, entity.socStart,
            entity.socEnd, entity.kwhCharged, entity.calculatedCapacityKwh, entity.sohPercent,
            entity.cellDeltaV, entity.batTempAvg, entity.chargeId, 1L, now,
        ),
        key = listOf("vehicle_id", "id"),
    )

    /** car_state is append-only (AUTO_INCREMENT id), so no ON DUPLICATE — a plain INSERT. */
    fun carState(s: CarStateSnapshot, vehicleId: String): Upsert = Upsert(
        table = "car_state",
        columns = listOf(
            "vehicle_id", "pushed_at", "soc_pct", "range_km", "soh_pct", "batt_temp_c",
            "cell_v_max", "cell_v_min", "voltage_12v", "exterior_temp_c", "cabin_temp_c",
            "odometer_km", "lifetime_charged_kwh", "charged_scope", "charge_cycles",
            "charge_capacity_kwh", "raw",
        ),
        values = listOf(
            vehicleId, s.pushedAt, s.socPct, s.rangeKm, s.sohPct, s.battTempC,
            s.cellVMax, s.cellVMin, s.voltage12v, s.exteriorTempC, s.cabinTempC,
            s.odometerKm, s.lifetimeChargedKwh, s.chargedScope, s.chargeCycles,
            s.chargeCapacityKwh, s.rawJson,
        ),
        key = null,
    )

    /**
     * One log line into `app_logs`. Append-only with an auto-increment id, so [Upsert] renders a
     * plain INSERT (null [Upsert.key]) and a re-sent line simply becomes a duplicate row — the
     * writer's timestamp cursor is what prevents that, not the database.
     */
    fun log(record: MysqlLogRecord, vehicleId: String, source: String): Upsert = Upsert(
        table = "app_logs",
        columns = listOf("vehicle_id", "source", "ts", "level", "tag", "msg", "run_id"),
        values = listOf(
            vehicleId, source, record.ts, record.level, record.tag,
            record.msg.take(MAX_LOG_MSG_CHARS), record.runId.ifEmpty { null },
        ),
        key = null,
    )

    /**
     * The rolling delete that keeps `app_logs` to [retentionDays] of history.
     *
     * The cutoff is the **server's** clock (`UNIX_TIMESTAMP()`), not the head unit's. A DiLink that
     * boots before its RTC is corrected reports a date weeks or years off, and a client-computed
     * cutoff would then either delete the whole table or nothing at all — silently, and only
     * discovered after the fact.
     *
     * Deliberately no `source` filter: the phone writes its own lines into this table and never
     * deletes them, so this vehicle's rows are pruned for both writers by the one app that has a
     * schedule. The `vehicle_id` filter stays, so a second car on this hub keeps its own logs.
     */
    fun logPrune(vehicleId: String, retentionDays: Int, limit: Int): String =
        "DELETE FROM `app_logs` WHERE `vehicle_id` = ${SqlLiteral.of(vehicleId)} " +
            "AND `ts` < (UNIX_TIMESTAMP() - ${retentionDays.toLong() * 86_400L}) * 1000 " +
            "LIMIT $limit"
}

/**
 * One statement ready to send as a literal string. [values] lines up one-for-one with [columns];
 * null means SQL NULL. [key] is empty/null for append-only tables (car_state).
 */
data class Upsert(
    val table: String,
    val columns: List<String>,
    val values: List<Any?>,
    val key: List<String>?,
) {
    init {
        require(columns.size == values.size) {
            "$table: ${columns.size} columns but ${values.size} values"
        }
    }

    /** This row alone. See [render] for why the SQL carries literals instead of `?`. */
    fun render(): String = render(listOf(this))

    companion object {
        /**
         * `INSERT INTO t (a, b) VALUES (…), (…) ON DUPLICATE KEY UPDATE a=VALUES(a), …` with
         * `row_version` self-incremented.
         *
         * Values are inlined as literals because a batch of them is the only way to keep one
         * statement per table: [java.sql.PreparedStatement] cannot even be loaded on Android (see
         * [SqlLiteral]), and re-opening a statement per row would multiply round trips on the one
         * session this process is allowed to hold.
         *
         * Every row must share the column list, which the callers guarantee by building the batch
         * from one [MysqlUpsertSql] function.
         */
        fun render(rows: List<Upsert>): String {
            require(rows.isNotEmpty()) { "nothing to render" }
            val first = rows.first()
            require(rows.all { it.table == first.table && it.columns == first.columns }) {
                "a batch must be one table with one column list"
            }
            val cols = first.columns.joinToString(", ") { "`${it}`" }
            val tuples = rows.joinToString(", ") { u ->
                u.values.joinToString(", ", "(", ")") { SqlLiteral.of(it) }
            }
            val base = "INSERT INTO `${first.table}` ($cols) VALUES $tuples"
            val key = first.key
            if (key.isNullOrEmpty()) return base
            val updates = first.columns
                .filter { it !in key && it != "row_version" }
                .joinToString(", ") { "`$it`=VALUES(`$it`)" }
            return "$base ON DUPLICATE KEY UPDATE $updates, `row_version`=`row_version`+1"
        }
    }
}
