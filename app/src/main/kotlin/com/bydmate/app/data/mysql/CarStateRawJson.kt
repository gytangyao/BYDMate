package com.bydmate.app.data.mysql

import com.bydmate.app.BuildConfig
import com.bydmate.app.data.remote.DiParsData
import org.json.JSONObject

/**
 * One live sample as a self-contained JSON document for `car_state.raw`.
 *
 * The table's typed columns are a dashboard subset: the phone reads them directly and they are
 * cheap to query. Everything else the car already polls every second would otherwise be lost
 * between pushes, so the same sample is written here in full. Adding a field to the wire format
 * needs no schema change — the JSON column was created for exactly this — only a consumer that
 * reads the new key.
 *
 * Nulls are omitted rather than written: a missing key means "the car did not answer", never zero.
 * Keys are snake_case with units, and follow the typed column names where one exists.
 */
internal object CarStateRawJson {

    /** Bump when a key changes meaning or is removed; consumers can branch on it. */
    const val VERSION = 1

    /** The latest OS fix, copied out of [android.location.Location] so this file stays JVM-testable. */
    data class CarLocationSnapshot(
        val latitude: Double,
        val longitude: Double,
        val ageMs: Long,
        val live: Boolean,
        val accuracyM: Double? = null,
        val headingDeg: Double? = null,
        val speedMps: Double? = null,
        val altitudeM: Double? = null,
    )

    fun build(
        data: DiParsData,
        pushedAt: Long,
        sohPct: Double?,
        vehicleKind: String?,
        location: CarLocationSnapshot? = null,
    ): String = JSONObject().apply {
        put("v", VERSION)
        put("pushed_at", pushedAt)
        put("app_version", BuildConfig.VERSION_NAME)
        vehicleKind?.let { put("vehicle_kind", it) }
        num("soh_pct", sohPct)
        location?.let { fix ->
            put(
                "gps",
                JSONObject().apply {
                    put("lat", fix.latitude)
                    put("lon", fix.longitude)
                    put("age_ms", fix.ageMs)
                    put("live", fix.live)
                    num("accuracy_m", fix.accuracyM)
                    num("heading_deg", fix.headingDeg)
                    num("speed_mps", fix.speedMps)
                    num("altitude_m", fix.altitudeM)
                },
            )
        }

        // Battery, charging, energy
        num("soc_pct", data.soc)
        num("speed_kmh", data.speed)
        num("odometer_km", data.mileage)
        num("power_kw", data.power)
        num("charge_gun_state", data.chargeGunState)
        num("bat_temp_max_c", data.maxBatTemp)
        num("bat_temp_avg_c", data.avgBatTemp)
        num("bat_temp_min_c", data.minBatTemp)
        num("charging_status", data.chargingStatus)
        num("battery_capacity_kwh", data.batteryCapacityKwh)
        num("total_elec_consumption_kwh", data.totalElecConsumption)
        num("voltage_12v", data.voltage12v)
        num("cell_v_max", data.maxCellVoltage)
        num("cell_v_min", data.minCellVoltage)
        num("exterior_temp_c", data.exteriorTemp)
        num("battery_remain_kwh", data.batteryRemainKwh)
        num("bms_state", data.bmsState)
        num("insulation_kohm", data.insulationKohm)
        num("hv_voltage_v", data.hvVoltage)
        num("hv_current_a", data.hvCurrent)
        num("battery_power_w", data.batteryPowerW)
        num("bms_max_charge_kw", data.bmsMaxChargeKw)
        num("bms_max_discharge_allow_kw", data.bmsMaxDischargeAllowKw)
        num("bms_max_discharge_kw", data.bmsMaxDischargeKw)
        num("charger_connect_state", data.chargerConnectState)
        num("charge_connect_indicator", data.chargeConnectIndicator)

        // Drivetrain
        num("gear", data.gear)
        num("power_state", data.powerState)
        num("drive_mode", data.driveMode)
        num("drive_mode_target", data.driveModeTarget)
        num("work_mode", data.workMode)
        num("auto_park", data.autoPark)
        num("motor_temp_front_c", data.motorTempFront)
        num("motor_temp_rear_c", data.motorTempRear)
        num("inverter_temp_front_c", data.inverterTempFront)
        num("inverter_temp_rear_c", data.inverterTempRear)
        num("motor_rpm_front", data.motorRpmFront)
        num("motor_rpm_rear", data.motorRpmRear)
        num("motor_current_front_a", data.motorCurrentFront)
        num("motor_current_rear_a", data.motorCurrentRear)
        num("pedal_accel_pct", data.pedalAccel)
        num("pedal_brake_pct", data.pedalBrake)

        // Climate
        num("cabin_temp_c", data.insideTemp)
        num("ac_status", data.acStatus)
        num("ac_setpoint_c", data.acTemp)
        num("fan_level", data.fanLevel)
        num("ac_recirculation", data.acCirc)
        num("ac_defrost_front", data.acDefrostFront)
        num("ac_wind_mode", data.acWindMode)
        num("ac_ctrl_mode", data.acCtrlMode)
        num("seat_heat_driver", data.seatHeatDriver)
        num("seat_vent_driver", data.seatVentDriver)
        num("seat_heat_passenger", data.seatHeatPassenger)
        num("seat_vent_passenger", data.seatVentPassenger)
        num("compressor_w", data.compressorW)

        // Body
        num("door_fl", data.doorFL)
        num("door_fr", data.doorFR)
        num("door_rl", data.doorRL)
        num("door_rr", data.doorRR)
        num("window_fl_pct", data.windowFL)
        num("window_fr_pct", data.windowFR)
        num("window_rl_pct", data.windowRL)
        num("window_rr_pct", data.windowRR)
        num("sunroof_pct", data.sunroof)
        num("trunk", data.trunk)
        num("hood", data.hood)
        num("front_trunk", data.frontTrunk)
        num("lock_fl", data.lockFL)
        num("seatbelt_fl", data.seatbeltFL)
        num("seatbelt_fr", data.seatbeltFR)
        num("seatbelt_rl", data.seatbeltRL)
        num("seatbelt_rm", data.seatbeltRM)
        num("seatbelt_rr", data.seatbeltRR)
        num("rear_belt_setting_rl", data.rearBeltSettingRL)
        num("rear_belt_setting_rm", data.rearBeltSettingRM)
        num("rear_belt_setting_rr", data.rearBeltSettingRR)
        num("occupancy_fl", data.occupancyFL)
        num("occupancy_fr", data.occupancyFR)
        num("occupancy_rl", data.occupancyRL)
        num("occupancy_rm", data.occupancyRM)
        num("occupancy_rr", data.occupancyRR)
        num("tire_press_fl_kpa", data.tirePressFL)
        num("tire_press_fr_kpa", data.tirePressFR)
        num("tire_press_rl_kpa", data.tirePressRL)
        num("tire_press_rr_kpa", data.tirePressRR)
        num("tyre_temp_fl_c", data.tyreTempFL)
        num("tyre_temp_fr_c", data.tyreTempFR)
        num("tyre_temp_rl_c", data.tyreTempRL)
        num("tyre_temp_rr_c", data.tyreTempRR)

        // Lights, wipers, rain
        num("light_low", data.lightLow)
        num("light_high", data.lightHigh)
        num("light_side", data.lightSide)
        num("drl", data.drl)
        num("light_level", data.lightLevel)
        num("turn_signal", data.turnSignal)
        num("rain", data.rain)
        num("wiper_relay", data.wiperRelay)
        num("auto_wipers", data.autoWipers)
        num("key_battery_status", data.keyBatteryStatus)

        // Hybrid
        num("fuel_percent", data.fuelPercent)
        num("fuel_range_km", data.fuelRangeKm)
        num("fuel_total_l", data.fuelTotalL)
        num("mileage_hev_km", data.mileageHevKm)
        num("mileage_ev_km", data.mileageEvKm)
        num("engine_rpm", data.engineRpm)
        num("ev_range_km", data.evRangeKm)
    }.toString()

    private fun JSONObject.num(key: String, value: Int?) {
        if (value != null) put(key, value)
    }

    private fun JSONObject.num(key: String, value: Double?) {
        if (value != null && value.isFinite()) put(key, value)
    }

    private fun JSONObject.num(key: String, value: Float?) {
        if (value != null && value.isFinite()) put(key, value.toDouble())
    }
}
