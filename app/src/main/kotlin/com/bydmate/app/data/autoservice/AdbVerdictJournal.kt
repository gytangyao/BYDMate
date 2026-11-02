package com.bydmate.app.data.autoservice

import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last ADB-channel verdict, kept in prefs so it can reach the hub without the data path.
 *
 * The verdict is the answer to "why is there no car data", and until now it only travelled inside
 * `car_state.raw` — a row that by definition stops being written when the channel it describes dies.
 * On 2026-10-28 a reboot left the head unit with wireless debugging off for four days: no
 * `car_state` rows at all, so the reason travelled nowhere, and the phone rendered a five-day-old
 * snapshot as if it were live.
 *
 * This is the same escape route [com.bydmate.app.data.charging.CatchUpJournal] uses for the charging
 * verdicts the WARN threshold cannot deliver: prefs hold the fact, and
 * [com.bydmate.app.data.mysql.CarLogCollector] ships it as a `marker` row on the WorkManager
 * schedule — the one path that keeps running while autoservice is unreadable, because it opens no
 * ADB connection and needs no sample.
 */
@Singleton
class AdbVerdictJournal @Inject constructor(
    private val settings: SettingsRepository,
) {
    /** [line] is the whole state except when it started; equal lines are the same fact, so nothing re-ships. */
    data class Entry(val line: String, val sinceMs: Long)

    /**
     * Records a published verdict. Called on every recompute, which is why it compares before
     * writing: a minute-level heartbeat must not turn into a minute-level prefs write.
     */
    suspend fun record(line: String, nowMs: Long) {
        if (line == settings.getString(SettingsRepository.KEY_ADB_VERDICT_LINE, "")) return
        settings.setStrings(
            mapOf(
                SettingsRepository.KEY_ADB_VERDICT_LINE to line,
                SettingsRepository.KEY_ADB_VERDICT_SINCE to nowMs.toString(),
            )
        )
    }

    /** Null until the first verdict after the start gate. */
    suspend fun entry(): Entry? {
        val line = settings.getString(SettingsRepository.KEY_ADB_VERDICT_LINE, "")
        if (line.isEmpty()) return null
        return Entry(line = line, sinceMs = settings.getString(SettingsRepository.KEY_ADB_VERDICT_SINCE, "0").toLongOrNull() ?: 0L)
    }

    companion object {
        /**
         * One field per input of [evaluateAdbVerdict], so the hub row says the same thing the
         * Dashboard header says — plus `model`, because `app_logs` has no device column and a test
         * rig pushes to the same `vehicle_id` as the car.
         */
        @Suppress("LongParameterList") // one parameter per input of the verdict table
        fun line(
            verdict: AdbVerdict,
            daemonHealthy: Boolean,
            adbConnected: Boolean,
            restoreState: Any,
            adbWifiEnabled: Int,
            daemonEverAlive: Boolean,
            deviceModel: String,
        ): String = buildString {
            append("verdict=").append(verdict.name)
            append(" healthy=").append(flag(daemonHealthy))
            append(" connected=").append(flag(adbConnected))
            append(" restore=").append(restoreState.toString())
            append(" wifi=").append(adbWifiEnabled)
            append(" everAlive=").append(flag(daemonEverAlive))
            append(" model=").append(deviceModel.trim().ifEmpty { "unknown" })
        }

        private fun flag(value: Boolean): String = if (value) "1" else "0"
    }
}
