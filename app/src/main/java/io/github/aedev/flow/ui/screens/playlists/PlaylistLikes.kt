package io.github.aedev.flow.ui.screens.playlists

import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.toVideo

/** A like as a row of the Liked videos page; when it was liked stands in for when it was added. */
internal fun LikedVideoInfo.toPlaylistVideo(): Video = toVideo().copy(timestamp = 0L, viewCount = 0L, addedAtInPlaylist = likedAt)
