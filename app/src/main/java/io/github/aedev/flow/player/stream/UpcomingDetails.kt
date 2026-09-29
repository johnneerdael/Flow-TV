package io.github.aedev.flow.player.stream

/** What a video plugin reports about a video that has not started yet. */
data class UpcomingDetails(
    val title: String,
    val channelName: String,
    val channelId: String,
    val thumbnailUrl: String,
    val description: String,
)
