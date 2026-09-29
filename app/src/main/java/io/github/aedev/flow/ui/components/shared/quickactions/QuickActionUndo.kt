package io.github.aedev.flow.ui.components.shared.quickactions

import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.entity.PlaylistVideoCrossRef
import io.github.aedev.flow.data.model.Video

/** How to put an action back. Each carries the state to restore, not the state that was set. */
sealed interface QuickActionUndo {
    data class WatchLater(
        val video: Video,
        val saved: Boolean,
    ) : QuickActionUndo

    data class Subscription(
        val channelId: String,
        val channelName: String,
        val channelThumbnail: String,
        val subscribed: Boolean,
    ) : QuickActionUndo

    data class ChannelBlock(
        val channelId: String,
    ) : QuickActionUndo

    data class PlaylistRemoval(
        val entries: List<PlaylistVideoCrossRef>,
    ) : QuickActionUndo

    /** Likes taken off the Liked videos or Liked music page. */
    data class Unlike(
        val likes: List<LikedVideoInfo>,
    ) : QuickActionUndo

    /** Files moved to the system trash; putting them back needs the system's consent, asked by the host. */
    data class RestoreFromTrash(
        val contentUris: List<String>,
    ) : QuickActionUndo
}
