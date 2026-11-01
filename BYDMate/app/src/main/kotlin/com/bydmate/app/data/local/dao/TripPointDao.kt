package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.TripPointEntity

@Dao
interface TripPointDao {
    @Insert
    suspend fun insertAll(points: List<TripPointEntity>)

    /**
     * One sync page, keyed by (trip_id, id): a LIMIT may land in the middle of a trip, so the
     * cursor has to be able to resume inside it. Points still in flight have trip_id = 0 and are
     * deliberately invisible until HistoryImporter attaches them.
     */
    @Query(
        "SELECT * FROM trip_points WHERE trip_id > 0 " +
            "AND (trip_id > :tripId OR (trip_id = :tripId AND id > :pointId)) " +
            "ORDER BY trip_id ASC, id ASC LIMIT :limit"
    )
    suspend fun getForSync(tripId: Long, pointId: Long, limit: Int): List<TripPointEntity>

    @Query("SELECT * FROM trip_points WHERE trip_id = :tripId ORDER BY timestamp ASC")
    suspend fun getByTripId(tripId: Long): List<TripPointEntity>

    @Query("SELECT * FROM trip_points WHERE trip_id IN (:tripIds) ORDER BY trip_id, timestamp ASC")
    suspend fun getByTripIds(tripIds: List<Long>): List<TripPointEntity>

    @Query("SELECT COUNT(*) FROM trip_points")
    suspend fun getCount(): Int

    @Query("SELECT * FROM trip_points ORDER BY trip_id, timestamp ASC")
    suspend fun getAll(): List<TripPointEntity>

    /**
     * Delete old points keeping every Nth one per trip.
     * Points older than [cutoff] are thinned to ~1 per [intervalMs].
     */
    @Query("""
        DELETE FROM trip_points
        WHERE timestamp < :cutoff
        AND id NOT IN (
            SELECT MIN(id) FROM trip_points
            WHERE timestamp < :cutoff
            GROUP BY trip_id, (timestamp / :intervalMs)
        )
    """)
    suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int

    @Query("SELECT * FROM trip_points WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getByTimeRange(from: Long, to: Long): List<TripPointEntity>

    @Query("UPDATE trip_points SET trip_id = :tripId WHERE timestamp BETWEEN :from AND :to AND trip_id = 0")
    suspend fun attachToTrip(tripId: Long, from: Long, to: Long): Int

    @Insert
    suspend fun insert(point: TripPointEntity): Long

    @Query("DELETE FROM trip_points WHERE trip_id = :tripId")
    suspend fun deleteByTripId(tripId: Long): Int
}
