package com.bydmate.app.lyrics

/**
 * How the head unit's own screen looks, which is what a second panel in the same cabin has to match.
 *
 * Not BYDMate's colours — the app draws one dark scheme everywhere — but the device's: whether Android
 * is in night mode and what its backlight is set to. Both are read from the system rather than from a
 * setting of our own, because the thing the driver watches change is the car's screen: it turns with
 * the headlights and dims at dusk, and a lyric panel that stayed a bright rectangle through that would
 * be the one thing in the cabin not belonging to it.
 *
 * Pure, so the value a display is handed can be built and compared without a device.
 */
data class ScreenLook(
    /** [DisplayProtocol.THEME_DARK] or [DisplayProtocol.THEME_LIGHT]. */
    val theme: String,
    /** Android's `SCREEN_BRIGHTNESS` on its 0..255 scale; null when the setting cannot be read. */
    val brightness: Int?,
    /** True while the head unit's light sensor drives the backlight. The setting is still the car's own
     *  number and still worth copying — see [DisplayProtocol.look]. */
    val auto: Boolean,
) {
    fun frame(): String = DisplayProtocol.look(theme, brightness, auto)
}