package com.bydmate.app.data.mysql

import com.bydmate.app.BuildConfig

/**
 * The hub this build was compiled against. The values come from the machine's gradle.properties
 * through `buildConfigField`, so an installed app connects without anyone typing on the head unit's
 * keyboard, and no credential lives in the source tree.
 *
 * Every field stays editable in Settings; these are only what the fields start out as and what a
 * stored blank falls back to.
 */
object MysqlDefaults {
    val HOST: String = BuildConfig.HUB_HOST
    val PORT: String = BuildConfig.HUB_PORT
    val USER: String = BuildConfig.HUB_USER
    val PASSWORD: String = BuildConfig.HUB_PASSWORD
    val DATABASE: String = BuildConfig.HUB_DATABASE
    val VEHICLE_ID: String = BuildConfig.HUB_VEHICLE_ID
}
