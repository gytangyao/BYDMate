package com.bydmate.app.data.mysql

import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CarStateFingerprintTest {

    @Test
    fun `identical samples produce the same fingerprint`() {
        val a = diParsData(soc = 62, speed = 40, mileage = 12_345.6, doorFL = 0, lockFL = 2)
        val b = diParsData(soc = 62, speed = 40, mileage = 12_345.6, doorFL = 0, lockFL = 2)

        assertEquals(CarStateFingerprint.of(a), CarStateFingerprint.of(b))
    }

    @Test
    fun `sensor noise inside one quantum does not count as a change`() {
        val base = diParsData(soc = 62, speed = 40)
            .copy(
                power = 10.10,
                hvCurrent = 12.2,
                maxCellVoltage = 3.201,
                minCellVoltage = 3.180,
                motorRpmFront = 1_234,
                pedalAccel = 31,
                insulationKohm = 1_204,
                compressorW = 1_010,
            )
        val noisy = base.copy(
            power = 10.12,
            hvCurrent = 12.4,
            maxCellVoltage = 3.204,
            minCellVoltage = 3.184,
            motorRpmFront = 1_249,
            pedalAccel = 32,
            insulationKohm = 1_203,
            compressorW = 1_020,
        )

        assertEquals(CarStateFingerprint.of(base), CarStateFingerprint.of(noisy))
    }

    @Test
    fun `a meaningful vehicle change produces a different fingerprint`() {
        val parked = diParsData(soc = 62, speed = 0, doorFL = 0, lockFL = 2)
        val unlocked = parked.copy(lockFL = 1)

        assertNotEquals(CarStateFingerprint.of(parked), CarStateFingerprint.of(unlocked))
    }

    @Test
    fun `soc and odometer steps large enough for the dashboard count as changes`() {
        val base = diParsData(soc = 62, speed = 40, mileage = 12_345.6)

        assertNotEquals(
            CarStateFingerprint.of(base),
            CarStateFingerprint.of(base.copy(soc = 63)),
        )
        assertNotEquals(
            CarStateFingerprint.of(base),
            CarStateFingerprint.of(base.copy(mileage = 12_345.7)),
        )
    }

    @Test
    fun `gps jitter under ~11 m stays the same fingerprint`() {
        val car = diParsData(soc = 62)

        assertEquals(
            CarStateFingerprint.of(car, latitude = 31.230416, longitude = 121.473701, locationLive = true),
            CarStateFingerprint.of(car, latitude = 31.230419, longitude = 121.473704, locationLive = true),
        )
    }

    @Test
    fun `a real move or a live-to-seed change produces a different fingerprint`() {
        val car = diParsData(soc = 62)
        val here = CarStateFingerprint.of(car, 31.2304, 121.4737, locationLive = true)

        assertNotEquals(here, CarStateFingerprint.of(car, 31.2315, 121.4737, locationLive = true))
        assertNotEquals(here, CarStateFingerprint.of(car, 31.2304, 121.4737, locationLive = false))
    }
}
