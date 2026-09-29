package io.github.aedev.flow.player

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global singleton to manage persistent video player state across the app.
 * Now delegates to EnhancedPlayerManager for actual player operations.
 * Maintains compatibility with existing code while providing enhanced features.
 */
@UnstableApi
object GlobalPlayerState {
    private const val TAG = "GlobalPlayerState"

    private val _currentVideo = MutableStateFlow<Video?>(null)
    val currentVideo: StateFlow<Video?> = _currentVideo.asStateFlow()

    private val _isExplicitBackgroundPlaybackActive = MutableStateFlow(false)
    val isExplicitBackgroundPlaybackActive: StateFlow<Boolean> =
        _isExplicitBackgroundPlaybackActive.asStateFlow()

    // Delegate to EnhancedPlayerManager for player state. This is the single reactive
    // source of truth for playback; collect playerState for isPlaying/position/duration.
    val playerState: StateFlow<EnhancedPlayerState> = EnhancedPlayerManager.getInstance().playerState

    /**
     * Initialize the player - delegates to EnhancedPlayerManager.
     */
    fun initialize(context: Context) {
        EnhancedPlayerManager.getInstance().initialize(context)
    }

    /**
     * Cold-start initialization that keeps the disk-bound half of player setup off the main thread.
     *
     * Best effort by design: this runs from the activity's lifecycle scope, so a throw here used to take the
     * launch down, and because the causes are persisted state (buffer preferences, cache index) it kept doing
     * so on every relaunch (#780, #788, #876). The playback entry points call [initialize] themselves while no
     * player exists, so giving up here costs a one-off setup on first playback rather than the whole app.
     */
    suspend fun initializeAsync(context: Context) {
        try {
            EnhancedPlayerManager.getInstance().initializeAsync(context)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.e(TAG, "Cold-start player initialization failed; deferring to on-demand setup", error)
        }
    }

    fun setExplicitBackgroundPlaybackActive(active: Boolean) {
        _isExplicitBackgroundPlaybackActive.value = active
    }

    /**
     * Set the current video being played.
     */
    fun setCurrentVideo(video: Video?) {
        _currentVideo.value = video
    }

    /**
     * Toggle play/pause state - delegates to EnhancedPlayerManager.
     */
    fun togglePlayPause() {
        if (EnhancedPlayerManager.getInstance().isPlaying()) {
            EnhancedPlayerManager.getInstance().pause()
        } else {
            EnhancedPlayerManager.getInstance().play()
        }
    }

    /**
     * Pause playback - delegates to EnhancedPlayerManager.
     */
    fun pause() {
        EnhancedPlayerManager.getInstance().pause()
    }

    /**
     * Resume playback - delegates to EnhancedPlayerManager.
     */
    fun play() {
        EnhancedPlayerManager.getInstance().play()
    }

    /**
     * Stop playback and clear current video.
     */
    fun stop() {
        EnhancedPlayerManager.getInstance().stop()
        _isExplicitBackgroundPlaybackActive.value = false
        _currentVideo.value = null
    }

    /**
     * Release the player - delegates to EnhancedPlayerManager.
     */
    fun release() {
        EnhancedPlayerManager.getInstance().release()
        _isExplicitBackgroundPlaybackActive.value = false
        _currentVideo.value = null
    }
}
