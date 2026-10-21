package com.bydmate.app.data.charging

import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last-known autoservice state snapshot so that runCatchUp can
 * detect SOC / capacity changes across DiLink power-cycles.
 *
 * Replaces ChargingBaselineStore (v2.4.16 and earlier used lifetime_kwh as the
 * baseline signal; v2.4.17 cascade uses SOC + per-session chargingCapacityKwh).
 */
@Singleton
class ChargingStateStore @Inject constructor(
    private val settings: SettingsRepository
) {
    data class State(
        val socPercent: Int?,
        val mileageKm: Float?,
        val capacityKwh: Float?,
        val ts: Long
    )

    suspend fun load(): State = State(
        socPercent = settings.getChargingBaselineSoc(),
        mileageKm = settings.getLastMileageKm(),
        capacityKwh = settings.getLastCapacityKwh(),
        ts = settings.getLastStateTs()
    )

    suspend fun save(socPercent: Int?, mileageKm: Float?, capacityKwh: Float?, ts: Long) {
        // Single transactional write: a process kill mid-save must never leave
        // a mixed anchor (new mileage + stale SOC → false odometerMoved at the
        // next catch-up) — audit 2026-06-11.
        val values = buildMap {
            socPercent?.let { put(SettingsRepository.KEY_CHARGING_BASELINE_SOC, it.toString()) }
            put(SettingsRepository.KEY_LAST_MILEAGE_KM, mileageKm?.toString() ?: "")
            put(SettingsRepository.KEY_LAST_CAPACITY_KWH, capacityKwh?.toString() ?: "")
            put(SettingsRepository.KEY_LAST_STATE_TS, ts.toString())
        }
        settings.setStrings(values)
    }

    /** True when a charge session was observed in progress (gun connected) and
     *  has not yet been reconstructed or dismissed. */
    suspend fun loadChargePending(): Boolean = settings.getChargePending()

    suspend fun setChargePending(pending: Boolean) = settings.setChargePending(pending)

    /**
     * The trail a plugged-in gun leaves while the session runs. The row is written at the
     * disconnect, possibly twelve hours after the plug went in, and an overnight charge
     * produces no samples of its own (the head unit sleeps) — so each catch-up tick folds
     * what it read into this probe, and [AutoserviceChargingDetector] opens the row with
     * these bounds instead of "somewhere before now".
     *
     * [lastRiseTs] is the end of energy transfer, which is not the unplug: a car left on
     * the charger after reaching 100% would otherwise report the whole night as charging.
     */
    data class SessionProbe(
        val startTs: Long,
        val lastTickTs: Long,
        val lastSoc: Int,
        val lastRiseTs: Long,
        val peakKw: Double? = null,
        val tempMinC: Double? = null,
        val tempMaxC: Double? = null,
        val tempSumC: Double? = null,
        val tempCount: Int = 0,
    ) {
        /** Mean of the sampled average battery temperatures; null when never sampled. */
        val tempAvgC: Double?
            get() = tempSumC?.takeIf { tempCount > 0 }?.div(tempCount)
    }

    suspend fun loadProbe(): SessionProbe? {
        val raw = settings.getString(SettingsRepository.KEY_CHARGING_SESSION_PROBE, "")
        if (raw.isEmpty()) return null
        val f = raw.split('|')
        if (f.size != FIELD_COUNT) return null
        return SessionProbe(
            startTs = f[0].toLongOrNull() ?: return null,
            lastTickTs = f[1].toLongOrNull() ?: return null,
            lastSoc = f[2].toIntOrNull() ?: return null,
            lastRiseTs = f[3].toLongOrNull() ?: return null,
            peakKw = f[4].toDoubleOrNull(),
            tempMinC = f[5].toDoubleOrNull(),
            tempMaxC = f[6].toDoubleOrNull(),
            tempSumC = f[7].toDoubleOrNull(),
            tempCount = f[8].toIntOrNull() ?: return null,
        )
    }

    suspend fun saveProbe(probe: SessionProbe) {
        val encoded = listOf(
            probe.startTs.toString(),
            probe.lastTickTs.toString(),
            probe.lastSoc.toString(),
            probe.lastRiseTs.toString(),
            probe.peakKw?.toString() ?: "",
            probe.tempMinC?.toString() ?: "",
            probe.tempMaxC?.toString() ?: "",
            probe.tempSumC?.toString() ?: "",
            probe.tempCount.toString(),
        ).joinToString("|")
        settings.setString(SettingsRepository.KEY_CHARGING_SESSION_PROBE, encoded)
    }

    suspend fun clearProbe() =
        settings.setString(SettingsRepository.KEY_CHARGING_SESSION_PROBE, "")

    private companion object {
        const val FIELD_COUNT = 9
    }
}
