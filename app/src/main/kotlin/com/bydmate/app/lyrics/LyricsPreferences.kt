package com.bydmate.app.lyrics

import android.content.Context
import android.content.SharedPreferences
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lyrics display settings, in their own SharedPreferences file.
 *
 * The engine re-reads both values on every poll, like [com.bydmate.app.media.ClusterMusicBridge]
 * re-reads the cluster card switch, so turning the feature on or moving it to another port lands
 * without a restart; the settings card writes the same file directly.
 */
@Singleton
class LyricsPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    /** The configured port, clamped: a hand-typed value outside the range would fail to bind. */
    val port: Int get() = prefs.getInt(KEY_PORT, DisplayProtocol.DEFAULT_PORT).coerceIn(MIN_PORT, MAX_PORT)

    companion object {
        const val PREFS_NAME = "lyrics_display"
        const val KEY_ENABLED = "enabled"
        const val KEY_PORT = "port"

        /** Ephemeral-range ports would collide with anything else on the LAN; the top of the
         *  dynamic range is where a router is least likely to hand out an address. */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65_535

        const val DEFAULT_PORT = DisplayProtocol.DEFAULT_PORT

        fun prefsOf(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun setEnabled(prefs: SharedPreferences, on: Boolean) {
            val was = prefs.getBoolean(KEY_ENABLED, false)
            prefs.edit().putBoolean(KEY_ENABLED, on).apply()
            Trace.event(TraceArea.USER, "toggle", "id" to "lyrics_display", "on" to on, "was" to was)
        }

        fun setPort(prefs: SharedPreferences, port: Int) {
            val clamped = port.coerceIn(MIN_PORT, MAX_PORT)
            prefs.edit().putInt(KEY_PORT, clamped).apply()
            Trace.event(TraceArea.USER, "lyrics_display_port", "port" to clamped)
        }
    }
}
