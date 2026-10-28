package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.ChargeSampleEntity

@Dao
interface ChargeSampleDao {
    @Insert
    suspend fun insert(sample: ChargeSampleEntity)

    @Query("SELECT * FROM charge_samples WHERE ts BETWEEN :from AND :to ORDER BY ts ASC")
    suspend fun getRange(from: Long, to: Long): List<ChargeSampleEntity>

    @Query("DELETE FROM charge_samples WHERE ts BETWEEN :from AND :to")
    suspend fun deleteRange(from: Long, to: Long)

    @Query("DELETE FROM charge_samples WHERE ts < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
