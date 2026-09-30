package io.github.aedev.flow.data.local.entity

import androidx.room.Entity

/**
 * Which of an audio plugin's tracks plays a track another plugin describes (a Spotify track through
 * YouTube Music), so it is searched once. [fingerprint] names the described track by its ids;
 * [candidate] is the audio plugin's own descriptor as JSON, or null when it has no confident match.
 */
@Entity(tableName = "track_matches", primaryKeys = ["fingerprint", "pluginId"])
data class TrackMatchEntity(
    val fingerprint: String,
    val pluginId: String,
    val candidate: String?,
    val confidence: Double,
    val matchedAt: Long,
)
