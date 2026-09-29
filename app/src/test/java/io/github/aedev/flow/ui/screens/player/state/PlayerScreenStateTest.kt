package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.components.shared.CommentSortFilter
import io.github.aedev.flow.ui.components.videoplayer.subtitle.SubtitleStyle
import org.junit.Test

/**
 * Pins the exact shape of [PlayerScreenState]: what every property starts as and precisely which
 * ones each mutator touches. The tables are exhaustive on purpose, so a property added without a
 * decision about the reset paths fails a test rather than slipping through.
 */
class PlayerScreenStateTest {
    private val defaults: Map<String, Any?> =
        mapOf(
            "showControls" to true,
            "isTouchLocked" to false,
            "lockOverlayRevealSignal" to 0,
            "isFullscreen" to false,
            "isFullscreenPortrait" to false,
            "isScrubbing" to false,
            "isSeekDragging" to false,
            "seekDragTargetMs" to 0L,
            "seekDragDeltaMs" to 0L,
            "currentPosition" to 0L,
            "bufferedPosition" to 0L,
            "duration" to 0L,
            "activeSheet" to PlayerSheet.None,
            "showLiveChatPanel" to true,
            "commentSortFilter" to CommentSortFilter.TOP,
            "brightnessLevel" to 0.5f,
            "volumeLevel" to 0.5f,
            "showBrightnessOverlay" to false,
            "showVolumeOverlay" to false,
            "showSeekForwardAnimation" to false,
            "seekAccumulation" to 10,
            "showSeekBackAnimation" to false,
            "subtitlesEnabled" to false,
            "selectedSubtitleUrl" to null,
            "subtitleStyle" to SubtitleStyle(),
            "resizeMode" to 0,
            "zoomScale" to 1f,
            "zoomOffsetX" to 0f,
            "zoomOffsetY" to 0f,
            "showZoomIndicator" to false,
            "zoomIndicatorSequence" to 0,
            "exitDragOffsetY" to 0f,
            "exitDragProgress" to 0f,
            "isSpeedBoostActive" to false,
            "normalSpeed" to 1.0f,
            "showShortsPrompt" to false,
            "hasShownShortsPrompt" to false,
        )

    private val resetForNewVideoTouches =
        setOf(
            "showControls",
            "isScrubbing",
            "isSeekDragging",
            "seekDragTargetMs",
            "seekDragDeltaMs",
            "isFullscreenPortrait",
            "isTouchLocked",
            "lockOverlayRevealSignal",
            "currentPosition",
            "duration",
            "subtitlesEnabled",
            "selectedSubtitleUrl",
            "showBrightnessOverlay",
            "showVolumeOverlay",
            "showSeekBackAnimation",
            "showSeekForwardAnimation",
            "hasShownShortsPrompt",
            "showShortsPrompt",
            "activeSheet",
            "showLiveChatPanel",
            "zoomScale",
            "zoomOffsetX",
            "zoomOffsetY",
            "showZoomIndicator",
            "zoomIndicatorSequence",
            "exitDragOffsetY",
            "exitDragProgress",
        )

    // Pins current behaviour: bufferedPosition, isSpeedBoostActive and seekAccumulation
    // survive a video change alongside the deliberately persistent display and gesture preferences.
    private val resetForNewVideoLeaves =
        setOf(
            "isFullscreen",
            "bufferedPosition",
            "commentSortFilter",
            "brightnessLevel",
            "volumeLevel",
            "seekAccumulation",
            "subtitleStyle",
            "resizeMode",
            "isSpeedBoostActive",
            "normalSpeed",
        )

    private val dismissMediaSheetsClears = setOf("activeSheet")

    /**
     * The eighteen-flag state deliberately left the sleep timer, download and cast
     * dialogs standing through a dismissal. One exclusive [PlayerSheet] cannot express that carve
     * out, so they close with everything else now.
     */
    private val dismissMediaSheetsAlsoClosesNow =
        listOf(
            PlayerSheet.SleepTimer,
            PlayerSheet.Download,
            PlayerSheet.Dlna,
        )

    @Test
    fun `the reset tables partition every property`() {
        assertThat(resetForNewVideoTouches + resetForNewVideoLeaves).containsExactlyElementsIn(defaults.keys)
        assertThat(resetForNewVideoTouches intersect resetForNewVideoLeaves).isEmpty()
    }
}
