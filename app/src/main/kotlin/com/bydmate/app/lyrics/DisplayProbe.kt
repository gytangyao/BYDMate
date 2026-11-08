package com.bydmate.app.lyrics

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import com.bydmate.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TEMPORARY 2026-11-08, delete once the DiLink source is confirmed on the car.
 *
 * Dumps the vendor `carsettings` tables whole (no key filter) plus the likely Settings rows, so one
 * light/dark switch on the car is enough to see which value moved. The phone cannot see any of this,
 * so the snapshot rides in `car_state.raw` where a database query can read it.
 */
@Singleton
class DisplayProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appearance: DisplayAppearance,
) {

    fun snapshot(): String = buildString {
        append("ver=").append(BuildConfig.VERSION_NAME).append('/').append(BuildConfig.VERSION_CODE)
        append(" ui=").append(context.resources.configuration.uiMode)
            .append("/").append(
                if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
                ) "yes" else "no"
            )
        append(" mode=").append(appearance.vendorMode() ?: "-")
        append(" theme=").append(appearance.vendorTheme() ?: "-")
        append(" sec=").append(
            runCatching { Settings.Secure.getInt(context.contentResolver, "ui_night_mode") }
                .getOrNull() ?: "-"
        )
        for (uri in URIS) {
            append(' ').append(uri.lastPathSegment).append('=').append(dump(uri))
        }
    }.take(4000)

    private fun dump(uri: Uri): String = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val names = c.columnNames
            val keyAt = names.indexOfFirst { it.equals("key", true) || it.equals("name", true) }
            val valAt = names.indexOfFirst { it.equals("value", true) || it.equals("val", true) }
            val out = ArrayList<String>()
            while (c.moveToNext() && out.size < 120) {
                val k = if (keyAt >= 0) c.getString(keyAt) else "c$keyAt"
                val v = if (valAt >= 0) c.getString(valAt) else c.getString(0)
                out += "${k.orEmpty().take(50)}=${v.orEmpty().take(60)}"
            }
            out.joinToString(";").ifEmpty { "(empty)" }
        } ?: "(null)"
    } catch (e: Exception) {
        "(err ${e.javaClass.simpleName})"
    }

    private companion object {
        val URIS = listOf(
            "global",
            "system",
            "vehicle",
            "user",
            "personal",
            "display",
            "theme",
        ).map { Uri.parse("content://carsettings/$it") }
    }
}
