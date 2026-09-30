package io.github.aedev.flow.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.aedev.flow.data.local.entity.TrackMatchEntity

@Dao
interface TrackMatchDao {
    @Query("SELECT * FROM track_matches WHERE fingerprint = :fingerprint AND pluginId = :pluginId LIMIT 1")
    suspend fun find(
        fingerprint: String,
        pluginId: String,
    ): TrackMatchEntity?

    @Upsert
    suspend fun upsert(match: TrackMatchEntity)

    @Query("DELETE FROM track_matches WHERE matchedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
