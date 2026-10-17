package com.bydmate.app.data.mysql

import com.bydmate.app.data.charging.AutoserviceChargingDetector
import com.bydmate.app.data.repository.fullCycleCapacityKwh
import com.bydmate.app.data.repository.fullCycleKwh

/**
 * The lifetime charging figures for one `car_state` row.
 *
 * The phone cannot compute these: it reads at most the newest 200 hub rows, while both numbers are
 * lifetime quantities. The car already answers them for its own «Техника» card, so it sends the
 * same values and the phone shows the number the driver just looked at on the head unit.
 *
 * Sources and the SoH-corrected divisor follow [com.bydmate.app.ui.tech.TechPanelVisuals.fullCycles]
 * exactly: the BMS lifetime counter wins, the app's own sum of logged sessions is the fallback, and
 * a car with neither sends nulls rather than a zero that would read as a measurement.
 */
data class ChargingTotals(
    val lifetimeKwh: Double?,
    /** Which source produced [lifetimeKwh], so the phone can label it honestly. */
    val scope: String?,
    val cycles: Double?,
    /** The kWh-per-cycle divisor actually used; a wrong one is visible instead of silent. */
    val capacityKwh: Double?,
) {
    companion object {
        const val SCOPE_BMS = "bms"
        const val SCOPE_APP = "app"
        val EMPTY = ChargingTotals(null, null, null, null)
    }
}

fun chargingTotals(
    bmsLifetimeKwh: Double?,
    appChargedKwh: Double?,
    nominalCapacityKwh: Double,
    sohPercent: Double?,
): ChargingTotals {
    val total = fullCycleKwh(bmsLifetimeKwh, appChargedKwh) ?: return ChargingTotals.EMPTY
    val fromBms = bmsLifetimeKwh != null && bmsLifetimeKwh > 0.0
    val scope = if (fromBms) ChargingTotals.SCOPE_BMS else ChargingTotals.SCOPE_APP
    if (nominalCapacityKwh <= 0.0) return ChargingTotals(total, scope, null, null)
    val soh = sohPercent?.toFloat()?.takeIf {
        it >= AutoserviceChargingDetector.SOH_SANITY_MIN && it <= 100f
    }
    val capacity = fullCycleCapacityKwh(nominalCapacityKwh, soh, fromBms)
    return ChargingTotals(
        lifetimeKwh = total,
        scope = scope,
        cycles = if (capacity > 0.0) total / capacity else null,
        capacityKwh = capacity.takeIf { it > 0.0 },
    )
}
