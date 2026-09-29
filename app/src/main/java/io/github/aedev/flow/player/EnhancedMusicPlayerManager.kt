package io.github.aedev.flow.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.aedev.flow.data.local.AudioSettingsPersistence
import io.github.aedev.flow.data.local.QueuePersistence
import io.github.aedev.flow.data.local.VisualizerPreferences
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.service.Media3MusicService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.stream.AudioStream
import java.util.concurrent.ExecutionException
import kotlin.math.pow

@OptIn(UnstableApi::class)
object EnhancedMusicPlayerManager {
    var player: Player? = null
        private set

    /**
     * Genre/mood of the surface the current queue was started from, or null for
     * unscoped queues. Stamped into listen signals by the music service so the
     * brain learns time-of-day × genre context.
     */
    @Volatile
    var playContextGenre: String? = null

    /**
     * Video id of a radio the user asked for by name. The seed is usually already in the playing
     * queue, which the service reads as the same session, so the request has to travel as its own
     * signal. Consumed by the service on the next queue context change.
     */
    @Volatile
    var pendingRadioSeedId: String? = null

    /**
     * The collection a new queue was played from, whose own YouTube mix follows it. Travels with
     * the queue change like [pendingRadioSeedId] and is consumed by the service with it.
     */
    @Volatile
    var pendingRadioPlaylistId: String? = null

    private val _radioLoading = MutableStateFlow(false)

    /** True while the service is seeding or topping up the station, for the queue sheet's spinner. */
    val radioLoading: StateFlow<Boolean> = _radioLoading.asStateFlow()

    fun setRadioLoading(loading: Boolean) {
        _radioLoading.value = loading
    }

    private var appContext: Context? = null

    private val _playerInstance = MutableStateFlow<Player?>(null)
    val playerInstance: StateFlow<Player?> = _playerInstance.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var isInitialized = false
    private val exceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            Log.e("EnhancedMusicPlayer", "Error in player scope: ${throwable.message}", throwable)
        }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    private var retryCount = 0
    private const val MAX_RETRIES = 3
    private var positionUpdateJob: kotlinx.coroutines.Job? = null

    // Persistence
    private var queuePersistence: QueuePersistence? = null
    private var audioSettingsPersistence: AudioSettingsPersistence? = null

    // Audio Settings State
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playbackPitch = MutableStateFlow(0.0f)
    val playbackPitch: StateFlow<Float> = _playbackPitch.asStateFlow()

    // Player state flows
    private val _playerState = MutableStateFlow(MusicPlayerState())
    val playerState: StateFlow<MusicPlayerState> = _playerState.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    /**
     * Number of consumers that need sub-second progress — in practice only the expanded music
     * sheet's seek bar. Everything else (mini player, notification, saved position, elapsed-time
     * label) renders whole seconds, so [playerState] stays on a 1 Hz cadence regardless.
     *
     * Only ever touched from the main thread: the position loop runs on [scope] (Main) and the
     * callers are Compose effects.
     */
    private var preciseProgressConsumers = 0

    fun acquirePreciseProgress() {
        preciseProgressConsumers++
    }

    fun releasePreciseProgress() {
        preciseProgressConsumers = (preciseProgressConsumers - 1).coerceAtLeast(0)
    }

    // Events
    sealed class PlayerEvent {
        data class RequestPlayTrack(
            val track: MusicTrack,
        ) : PlayerEvent()

        object RequestToggleLike : PlayerEvent()
    }

    private val _playerEvents = MutableSharedFlow<PlayerEvent>()
    val playerEvents: SharedFlow<PlayerEvent> = _playerEvents.asSharedFlow()

    private val _playbackWarnings = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playbackWarnings: SharedFlow<String> = _playbackWarnings.asSharedFlow()

    // Queue
    private val _queue = MutableStateFlow<List<MusicTrack>>(emptyList())
    val queue: StateFlow<List<MusicTrack>> = _queue.asStateFlow()

    private val _automixItems = MutableStateFlow<List<MusicTrack>>(emptyList())
    val automixItems: StateFlow<List<MusicTrack>> = _automixItems.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    private val _currentTrack = MutableStateFlow<MusicTrack?>(null)
    val currentTrack: StateFlow<MusicTrack?> = _currentTrack.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    // Whether a music video shows its picture: the setting decides, and the player's Video button
    // overrides it for the session, so each video track starts the way the last one was left.
    @Volatile
    private var showVideo = false

    // Queue items that stream (not device files or downloads), and which of them carry a picture; a
    // track whose picture failed plays as its song for the rest of the session.
    private val streamItemIds =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
    private val videoItemIds =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
    private val videoUnavailableIds =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    // Surfaces on screen that show the picture; with none, the picture track is off so nothing is
    // decoded or downloaded that nobody sees (collapsed now-playing, background, screen off).
    private var videoSurfaces = 0

    private val _videoShown = MutableStateFlow(false)

    /** Whether the playing track's picture is shown rather than the visualizer. */
    val videoShown: StateFlow<Boolean> = _videoShown.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _playingFrom = MutableStateFlow("Flow Music")
    val playingFrom: StateFlow<String> = _playingFrom.asStateFlow()

    private val _isLiked = MutableStateFlow(false)
    val isLiked: StateFlow<Boolean> = _isLiked.asStateFlow()

    /** Resolves a queue item's stream ahead of playback; set by the music service while it runs. */
    @Volatile
    var prefetcher: (suspend (Uri) -> Unit)? = null
    private var pendingPlayNextMediaId: String? = null
    private var pendingPlayNextMediaIndex: Int = MusicQueuePlanner.INDEX_UNSET

    // Resolving the next track while this one plays keeps the gap between them short.
    private fun prefetchNextTrack() {
        val queue = _queue.value
        val idx = currentPlaybackQueueIndex()
        val nextTrack = queue.getOrNull(idx + 1)?.takeIf { idx != -1 && !LocalMediaIds.isLocal(it.videoId) } ?: return
        val prefetch = prefetcher ?: return
        scope.launch(Dispatchers.IO) {
            try {
                prefetch(streamUri(nextTrack))
                Log.d("EnhancedMusicPlayer", "Pre-fetched stream for next track: ${nextTrack.title}")
            } catch (e: Exception) {
                Log.w("EnhancedMusicPlayer", "Failed to pre-fetch next track: ${e.message}")
            }
        }
    }

    fun initialize(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        isInitialized = true

        queuePersistence = QueuePersistence.getInstance(context)
        audioSettingsPersistence = AudioSettingsPersistence.getInstance(context)

        val sessionToken = SessionToken(context, ComponentName(context, Media3MusicService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                player = controller
                _playerInstance.value = controller
                if (controller != null) {
                    setupPlayerListener(controller)
                    scope.launch {
                        VisualizerPreferences(context).showMusicVideos.distinctUntilChanged().collect(::setVideoMode)
                    }

                    scope.launch {
                        restoreSavedQueue()
                        restoreAudioSettings()
                    }

                    queuePersistence?.startAutoSave {
                        val currentQ = _queue.value
                        if (currentQ.isNotEmpty()) {
                            QueuePersistence.QueueState(
                                queue = currentQ,
                                currentIndex = _currentQueueIndex.value,
                                currentPosition = _currentPosition.value, // Use StateFlow, not player directly
                                currentTrackId = _currentTrack.value?.videoId,
                                shuffleEnabled = _shuffleEnabled.value,
                                repeatMode =
                                    when (_repeatMode.value) {
                                        RepeatMode.OFF -> 0
                                        RepeatMode.ALL -> 1
                                        RepeatMode.ONE -> 2
                                    },
                                savedAt = System.currentTimeMillis(),
                                automix = _automixItems.value,
                            )
                        } else {
                            null
                        }
                    }
                }
            } catch (e: ExecutionException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())

        startPositionUpdates()
    }

    private fun setupPlayerListener(controller: Player) {
        applyVideoMode(controller)
        controller.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updatePlayerState()
                    if (playbackState == Player.STATE_ENDED) {
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayerState()
                    if (isPlaying) startPositionUpdates()
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    if (mediaItem == null) return

                    val isAutomaticTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    if (enforcePendingPlayNext(controller, mediaItem, isAutomaticTransition)) {
                        return
                    }

                    syncCurrentTrackFromMediaItem(controller, mediaItem)
                    applyVideoMode(controller)
                    if (
                        isAutomaticTransition ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    ) {
                        _currentPosition.value = 0L
                        _playerState.value = _playerState.value.copy(position = 0L)
                    }
                    prefetchNextTrack()
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _repeatMode.value =
                        when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                            else -> RepeatMode.OFF
                        }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("EnhancedMusicPlayer", "Player error: ${error.errorCodeName} (${error.errorCode})", error)
                    showPlaybackWarning(
                        appContext?.getString(io.github.aedev.flow.R.string.music_playback_warning_generic)
                            ?: "Music playback failed. Try again or switch networks.",
                    )
                    retryCount = 0
                }
            },
        )
    }

    private fun syncCurrentTrackFromMediaItem(
        controller: Player,
        mediaItem: MediaItem,
    ) {
        val trackId = mediaItem.mediaId
        val currentQ = _queue.value
        val queueIndex =
            MusicQueuePlanner.currentQueueIndex(
                queueIds = currentQ.map { it.videoId },
                playerIndex = controller.currentMediaItemIndex,
                currentTrackId = trackId,
            )
        val track = currentQ.getOrNull(queueIndex) ?: currentQ.find { it.videoId == trackId }
        if (track != null) {
            val resolvedIndex =
                if (queueIndex != MusicQueuePlanner.INDEX_UNSET) {
                    queueIndex
                } else {
                    currentQ.indexOf(track)
                }
            _currentTrack.value = track
            _currentQueueIndex.value = resolvedIndex.coerceAtLeast(0)
        }
        if (
            pendingPlayNextMediaId == trackId &&
            (
                pendingPlayNextMediaIndex == MusicQueuePlanner.INDEX_UNSET ||
                    pendingPlayNextMediaIndex == controller.currentMediaItemIndex
            )
        ) {
            clearPendingPlayNext()
        }
    }

    private fun enforcePendingPlayNext(
        controller: Player,
        mediaItem: MediaItem,
        isAutomaticTransition: Boolean,
    ): Boolean {
        val expectedMediaId = pendingPlayNextMediaId
        val actualMediaId = mediaItem.mediaId
        if (!MusicQueuePlanner.shouldForcePendingPlayNext(
                isAutomaticTransition = isAutomaticTransition,
                pendingMediaId = expectedMediaId,
                pendingPlayerIndex = pendingPlayNextMediaIndex,
                actualMediaId = actualMediaId,
                actualPlayerIndex = controller.currentMediaItemIndex,
            )
        ) {
            return false
        }

        val targetIndex =
            findPlayerMediaItemIndex(
                controller = controller,
                mediaId = expectedMediaId ?: return false,
                preferredIndex = pendingPlayNextMediaIndex,
            )
        if (targetIndex == MusicQueuePlanner.INDEX_UNSET) {
            Log.w("EnhancedMusicPlayer", "Pending play-next item $expectedMediaId is missing from player queue")
            clearPendingPlayNext()
            return false
        }

        Log.w(
            "EnhancedMusicPlayer",
            "Auto transition landed on $actualMediaId; forcing queued play-next item $expectedMediaId",
        )
        controller.seekTo(targetIndex, 0L)
        controller.play()
        return true
    }

    private fun findPlayerMediaItemIndex(
        controller: Player,
        mediaId: String,
        preferredIndex: Int = MusicQueuePlanner.INDEX_UNSET,
    ): Int {
        if (
            preferredIndex in 0 until controller.mediaItemCount &&
            controller.getMediaItemAt(preferredIndex).mediaId == mediaId
        ) {
            return preferredIndex
        }

        for (index in 0 until controller.mediaItemCount) {
            if (controller.getMediaItemAt(index).mediaId == mediaId) {
                return index
            }
        }
        return MusicQueuePlanner.INDEX_UNSET
    }

    private fun currentPlaybackQueueIndex(): Int {
        val queue = _queue.value
        return MusicQueuePlanner.currentQueueIndex(
            queueIds = queue.map { it.videoId },
            playerIndex = player?.currentMediaItemIndex ?: MusicQueuePlanner.INDEX_UNSET,
            currentTrackId = _currentTrack.value?.videoId,
        )
    }

    /**
     * A device file plays from its MediaStore URI; a music video as picture and sound, whether or not
     * its picture is shown; everything else resolves through `music://`.
     */
    /** Where [track] plays from: its local file, or its descriptor for the audio plugins. */
    fun streamUri(track: MusicTrack): Uri =
        LocalMediaIds.audioUri(track.videoId)
            ?: MusicVideoItems.uri(track, withPicture = carriesPicture(track))

    private fun carriesPicture(track: MusicTrack): Boolean = showVideo && track.isVideoSong && track.videoId !in videoUnavailableIds

    private fun buildMediaItem(
        track: MusicTrack,
        uri: Uri = streamUri(track),
        useCacheKey: Boolean = !LocalMediaIds.isLocal(track.videoId),
    ): MediaItem {
        val builder =
            MediaItem
                .Builder()
                .setUri(uri)
                .setMediaId(track.videoId)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setArtworkUri(Uri.parse(track.highResThumbnailUrl))
                        .build(),
                )

        if (useCacheKey) {
            builder.setCustomCacheKey(track.videoId)
        }
        if (uri.scheme == MusicVideoItems.SCHEME || uri.scheme == MusicVideoItems.SONG_SCHEME) {
            streamItemIds += track.videoId
            if (uri.scheme == MusicVideoItems.SCHEME) videoItemIds += track.videoId else videoItemIds -= track.videoId
        } else {
            streamItemIds -= track.videoId
            videoItemIds -= track.videoId
        }

        return builder
            .build()
    }

    fun toggleVideoMode() = setVideoMode(!showVideo)

    /**
     * Shows or hides music videos' pictures. Hiding turns the playing track's picture off while its sound
     * plays on; showing gives it back, reloading the track once if it started as a song. Queued tracks
     * are rebuilt either way, so a hidden picture is never fetched.
     */
    fun setVideoMode(show: Boolean) {
        if (show == showVideo) return
        showVideo = show
        Log.d("EnhancedMusicPlayer", "Music video pictures ${if (show) "shown" else "hidden"}")
        val controller = player ?: return
        val playing = controller.currentMediaItemIndex
        val position = controller.currentPosition
        val tracks = _queue.value.associateBy { it.videoId }
        for (index in (if (show) playing else playing + 1) until controller.mediaItemCount) {
            val track = tracks[controller.getMediaItemAt(index).mediaId]?.takeIf { it.isVideoSong } ?: continue
            if (track.videoId !in streamItemIds || (track.videoId in videoItemIds) == carriesPicture(track)) continue
            controller.replaceMediaItem(index, buildMediaItem(track))
            if (index == playing) controller.seekTo(index, position)
        }
        applyVideoMode(controller)
    }

    /** A surface showing the picture is on screen; the picture track plays only while one is. */
    fun acquireVideoSurface() {
        videoSurfaces++
        player?.let(::applyVideoMode)
    }

    fun releaseVideoSurface() {
        videoSurfaces = (videoSurfaces - 1).coerceAtLeast(0)
        player?.let(::applyVideoMode)
    }

    /** The service found no playable picture for [videoId]; it plays as its song from now on. */
    fun onVideoUnavailable(videoId: String) {
        videoUnavailableIds += videoId
        videoItemIds -= videoId
        player?.let(::applyVideoMode)
    }

    private fun applyVideoMode(controller: Player) {
        val currentId = _currentTrack.value?.videoId
        _videoShown.value = showVideo && currentId != null && currentId in videoItemIds
        val play = _videoShown.value && videoSurfaces > 0
        val disabled = androidx.media3.common.C.TRACK_TYPE_VIDEO in controller.trackSelectionParameters.disabledTrackTypes
        if (disabled != play) return
        controller.trackSelectionParameters =
            controller.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, !play)
                .build()
    }

    private fun clearPendingPlayNext() {
        pendingPlayNextMediaId = null
        pendingPlayNextMediaIndex = MusicQueuePlanner.INDEX_UNSET
    }

    private fun updatePlayerState() {
        player?.let { p ->
            if (p.playbackState == Player.STATE_READY && p.isPlaying) {
                retryCount = 0
            }

            _playerState.value =
                _playerState.value.copy(
                    isPlaying = p.isPlaying,
                    isBuffering = p.playbackState == Player.STATE_BUFFERING,
                    duration = if (p.duration > 0) p.duration else _playerState.value.duration,
                    position = p.currentPosition,
                )
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob =
            scope.launch {
                while (true) {
                    val p = player
                    if (p != null && p.isPlaying) {
                        val position = p.currentPosition
                        _currentPosition.value = position

                        // Coarsened deliberately. Every consumer of playerState renders seconds, but a
                        // copy here emits a whole new MusicPlayerState to the mini player and the
                        // playback service, so it must not follow the fast tick.
                        val state = _playerState.value
                        val duration = if (p.duration > 0) p.duration else state.duration
                        if (position / 1000L != state.position / 1000L || duration != state.duration) {
                            _playerState.value = state.copy(position = position, duration = duration)
                        }

                        kotlinx.coroutines.delay(
                            if (preciseProgressConsumers > 0) {
                                PRECISE_POSITION_INTERVAL_MS
                            } else {
                                COARSE_POSITION_INTERVAL_MS
                            },
                        )
                    } else {
                        withTimeoutOrNull(5000) { _playerState.first { it.isPlaying } }
                    }
                }
            }
    }

    // --- Playback Control Methods ---

    fun setPendingTrack(
        track: MusicTrack,
        sourceName: String? = null,
    ) {
        clearPendingPlayNext()
        player?.stop()
        player?.clearMediaItems()

        _currentTrack.value = track
        sourceName?.let { _playingFrom.value = it }
        _playerState.value =
            _playerState.value.copy(
                isPlaying = false,
                isBuffering = false,
                isPreparing = true,
                position = 0,
            )
    }

    fun setCurrentTrack(
        track: MusicTrack,
        sourceName: String?,
    ) {
        clearPendingPlayNext()
        _currentTrack.value = track
        sourceName?.let { _playingFrom.value = it }
    }

    fun showPlaybackWarning(message: String) {
        _playbackWarnings.tryEmit(message)
    }

    fun playTrack(
        track: MusicTrack,
        audioStream: AudioStream,
        durationSeconds: Long,
        queue: List<MusicTrack> = emptyList(),
        startIndex: Int = -1,
        sourceName: String? = null,
    ) {
        playTrack(track, audioStream.content, queue, startIndex, sourceName = sourceName)
    }

    fun playTrack(
        track: MusicTrack,
        audioUrl: String,
        queue: List<MusicTrack> = emptyList(),
        startIndex: Int = -1,
        startPositionMs: Long = 0,
        sourceName: String? = null,
        localUriOverrides: Map<String, Uri> = emptyMap(),
    ) {
        player?.stop()
        player?.clearMediaItems()
        clearPendingPlayNext()

        _playerState.value = _playerState.value.copy(isPreparing = false)

        val activeQueue = if (queue.isNotEmpty()) queue else listOf(track)
        _queue.value = activeQueue
        _currentTrack.value = track
        sourceName?.let { _playingFrom.value = it }

        val mediaItems =
            activeQueue.map { t ->
                val localUri = localUriOverrides[t.videoId]
                val uri =
                    localUri ?: if (t.videoId == track.videoId && audioUrl.isNotEmpty() && !audioUrl.startsWith("music://")) {
                        Uri.parse(audioUrl)
                    } else {
                        streamUri(t)
                    }

                buildMediaItem(t, uri, useCacheKey = localUri == null && !LocalMediaIds.isLocal(t.videoId))
            }

        val startIdx = if (startIndex >= 0) startIndex else activeQueue.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)

        player?.setMediaItems(mediaItems, startIdx, startPositionMs)
        player?.prepare()
        player?.play()

        prefetchNextTrack()
    }

    fun updateQueue(newQueue: List<MusicTrack>) {
        if (newQueue.isEmpty()) return

        _queue.value = newQueue
        if (pendingPlayNextMediaId != null && newQueue.none { it.videoId == pendingPlayNextMediaId }) {
            clearPendingPlayNext()
        }
        triggerQueueSave()

        scope.launch {
            val currentMediaId = player?.currentMediaItem?.mediaId
            val currentPosition = player?.currentPosition ?: 0L

            val mediaItems = newQueue.map { track -> buildMediaItem(track) }

            val newIndex =
                MusicQueuePlanner
                    .currentQueueIndex(
                        queueIds = newQueue.map { it.videoId },
                        playerIndex = player?.currentMediaItemIndex ?: _currentQueueIndex.value,
                        currentTrackId = currentMediaId,
                    ).coerceAtLeast(0)

            player?.let { p ->
                if (p.mediaItemCount != mediaItems.size || p.currentMediaItem?.mediaId != currentMediaId) {
                    p.setMediaItems(mediaItems, newIndex, currentPosition)
                }
            }
        }
    }

    /**
     * Physically rearranges the queue: the playing track is pinned to the top and everything else
     * is randomized. Applied as individual player moves so playback never restarts or rebuffers.
     */
    fun shuffleQueue() {
        scope.launch {
            val currentQ = _queue.value
            if (currentQ.size < 2) return@launch
            val currentIdx =
                currentPlaybackQueueIndex().takeIf { it in currentQ.indices }
                    ?: _currentQueueIndex.value.coerceIn(0, currentQ.size - 1)
            val target =
                buildList {
                    add(currentQ[currentIdx])
                    addAll(currentQ.filterIndexed { index, _ -> index != currentIdx }.shuffled())
                }
            _queue.value = target
            clearPendingPlayNext()
            player?.let { p ->
                if (p.mediaItemCount == target.size) {
                    target.forEachIndexed { targetIdx, track ->
                        var fromIdx = -1
                        for (i in targetIdx until p.mediaItemCount) {
                            if (p.getMediaItemAt(i).mediaId == track.videoId) {
                                fromIdx = i
                                break
                            }
                        }
                        if (fromIdx > targetIdx) {
                            p.moveMediaItem(fromIdx, targetIdx)
                        }
                    }
                }
            }
            triggerQueueSave()
        }
    }

    fun updateAutomixItems(items: List<MusicTrack>) {
        _automixItems.value =
            MusicRadioPlanner.seedPool(
                candidates = items,
                currentId = _currentTrack.value?.videoId,
                queueIds = _queue.value.mapTo(HashSet()) { it.videoId },
            )
        triggerQueueSave()
    }

    /** Radio top-up path: grows the suggestion pool without disturbing what's already in it. */
    fun appendAutomixItems(items: List<MusicTrack>) {
        if (items.isEmpty()) return
        val existing = _automixItems.value
        val grown =
            MusicRadioPlanner.growPool(
                existing = existing,
                incoming = items,
                currentId = _currentTrack.value?.videoId,
                queueIds = _queue.value.mapTo(HashSet()) { it.videoId },
            )
        if (grown === existing) return
        _automixItems.value = grown
        triggerQueueSave()
    }

    fun removeAutomixItem(videoId: String) {
        val updated = _automixItems.value.filterNot { it.videoId == videoId }
        if (updated.size != _automixItems.value.size) {
            _automixItems.value = updated
            triggerQueueSave()
        }
    }

    private fun triggerQueueSave() {
        val currentQ = _queue.value
        if (currentQ.isNotEmpty()) {
            queuePersistence?.saveQueueDebounced(
                queue = currentQ,
                currentIndex = _currentQueueIndex.value,
                currentPosition = _currentPosition.value, // Use StateFlow for thread safety
                currentTrackId = _currentTrack.value?.videoId,
                shuffleEnabled = _shuffleEnabled.value,
                repeatMode =
                    when (_repeatMode.value) {
                        RepeatMode.OFF -> 0
                        RepeatMode.ALL -> 1
                        RepeatMode.ONE -> 2
                    },
                automix = _automixItems.value,
            )
        }
    }

    /**
     * Restore queue from persistent storage
     */
    private suspend fun restoreSavedQueue() {
        try {
            val savedState = queuePersistence?.restoreQueue() ?: return

            if (savedState.queue.isEmpty()) return

            if ((player?.mediaItemCount ?: 0) > 0) return

            Log.d("EnhancedMusicPlayer", "Restoring saved queue: ${savedState.queue.size} tracks")

            _queue.value = savedState.queue
            _currentQueueIndex.value = savedState.currentIndex.coerceIn(0, savedState.queue.size - 1)
            _shuffleEnabled.value = savedState.shuffleEnabled
            _repeatMode.value =
                when (savedState.repeatMode) {
                    1 -> RepeatMode.ALL
                    2 -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                }
            _automixItems.value = savedState.automix

            val currentTrack =
                savedState.currentTrackId?.let { id ->
                    savedState.queue.find { it.videoId == id }
                } ?: savedState.queue.getOrNull(savedState.currentIndex)

            currentTrack?.let {
                _currentTrack.value = it
                if (it.duration > 0) {
                    _playerState.value = _playerState.value.copy(duration = it.duration * 1000L)
                }
            }
        } catch (e: Exception) {
            Log.e("EnhancedMusicPlayer", "Failed to restore queue", e)
        }
    }

    /**
     * Force save current queue immediately.
     * Uses cached position from StateFlow for thread safety.
     */
    fun saveQueueNow() {
        scope.launch(Dispatchers.IO) {
            val currentQ = _queue.value
            if (currentQ.isNotEmpty()) {
                queuePersistence?.saveQueueImmediate(
                    queue = currentQ,
                    currentIndex = _currentQueueIndex.value,
                    currentPosition = _currentPosition.value, // Use StateFlow for thread safety
                    currentTrackId = _currentTrack.value?.videoId,
                    shuffleEnabled = _shuffleEnabled.value,
                    repeatMode =
                        when (_repeatMode.value) {
                            RepeatMode.OFF -> 0
                            RepeatMode.ALL -> 1
                            RepeatMode.ONE -> 2
                        },
                    automix = _automixItems.value,
                )
            }
        }
    }

    fun togglePlayPause() {
        scope.launch {
            player?.let { p ->
                if (p.mediaItemCount == 0 && _currentTrack.value != null) {
                    _currentTrack.value?.let { track ->
                        _playerEvents.emit(PlayerEvent.RequestPlayTrack(track))
                    }
                } else if (p.isPlaying) {
                    p.pause()
                } else {
                    p.play()
                }
            }
        }
    }

    fun playNext(track: MusicTrack) {
        val currentQ = _queue.value.toMutableList()
        val insertIdx =
            MusicQueuePlanner.playNextInsertionIndex(
                queueIds = currentQ.map { it.videoId },
                playerIndex = player?.currentMediaItemIndex ?: MusicQueuePlanner.INDEX_UNSET,
                currentTrackId = _currentTrack.value?.videoId,
            )

        currentQ.add(insertIdx, track)
        _queue.value = currentQ
        pendingPlayNextMediaId = track.videoId
        pendingPlayNextMediaIndex = insertIdx

        player?.let { p ->
            val playerInsertIdx =
                when {
                    p.currentMediaItemIndex in 0 until p.mediaItemCount -> p.currentMediaItemIndex + 1
                    else -> insertIdx
                }.coerceIn(0, p.mediaItemCount)

            if (playerInsertIdx <= p.mediaItemCount) {
                p.addMediaItem(playerInsertIdx, buildMediaItem(track))
                pendingPlayNextMediaIndex = playerInsertIdx
            }
        }

        triggerQueueSave()
    }

    fun addToQueue(track: MusicTrack) {
        val currentQ = _queue.value.toMutableList()
        currentQ.add(track)
        _queue.value = currentQ

        player?.let { p ->
            p.addMediaItem(buildMediaItem(track))
        }

        triggerQueueSave()
    }

    fun playNext() {
        val queue = _queue.value
        val idx = currentPlaybackQueueIndex()

        if (idx != -1 && idx < queue.size - 1) {
            val nextTrack = queue[idx + 1]
            setPendingTrack(nextTrack)
            scope.launch { _playerEvents.emit(PlayerEvent.RequestPlayTrack(nextTrack)) }
        }
    }

    fun playPrevious() {
        scope.launch {
            val queue = _queue.value
            val idx = currentPlaybackQueueIndex()

            if ((player?.currentPosition ?: 0) > 3000) {
                player?.seekTo(0)
                return@launch
            }

            if (idx > 0) {
                val prevTrack = queue[idx - 1]
                setPendingTrack(prevTrack)
                _playerEvents.emit(PlayerEvent.RequestPlayTrack(prevTrack))
            }
        }
    }

    fun playFromQueue(index: Int) {
        val queue = _queue.value
        if (index in queue.indices) {
            clearPendingPlayNext()
            val track = queue[index]
            setPendingTrack(track)
            scope.launch { _playerEvents.emit(PlayerEvent.RequestPlayTrack(track)) }
        }
    }

    /**
     * Shuffle is app-owned state, not ExoPlayer's shuffle mode: enabling it physically
     * rearranges the queue so the listed order always matches the playback order.
     */
    fun toggleShuffle() {
        scope.launch {
            val enabling = !_shuffleEnabled.value
            player?.shuffleModeEnabled = false
            _shuffleEnabled.value = enabling
            if (enabling) shuffleQueue()
        }
    }

    fun toggleRepeat() {
        scope.launch {
            player?.let {
                val newMode =
                    when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                it.repeatMode = newMode
            }
        }
    }

    fun seekTo(position: Long) {
        scope.launch {
            val duration = player?.duration?.takeIf { it > 0 } ?: _playerState.value.duration.takeIf { it > 0 }
            val target = duration?.let { position.coerceIn(0L, it) } ?: position.coerceAtLeast(0L)

            _currentPosition.value = target
            _playerState.value = _playerState.value.copy(position = target)
            player?.seekTo(target)
        }
    }

    fun switchMode(url: String) {
        scope.launch {
            player?.let { p ->
                val currentPos = p.currentPosition
                val wasPlaying = p.isPlaying

                val currentItem = p.currentMediaItem ?: return@let
                val newItem =
                    currentItem
                        .buildUpon()
                        .setUri(Uri.parse(url))
                        .build()

                val currentIndex = p.currentMediaItemIndex
                if (currentIndex >= 0 && currentIndex < p.mediaItemCount) {
                    p.replaceMediaItem(currentIndex, newItem)
                    p.seekTo(currentPos)
                    if (wasPlaying) p.play()
                }
            }
        }
    }

    fun getCurrentPosition(): Long =
        try {
            if (player?.isPlaying == true) {
                player?.currentPosition ?: _currentPosition.value
            } else {
                _currentPosition.value
            }
        } catch (e: Exception) {
            _currentPosition.value
        }

    fun getDuration(): Long = _playerState.value.duration

    fun toggleLike() {
        _isLiked.value = !_isLiked.value
        emitToggleLikeEvent()
    }

    fun setLiked(liked: Boolean) {
        _isLiked.value = liked
    }

    fun emitToggleLikeEvent() {
        scope.launch { _playerEvents.emit(PlayerEvent.RequestToggleLike) }
    }

    fun play() {
        scope.launch { player?.play() }
    }

    fun pause() {
        scope.launch { player?.pause() }
    }

    fun stop() {
        scope.launch {
            player?.stop()
            _playerState.value =
                _playerState.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    isPreparing = false,
                    position = 0L,
                )
            _currentPosition.value = 0L
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        scope.launch { audioSettingsPersistence?.saveSpeed(speed) }

        player?.let { p ->
            val currentPitch = p.playbackParameters.pitch
            p.playbackParameters = PlaybackParameters(speed, currentPitch)
        }
    }

    fun setPlaybackPitch(semitones: Float) {
        _playbackPitch.value = semitones
        scope.launch { audioSettingsPersistence?.savePitch(semitones) }

        player?.let { p ->
            val pitch = 2.0.pow(semitones.toDouble() / 12.0).toFloat()
            val currentSpeed = p.playbackParameters.speed
            p.playbackParameters = PlaybackParameters(currentSpeed, pitch)
        }
    }

    private suspend fun restoreAudioSettings() {
        try {
            val settings = audioSettingsPersistence?.settingsFlow?.first() ?: return

            Log.d("EnhancedMusicPlayer", "Restoring audio settings: $settings")

            _playbackSpeed.value = settings.speed
            _playbackPitch.value = settings.pitch

            player?.let { p ->
                val pitch = 2.0.pow(settings.pitch.toDouble() / 12.0).toFloat()
                p.playbackParameters = PlaybackParameters(settings.speed, pitch)
            }
        } catch (e: Exception) {
            Log.e("EnhancedMusicPlayer", "Failed to restore audio settings", e)
        }
    }

    fun isPlaying(): Boolean = _playerState.value.isPlaying

    fun clearCurrentTrack() {
        scope.launch {
            player?.pause()
            player?.stop()
            player?.clearMediaItems()
            _currentTrack.value = null
            _queue.value = emptyList()
            _automixItems.value = emptyList()
            playContextGenre = null
            pendingRadioSeedId = null
            pendingRadioPlaylistId = null
            _radioLoading.value = false
            _currentQueueIndex.value = 0
            clearPendingPlayNext()
            _currentPosition.value = 0L
            _playingFrom.value = "Flow Music"
            _playerState.value = MusicPlayerState()
            appContext?.let { context ->
                context.stopService(Intent(context, Media3MusicService::class.java))
            }
        }
    }

    fun removeFromQueue(index: Int) {
        removeMediaItem(index)
    }

    fun removeMediaItem(index: Int) {
        scope.launch {
            val currentQ = _queue.value.toMutableList()
            if (index in currentQ.indices) {
                currentQ.removeAt(index)
                _queue.value = currentQ
                when {
                    pendingPlayNextMediaIndex == index -> clearPendingPlayNext()
                    pendingPlayNextMediaIndex > index -> pendingPlayNextMediaIndex--
                }

                player?.let { p ->
                    if (index < p.mediaItemCount) {
                        p.removeMediaItem(index)
                    }
                }
                triggerQueueSave()
            }
        }
    }

    fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        scope.launch {
            val currentQ = _queue.value.toMutableList()
            if (fromIndex in currentQ.indices && toIndex in currentQ.indices) {
                val item = currentQ.removeAt(fromIndex)
                currentQ.add(toIndex, item)
                _queue.value = currentQ
                clearPendingPlayNext()

                player?.let { p ->
                    if (fromIndex < p.mediaItemCount && toIndex < p.mediaItemCount) {
                        p.moveMediaItem(fromIndex, toIndex)
                    }
                }
                triggerQueueSave()
            }
        }
    }

    fun insertMediaItem(
        index: Int,
        track: MusicTrack,
    ) {
        scope.launch {
            val currentQ = _queue.value.toMutableList()
            val insertIndex = index.coerceIn(0, currentQ.size)
            currentQ.add(insertIndex, track)
            _queue.value = currentQ
            if (pendingPlayNextMediaIndex >= insertIndex) {
                pendingPlayNextMediaIndex++
            }

            player?.let { p ->
                if (insertIndex <= p.mediaItemCount) {
                    p.addMediaItem(insertIndex, buildMediaItem(track))
                }
            }
            triggerQueueSave()
        }
    }
}

data class MusicPlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isPreparing: Boolean = false,
    val isReady: Boolean = false,
    val playWhenReady: Boolean = false,
    val duration: Long = 0,
    val position: Long = 0,
)

private const val PRECISE_POSITION_INTERVAL_MS = 250L
private const val COARSE_POSITION_INTERVAL_MS = 1_000L

enum class RepeatMode {
    OFF,
    ALL,
    ONE,
}
