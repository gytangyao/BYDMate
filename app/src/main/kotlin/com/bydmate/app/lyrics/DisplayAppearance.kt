package com.bydmate.app.lyrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The head unit's appearance, watched rather than polled.
 *
 * Two system facts make a [ScreenLook]: the night bit of the process configuration, which is what
 * Android turns when the car's lights come on or the driver picks a dark theme, and the backlight
 * setting. Both are observed — a [ContentObserver] per brightness key and one receiver for
 * `ACTION_CONFIGURATION_CHANGED` — so a panel across the cabin dims in the same moment the car does,
 * not on the lyric engine's next poll. The mode key is watched as well as the level, because a car that
 * switches to automatic has changed what it is telling the panel even when the number has not moved.
 *
 * Nothing here is a permission: `Settings.System` brightness is readable by any app, and writing it is
 * what needs the grant this never asks for.
 */
@Singleton
class DisplayAppearance @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** One read of the current appearance. A setting the firmware does not have is a null, not a throw. */
    fun read(): ScreenLook = ScreenLook(
        theme = vendorTheme() ?: themeFor(context.resources.configuration.uiMode),
        brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()?.coerceIn(0, MAX_BRIGHTNESS),
        auto = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrNull() == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
    )

    /**
     * DiLink's own light/dark setting, which this firmware keeps out of Android's night bit.
     *
     * On DiLink 5.0 the value that moves with the driver's 深浅模式 is `time_wallpaper_mode` inside the
     * vendor `carsettings` provider, and it numbers the two schemes opposite to the old key: 2 while
     * the screen is light, 1 while it is dark. Confirmed against the car's own screen after a manual
     * switch, not inferred from the brightness that came with it. It is the effective state, so it
     * also answers for 自动. `sys_screen_mode` (0 automatic, 1 light, 2 dark) is the older generation's
     * key and is kept as the second source; anything unreadable falls through to the night bit rather
     * than a guess.
     */
    fun vendorTheme(): String? = when (providerInt(KEY_WALLPAPER_MODE)) {
        WALLPAPER_LIGHT -> DisplayProtocol.THEME_LIGHT
        WALLPAPER_DARK -> DisplayProtocol.THEME_DARK
        else -> when (providerInt(SCREEN_MODE_KEY)) {
            SCREEN_MODE_LIGHT -> DisplayProtocol.THEME_LIGHT
            SCREEN_MODE_DARK -> DisplayProtocol.THEME_DARK
            else -> null
        }
    }

    fun vendorMode(): Int? = providerInt(SCREEN_MODE_KEY)

    private fun providerInt(key: String): Int? = runCatching {
        // The whole table, not a selection: a provider that ignores `selection` would otherwise
        // answer with its first row, which is not this key.
        context.contentResolver.query(
            CARSETTINGS_GLOBAL,
            arrayOf("key", "value"),
            null,
            null,
            null,
        )?.use { c ->
            val keyAt = c.getColumnIndex("key")
            val valAt = c.getColumnIndex("value")
            if (keyAt < 0 || valAt < 0) return@use null
            while (c.moveToNext()) {
                if (c.getString(keyAt) == key) {
                    return@use c.getString(valAt)?.trim()?.toIntOrNull()
                }
            }
            null
        }
    }.getOrNull() ?: runCatching {
        Settings.Global.getInt(context.contentResolver, key)
    }.getOrNull()

    /** Emits the current look at once and then only when one of the two facts actually changed. */
    fun changes(): Flow<ScreenLook> = callbackFlow {
        val resolver = context.contentResolver
        trySend(read())

        // A null handler puts onChange on the binder thread; trySend is safe there and the collector
        // stays on whichever dispatcher the engine launched it on.
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(read())
            }
        }
        val keys = listOf(Settings.System.SCREEN_BRIGHTNESS, Settings.System.SCREEN_BRIGHTNESS_MODE)
        val watched = keys.count { key ->
            runCatching {
                resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer)
            }.onFailure { Log.w(TAG, "no observer for $key: ${it.javaClass.simpleName}") }.isSuccess
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                trySend(read())
            }
        }
        val receiverUp = runCatching {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED))
        }.onFailure { Log.w(TAG, "no configuration receiver: ${it.javaClass.simpleName}") }.isSuccess
        Log.i(TAG, "watching appearance: brightness=$watched/${keys.size} config=$receiverUp")

        awaitClose {
            // One unregister covers every URI the same observer was registered for.
            if (watched > 0) runCatching { resolver.unregisterContentObserver(observer) }
            if (receiverUp) runCatching { context.unregisterReceiver(receiver) }
        }
    }.distinctUntilChanged()

    private companion object {
        const val TAG = "DisplayAppearance"

        /** DiLink keeps its light/dark switch here; Android's own night bit does not move with it. */
        val CARSETTINGS_GLOBAL: Uri = Uri.parse("content://carsettings/global")

        /** What DiLink 5.0 actually flips: 2 light, 1 dark — note the inverted numbering. */
        const val KEY_WALLPAPER_MODE = "time_wallpaper_mode"
        const val WALLPAPER_LIGHT = 2
        const val WALLPAPER_DARK = 1

        /** The older generation's switch (0 automatic, 1 light, 2 dark); a dead constant here. */
        const val SCREEN_MODE_KEY = "sys_screen_mode"
        const val SCREEN_MODE_LIGHT = 1
        const val SCREEN_MODE_DARK = 2

        /** Android's own scale for `SCREEN_BRIGHTNESS`; a firmware using a wider one is clamped to it. */
        const val MAX_BRIGHTNESS = 255

        /**
         * Only an explicit "not night" is daylight. An undefined night bit is treated as dark: that is
         * the scheme BYDMate itself draws, and guessing light would flash a white panel into a cabin
         * the system has not yet made its mind up about.
         */
        fun themeFor(uiMode: Int): String =
            if (uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO) {
                DisplayProtocol.THEME_LIGHT
            } else {
                DisplayProtocol.THEME_DARK
            }
    }
}