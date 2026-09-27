package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.ui.components.musicplayer.controls.PlayerProgressSlider
import io.github.aedev.flow.ui.tv.components.TvIconButton
import io.github.aedev.flow.ui.tv.components.TvIconButtonColors

// Fits beside the open queue panel: screen width minus the panel, the overscan edge and a gap.
private val ControlsWidth = 480.dp
private val ControlButtonSize = 48.dp

/** What the controls bar shows; the screen owns the state and the player calls. */
internal data class TvNowPlayingControlsState(
    val isPlaying: Boolean,
    val isLiked: Boolean,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
    val lyricsOpen: Boolean,
    val queueOpen: Boolean,
)

internal class TvNowPlayingControlsActions(
    val onSeekTo: (Long) -> Unit,
    val onSeekBarFocusChanged: (Boolean) -> Unit,
    val onToggleShuffle: () -> Unit,
    val onPrevious: () -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onToggleRepeat: () -> Unit,
    val onToggleLike: () -> Unit,
    val onToggleLyrics: () -> Unit,
    val onToggleQueue: () -> Unit,
)

/**
 * Seek bar and transport for the now-playing screen. The mobile progress slider sits in a focusable
 * shell so D-pad left/right scrub through the screen's scrub controller.
 */
@Composable
internal fun TvNowPlayingControls(
    state: TvNowPlayingControlsState,
    actions: TvNowPlayingControlsActions,
    positionProvider: () -> Long,
    durationMs: Long,
    buttonColors: TvIconButtonColors,
    playPauseFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var seekBarFocused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.width(ControlsWidth),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        seekBarFocused = it.isFocused
                        actions.onSeekBarFocusChanged(it.isFocused)
                    }.focusable(),
            shape = MaterialTheme.shapes.large,
            color = if (seekBarFocused) buttonColors.container else Color.Transparent,
        ) {
            PlayerProgressSlider(
                positionProvider = positionProvider,
                duration = durationMs,
                onSeekTo = actions.onSeekTo,
                isPlaying = state.isPlaying,
                modifier =
                    Modifier
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .focusProperties { canFocus = false },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvIconButton(
                icon = Icons.Outlined.Shuffle,
                contentDescription = stringResource(R.string.shuffle),
                onClick = actions.onToggleShuffle,
                active = state.shuffleEnabled,
                colors = buttonColors,
                size = ControlButtonSize,
            )
            TvIconButton(
                icon = Icons.Outlined.SkipPrevious,
                contentDescription = stringResource(R.string.previous),
                onClick = actions.onPrevious,
                colors = buttonColors,
                size = ControlButtonSize,
            )
            TvIconButton(
                icon = if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                onClick = actions.onTogglePlayPause,
                active = true,
                colors = buttonColors,
                size = ControlButtonSize,
                focusRequester = playPauseFocusRequester,
            )
            TvIconButton(
                icon = Icons.Outlined.SkipNext,
                contentDescription = stringResource(R.string.next),
                onClick = actions.onNext,
                colors = buttonColors,
                size = ControlButtonSize,
            )
            TvIconButton(
                icon = if (state.repeatMode == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                contentDescription = stringResource(R.string.loop_video),
                onClick = actions.onToggleRepeat,
                active = state.repeatMode != RepeatMode.OFF,
                colors = buttonColors,
                size = ControlButtonSize,
            )
            TvIconButton(
                icon = if (state.isLiked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(R.string.tv_library_likes),
                onClick = actions.onToggleLike,
                active = state.isLiked,
                colors = buttonColors,
                size = ControlButtonSize,
            )
            TvIconButton(
                icon = Icons.Outlined.Lyrics,
                contentDescription = stringResource(R.string.tv_music_lyrics),
                onClick = actions.onToggleLyrics,
                active = state.lyricsOpen,
                colors = buttonColors,
                size = ControlButtonSize,
            )
            TvIconButton(
                icon = Icons.AutoMirrored.Outlined.QueueMusic,
                contentDescription = stringResource(R.string.tv_player_queue),
                onClick = actions.onToggleQueue,
                active = state.queueOpen,
                colors = buttonColors,
                size = ControlButtonSize,
            )
        }
    }
}
