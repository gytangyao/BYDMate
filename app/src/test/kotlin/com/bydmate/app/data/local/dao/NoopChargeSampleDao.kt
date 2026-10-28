package com.bydmate.app.data.local.dao

import com.bydmate.app.data.local.entity.ChargeSampleEntity

/** The curve buffer is irrelevant to tests that only inspect charge rows or the screen state. */
object NoopChargeSampleDao : ChargeSampleDao {
    override suspend fun insert(sample: ChargeSampleEntity) {}
    override suspend fun getRange(from: Long, to: Long): List<ChargeSampleEntity> = emptyList()
    override suspend fun deleteRange(from: Long, to: Long) {}
    override suspend fun deleteOlderThan(cutoff: Long): Int = 0
}
