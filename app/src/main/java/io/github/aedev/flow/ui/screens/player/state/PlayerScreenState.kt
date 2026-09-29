package io.github.aedev.flow.ui.screens.player.state

import androidx.compose.runtime.*
import io.github.aedev.flow.ui.components.shared.CommentSortFilter
import io.github.aedev.flow.ui.components.videoplayer.subtitle.SubtitleStyle

// Every property is snapshot state, so composables taking this instance can skip on identity.
@Stable
class PlayerScreenState {
    // UI Visibility States
    var showControls by mutableStateOf(true)
    var isTouchLocked by mutableStateOf(false)
    var lockOverlayRevealSignal by mutableIntStateOf(0)
    var isFullscreenPortrait by mutableStateOf(false)
    var lastInteractionTimestamp by mutableLongStateOf(System.currentTimeMillis())

    var isScrubbing by mutableStateOf(false)

    var isSeekDragging by mutableStateOf(false)
    var seekDragTargetMs by mutableLongStateOf(0L)
    var seekDragDeltaMs by mutableLongStateOf(0L)

    // Playback Position
    var currentPosition by mutableLongStateOf(0L)
    var bufferedPosition by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)

    // Sheets, panels and dialogs (exactly one at a time)
    internal var activeSheet by mutableStateOf<PlayerSheet>(PlayerSheet.None)

    // The tablet side column's own show/hide toggle, not a sheet: it survives a sheet dismissal.
    var showLiveChatPanel by mutableStateOf(true)

    // Comment Sorting
    var commentSortFilter by mutableStateOf(CommentSortFilter.TOP)

    var showBrightnessOverlay by mutableStateOf(false)
    var showVolumeOverlay by mutableStateOf(false)

    // Seek Animation States
    var showSeekForwardAnimation by mutableStateOf(false)
    var showSeekBackAnimation by mutableStateOf(false)

    // Subtitle States
    var subtitlesEnabled by mutableStateOf(false)
    var selectedSubtitleUrl by mutableStateOf<String?>(null)
    var subtitleStyle by mutableStateOf(SubtitleStyle())

    // The caption track the transcript is read from. Separate from the subtitle choice: reading
    // a transcript in another language should not put that language on top of the video.
    var selectedTranscriptUrl by mutableStateOf<String?>(null)

    // Video Display
    var resizeMode by mutableIntStateOf(0) // 0=Fit, 1=Fill, 2=Zoom

    // Pinch-to-Zoom State
    var zoomScale by mutableFloatStateOf(1f)
    var zoomOffsetX by mutableFloatStateOf(0f)
    var zoomOffsetY by mutableFloatStateOf(0f)
    var showZoomIndicator by mutableStateOf(false)
    var zoomIndicatorSequence by mutableIntStateOf(0)
    var exitDragOffsetY by mutableFloatStateOf(0f)
    var exitDragProgress by mutableFloatStateOf(0f)

    // Shorts/Music Prompt
    var showShortsPrompt by mutableStateOf(false)
    var hasShownShortsPrompt by mutableStateOf(false)

    fun resetForNewVideo() {
        lastInteractionTimestamp = System.currentTimeMillis()
        showControls = true
        isScrubbing = false
        isSeekDragging = false
        seekDragTargetMs = 0L
        seekDragDeltaMs = 0L
        isFullscreenPortrait = false
        isTouchLocked = false
        lockOverlayRevealSignal = 0
        currentPosition = 0L
        duration = 0L
        subtitlesEnabled = false
        selectedSubtitleUrl = null
        selectedTranscriptUrl = null
        showBrightnessOverlay = false
        showVolumeOverlay = false
        showSeekBackAnimation = false
        showSeekForwardAnimation = false
        hasShownShortsPrompt = false
        showShortsPrompt = false
        activeSheet = PlayerSheet.None
        showLiveChatPanel = true
        zoomScale = 1f
        zoomOffsetX = 0f
        zoomOffsetY = 0f
        showZoomIndicator = false
        zoomIndicatorSequence = 0
        exitDragOffsetY = 0f
        exitDragProgress = 0f
    }

    internal fun open(sheet: PlayerSheet) {
        activeSheet = sheet
    }
}
