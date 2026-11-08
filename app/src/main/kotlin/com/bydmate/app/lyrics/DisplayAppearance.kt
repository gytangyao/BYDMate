package com.bydmate.app.lyrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
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
        theme = themeFor(context.resources.configuration.uiMode),
        brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()?.coerceIn(0, MAX_BRIGHTNESS),
        auto = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrNull() == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
    )

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