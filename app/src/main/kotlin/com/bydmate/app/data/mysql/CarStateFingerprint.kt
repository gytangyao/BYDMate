package com.bydmate.app.data.mysql

import com.bydmate.app.data.remote.DiParsData

/**
 * Canonical value of everything a `car_state` row says about the car, used to decide whether the
 * next sample has anything new to send. Two ticks with equal fingerprints would render identical
 * vehicle columns, so the second row is pure duplicated history.
 *
 * Continuous readings are quantized first: a battery voltage that drifts by 1 mV or a motor
 * current that swings by 0.2 A is sensor noise, not a state change worth a hub write. Steps are
 * chosen at the resolution the phone actually displays, while discrete state (doors, locks,
 * windows, gear, temperatures) stays exact.
 *
 * The location is not part of [DiParsData]; the caller passes it separately so a parked car whose
 * GPS jitters by a few metres is not mistaken for a moving one. Four decimal places ≈ 11 m.
 */
internal object CarStateFingerprint {

    fun of(
        data: DiParsData,
        latitude: Double? = null,
        longitude: Double? = null,
        locationLive: Boolean = false,
    ): String {
        val vehicle = data.copy(
            mileage = q(data.mileage, 0.1),
            power = q(data.power, 0.5),
            batteryCapacityKwh = q(data.batteryCapacityKwh, 0.1),
            totalElecConsumption = q(data.totalElecConsumption, 0.1),
            voltage12v = q(data.voltage12v, 0.1),
            maxCellVoltage = q(data.maxCellVoltage, 0.01),
            minCellVoltage = q(data.minCellVoltage, 0.01),
            hvVoltage = q(data.hvVoltage, 1),
            hvCurrent = q(data.hvCurrent, 1.0),
            batteryPowerW = q(data.batteryPowerW, 100.0),
            bmsMaxChargeKw = q(data.bmsMaxChargeKw, 1.0),
            bmsMaxDischargeAllowKw = q(data.bmsMaxDischargeAllowKw, 1.0),
            motorCurrentFront = q(data.motorCurrentFront, 1.0),
            motorCurrentRear = q(data.motorCurrentRear, 1.0),
            fuelTotalL = q(data.fuelTotalL, 0.1),
            batteryRemainKwh = q(data.batteryRemainKwh, 0.1),
            motorRpmFront = q(data.motorRpmFront, 100),
            motorRpmRear = q(data.motorRpmRear, 100),
            compressorW = q(data.compressorW, 50),
            insulationKohm = q(data.insulationKohm, 10),
            pedalAccel = q(data.pedalAccel, 5),
            pedalBrake = q(data.pedalBrake, 5),
            engineRpm = q(data.engineRpm, 100),
        ).toString()
        val lat = latitude?.let { Math.round(it * 10_000) }
        val lon = longitude?.let { Math.round(it * 10_000) }
        return "$vehicle|loc=$lat,$lon,live=$locationLive"
    }

    private fun q(value: Double?, step: Double): Double? =
        value?.let { Math.round(it / step) * step }

    private fun q(value: Float?, step: Double): Float? =
        value?.let { Math.round(it / step) * step.toFloat() }

    private fun q(value: Int?, step: Int): Int? =
        value?.let { Math.round(it.toDouble() / step).toInt() * step }
}
