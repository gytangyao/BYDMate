package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One charging tick's reading, buffered until the session row exists.
 *
 * The session is written at the unplug, possibly twelve hours after the plug went in, so a
 * point cannot carry a `charge_id` while it is sampled. These rows are copied into
 * `charge_points` (with the real id) when the row is created, which is what the hub ships and
 * the phone draws as the charge curve.
 */
@Entity(tableName = "charge_samples", indices = [Index("ts")])
data class ChargeSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    @ColumnInfo(name = "power_kw") val powerKw: Double? = null,
    val soc: Int? = null,
    @ColumnInfo(name = "bat_temp") val batTemp: Int? = null
)
