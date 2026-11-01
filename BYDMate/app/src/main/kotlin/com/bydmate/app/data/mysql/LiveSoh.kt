package com.bydmate.app.data.mysql

import com.bydmate.app.data.charging.AutoserviceChargingDetector

/**
 * The figure the hub's `car_state.soh_pct` carries.
 *
 * The BMS answers this live, so a car that has never finished a charge still has a health number to
 * publish. The previous source — the newest `battery_snapshots` row — only exists after a session that
 * raised SOC by five points, which left the phone's battery field blank on a car with months of
 * driving behind it.
 *
 * The snapshot stays as the fallback because `soh` is one of the fids that returns a sentinel during
 * the BMS recalibration after a full charge, and a dashboard push should not blank the phone for
 * those seconds.
 *
 * Both candidates go through the plausibility window the SOC→kWh conversion already uses: below
 * [AutoserviceChargingDetector.SOH_SANITY_MIN] a number is a sentinel leftover or another pack's, and
 * above 100 it is not a percentage. The phone renders this as a health figure, so garbage there reads
 * as a dying battery — a missing value shows as a dash instead.
 */
fun resolveSohPct(liveBmsSohPercent: Float?, newestSnapshotSohPercent: Double?): Double? =
    liveBmsSohPercent?.toDouble().plausibleSoh() ?: newestSnapshotSohPercent.plausibleSoh()

private fun Double?.plausibleSoh(): Double? =
    this?.takeIf { it in AutoserviceChargingDetector.SOH_SANITY_MIN..SOH_MAX_PCT }

private const val SOH_MAX_PCT = 100.0
