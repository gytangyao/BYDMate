package com.bydmate.app.data.mysql

import com.bydmate.app.BuildConfig
import com.bydmate.app.data.autoservice.ChargingReading
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.diParsData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class CarStateRawJsonTest {

    private fun fullSample(): DiParsData = diParsData(
        soc = 57, speed = 42, mileage = 25215.4, power = 12.5, chargeGunState = 1,
        maxBatTemp = 31, avgBatTemp = 28, minBatTemp = 25, chargingStatus = 2,
        batteryCapacityKwh = 10.08, totalElecConsumption = 1234.5, voltage12v = 13.8,
        maxCellVoltage = 3.42, minCellVoltage = 3.39, exteriorTemp = 17, gear = 4,
        powerState = 2, insideTemp = 22, acStatus = 1, acTemp = 23, fanLevel = 3,
        acCirc = 1, doorFL = 0, doorFR = 0, doorRL = 1, doorRR = 0, windowFL = 0,
        windowFR = 10, windowRL = 0, windowRR = 0, sunroof = 0, trunk = 2, hood = 0,
        seatbeltFL = 1, lockFL = 2, tirePressFL = 250, tirePressFR = 248,
        tirePressRL = 245, tirePressRR = 246, driveMode = 3, workMode = 1,
        autoPark = 0, rain = 0, lightLow = 0, drl = 1, turnSignal = 2,
    ).copy(
        acDefrostFront = 1, acWindMode = 5, acCtrlMode = 0, seatHeatDriver = 2,
        seatVentDriver = 0, seatHeatPassenger = 1, seatVentPassenger = 0,
        lightSide = 1, lightHigh = 0, seatbeltFR = 1, occupancyFL = 2,
        occupancyFR = 1, occupancyRL = 1, occupancyRM = 1, occupancyRR = 2,
        lightLevel = 3, keyBatteryStatus = 0, wiperRelay = 0, autoWipers = 1,
        bmsState = 1, insulationKohm = 550, motorTempFront = 45, motorTempRear = 46,
        inverterTempFront = 40, inverterTempRear = 41, hvVoltage = 380,
        hvCurrent = -12.5, batteryPowerW = -4750.0, bmsMaxChargeKw = 60.0,
        bmsMaxDischargeAllowKw = 150.0, bmsMaxDischargeKw = 160,
        motorRpmFront = 3000, motorRpmRear = 0, motorCurrentFront = 25.5f,
        motorCurrentRear = 0f, compressorW = 800, tyreTempFL = 25, tyreTempFR = 26,
        tyreTempRL = 24, tyreTempRR = 24, pedalAccel = 10, pedalBrake = 0,
        frontTrunk = 0, chargerConnectState = 1, chargeConnectIndicator = 1,
        batteryRemainKwh = 5.8, driveModeTarget = 1, seatbeltRL = 1, seatbeltRM = 0,
        seatbeltRR = 1, rearBeltSettingRL = 1, rearBeltSettingRM = 0,
        rearBeltSettingRR = 1, fuelPercent = 50, fuelRangeKm = 400, fuelTotalL = 123.4,
        mileageHevKm = 1000, mileageEvKm = 24000, engineRpm = 0, evRangeKm = 310,
    )

    private fun declaredFieldCount(): Int =
        DiParsData::class.java.declaredFields.count { !Modifier.isStatic(it.modifiers) }

    @Test
    fun `every field the poller has reaches the document`() {
        val json = JSONObject(
            CarStateRawJson.build(
                data = fullSample(),
                pushedAt = 1_700_000_000_000L,
                sohPct = 96.4,
                vehicleKind = "EV",
                location = CarStateRawJson.CarLocationSnapshot(
                    latitude = 55.75,
                    longitude = 37.61,
                    ageMs = 2_000L,
                    live = true,
                    accuracyM = 8.0,
                    headingDeg = 90.0,
                    speedMps = 12.0,
                    altitudeM = 150.0,
                ),
            ),
        )

        assertEquals(CarStateRawJson.VERSION, json.getInt("v"))
        assertEquals(1_700_000_000_000L, json.getLong("pushed_at"))
        assertEquals(BuildConfig.VERSION_NAME, json.getString("app_version"))
        assertEquals("EV", json.getString("vehicle_kind"))
        assertEquals(96.4, json.getDouble("soh_pct"), 1e-9)
        val gps = json.getJSONObject("gps")
        assertEquals(55.75, gps.getDouble("lat"), 1e-9)
        assertEquals(37.61, gps.getDouble("lon"), 1e-9)
        assertEquals(2_000L, gps.getLong("age_ms"))
        assertTrue(gps.getBoolean("live"))
        // One key per non-null DiParsData property plus the six document keys.
        assertEquals(6 + declaredFieldCount(), json.length())
    }

    @Test
    fun `keys follow the typed columns and carry their units`() {
        val json = JSONObject(
            CarStateRawJson.build(fullSample(), 1L, sohPct = null, vehicleKind = null),
        )

        assertEquals(57, json.getInt("soc_pct"))
        assertEquals(42, json.getInt("speed_kmh"))
        assertEquals(25215.4, json.getDouble("odometer_km"), 1e-9)
        assertEquals(380, json.getInt("hv_voltage_v"))
        assertEquals(-12.5, json.getDouble("hv_current_a"), 1e-9)
        assertEquals(250, json.getInt("tire_press_fl_kpa"))
        assertEquals(25, json.getInt("tyre_temp_fl_c"))
        assertEquals(24, json.getInt("tyre_temp_rl_c"))
        assertEquals(0.0, json.getDouble("motor_current_rear_a"), 1e-9)
    }

    @Test
    fun `nulls are omitted instead of written as zero`() {
        val json = JSONObject(
            CarStateRawJson.build(
                data = diParsData(soc = 50),
                pushedAt = 2L,
                sohPct = null,
                vehicleKind = null,
            ),
        )

        // v, pushed_at and app_version remain; soh and vehicle kind were not known.
        assertEquals(3 + 1, json.length())
        assertEquals(50, json.getInt("soc_pct"))
        assertFalse(json.has("speed_kmh"))
        assertFalse(json.has("soh_pct"))
        assertFalse(json.has("vehicle_kind"))
    }

    @Test
    fun `a zero remaining-energy placeholder with a non-empty pack is not shipped`() {
        val json = JSONObject(
            CarStateRawJson.build(
                data = fullSample().copy(soc = 73, batteryRemainKwh = 0.0),
                pushedAt = 3L,
                sohPct = null,
                vehicleKind = null,
            ),
        )
        // Every one of 1820 samples read 0.00 kWh at SOC > 0: the BMS has no such reading.
        assertFalse(json.has("battery_remain_kwh"))

        val empty = JSONObject(
            CarStateRawJson.build(
                data = fullSample().copy(soc = 0, batteryRemainKwh = 0.0),
                pushedAt = 4L,
                sohPct = null,
                vehicleKind = null,
            ),
        )
        assertEquals(0.0, empty.getDouble("battery_remain_kwh"), 1e-9)
    }

    /**
     * A running charge has no ChargeEntity yet, so these are the only rows the phone can show
     * until the gun comes out. Both halves ride in one block: the live fids and the probe.
     */
    @Test
    fun `charge session block carries the live fids and the probe`() {
        val json = JSONObject(
            CarStateRawJson.build(
                diParsData(soc = 50), 4L, sohPct = null, vehicleKind = null,
                chargeSession = CarStateRawJson.ChargeSessionSnapshot(
                    live = ChargingReading(
                        gunConnectState = 2, chargingType = 1, chargeBatteryVoltV = 381,
                        batteryType = 1, chargingCapacityKwh = 12.3f, bmsState = 1,
                        readAtMs = 4L, chargerConnectState = 1, chargeConnectIndicator = 2,
                    ),
                    probeStartTs = 100L, probeLastTickTs = 200L, probeLastSoc = 55,
                    probeLastRiseTs = 190L, probePeakKw = 11.4,
                    probeTempMinC = 27.0, probeTempMaxC = 31.0, probeTempAvgC = 29.0,
                ),
            ),
        )

        val session = json.getJSONObject("charge_session")
        assertEquals(2, session.getInt("gun_connect_state"))
        assertEquals(12.3, session.getDouble("charging_capacity_kwh"), 1e-6)
        assertEquals(2, session.getInt("charge_connect_indicator"))
        assertEquals(4L, session.getLong("read_at"))
        assertEquals(100L, session.getLong("probe_start_ts"))
        assertEquals(55, session.getInt("probe_last_soc_pct"))
        assertEquals(11.4, session.getDouble("probe_peak_kw"), 1e-9)
        assertEquals(29.0, session.getDouble("probe_temp_avg_c"), 1e-9)
    }

    /** With neither a probe nor a live read there is nothing to say; an empty object is noise. */
    @Test
    fun `an empty charge session block is omitted`() {
        val json = JSONObject(
            CarStateRawJson.build(
                diParsData(soc = 50), 5L, sohPct = null, vehicleKind = null,
                chargeSession = CarStateRawJson.ChargeSessionSnapshot(),
            ),
        )
        assertFalse(json.has("charge_session"))
    }

    /** The open trip: anchors only, so the phone can show distance and SOC use before it closes. */
    @Test
    fun `trip live block carries both ends of the running trip`() {
        val json = JSONObject(
            CarStateRawJson.build(
                diParsData(soc = 50), 6L, sohPct = null, vehicleKind = null,
                tripLive = CarStateRawJson.TripLiveSnapshot(
                    startTs = 1_000L, startSoc = 81, startMileageKm = 25_000.0,
                    startTotalElecKwh = 1_234.5, startExteriorTempC = 17,
                    lastTickTs = 2_000L, lastSoc = 78, lastMileageKm = 25_012.5,
                    lastTotalElecKwh = 1_236.4,
                ),
            ),
        )

        val trip = json.getJSONObject("trip_live")
        assertEquals(1_000L, trip.getLong("start_ts"))
        assertEquals(81, trip.getInt("start_soc_pct"))
        assertEquals(25_012.5, trip.getDouble("last_mileage_km"), 1e-9)
        assertFalse("a DM-i-only anchor is absent on an EV", trip.has("start_fuel_total_l"))
    }

    /** One-shot fids and head-unit facts are namespaced, not flattened into the car's own keys. */
    @Test
    fun `extra fids and diagnostics travel in their own blocks`() {
        val json = JSONObject(
            CarStateRawJson.build(
                diParsData(soc = 50), 7L, sohPct = null, vehicleKind = null,
                extraFids = mapOf("bsdLeft" to 0.0, "lifetimeAvgPhm" to 152.4),
                diagnostics = CarStateRawJson.DiagnosticsSnapshot(
                    adbVerdict = "OK", uptimeSeconds = 3_600L, heapUsedMb = 64L, heapMaxMb = 256L,
                    storageFreeMb = 12_000L, storageTotalMb = 32_000L, networkType = "wifi",
                    androidRelease = "14", sdkInt = 34, deviceModel = "BYD",
                    lastSyncTs = 999L,
                ),
            ),
        )

        val extra = json.getJSONObject("extra_fids")
        assertEquals(0.0, extra.getDouble("bsdLeft"), 1e-9)
        assertEquals(152.4, extra.getDouble("lifetimeAvgPhm"), 1e-9)
        val diag = json.getJSONObject("diag")
        assertEquals("OK", diag.getString("adb_verdict"))
        assertEquals(3_600L, diag.getLong("uptime_s"))
        assertEquals("wifi", diag.getString("network"))
        assertEquals("14", diag.getString("android"))
        assertEquals(999L, diag.getLong("last_sync_ts"))
        assertFalse(json.has("bsdLeft"))
    }

    @Test
    fun `a non-finite reading never becomes invalid JSON`() {
        val data = diParsData(power = Double.NaN).copy(
            hvCurrent = Double.POSITIVE_INFINITY,
            batteryPowerW = Double.NEGATIVE_INFINITY,
            motorCurrentFront = Float.NaN,
        )

        val json = JSONObject(CarStateRawJson.build(data, 3L, sohPct = null, vehicleKind = null))

        assertFalse(json.has("power_kw"))
        assertFalse(json.has("hv_current_a"))
        assertFalse(json.has("battery_power_w"))
        assertFalse(json.has("motor_current_front_a"))
        assertTrue(json.has("v"))
    }
}
