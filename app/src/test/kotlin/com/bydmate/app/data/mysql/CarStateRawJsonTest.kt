package com.bydmate.app.data.mysql

import com.bydmate.app.BuildConfig
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
