package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.data.model.LiveChatMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One batch of a live chat: its messages, the cursor that continues it and how long to wait before asking. */
internal data class LiveChatPoll(
    val messages: List<LiveChatMessage>,
    val next: String?,
    val pollAfterMs: Long,
)

/**
 * The live chat transcript and the loop that drips it in.
 *
 * Availability is probed as soon as a live video starts, because the whole chat affordance is
 * hidden until it resolves; the probe's batch is kept, so it is the first one shown. The polling
 * behind it runs only while the chat is on screen and the video is playing: a chat nobody is
 * looking at is a request every few seconds for the length of a stream, and the player keeps its
 * surfaces composed while they are hidden. Hiding it keeps the transcript and the cursor, so
 * reopening it picks up where it left off.
 *
 * [fetch] asks for the batch after a cursor, or the first one for a null cursor; null means it failed.
 */
internal class LiveChatController(
    private val fetch: suspend (videoId: String, cursor: String?) -> LiveChatPoll?,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _messages = MutableStateFlow<List<LiveChatMessage>>(emptyList())
    val messages: StateFlow<List<LiveChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private var videoId: String? = null
    private var panelVisible = false
    private var playing = true
    private var probeJob: Job? = null
    private var dripJob: Job? = null
    private var pendingBatch: LiveChatPoll? = null
    private var cursor: String? = null
    private var shownFirstBatch = false
    private val seen = LinkedHashSet<String>()

    private val shouldPoll: Boolean get() = panelVisible && playing

    fun start(videoId: String) {
        if (this.videoId == videoId) return
        stop()
        this.videoId = videoId
        seen.clear()
        shownFirstBatch = false
        _messages.value = emptyList()
        _isLoading.value = true
        _isAvailable.value = false

        probeJob =
            scope.launch(dispatcher) {
                val first = fetch(videoId, null)
                if (this@LiveChatController.videoId != videoId) return@launch
                _isLoading.value = false
                if (first == null) {
                    _isAvailable.value = false
                    return@launch
                }
                _isAvailable.value = true
                pendingBatch = first
                if (shouldPoll) startDrip(videoId)
            }
    }

    fun stop() {
        probeJob?.cancel()
        probeJob = null
        dripJob?.cancel()
        dripJob = null
        videoId = null
        pendingBatch = null
        cursor = null
    }

    fun setPanelVisible(visible: Boolean) {
        if (panelVisible == visible) return
        panelVisible = visible
        onGateChanged()
    }

    /** Paused playback stops the polling; resuming picks it up again if the chat is on screen. */
    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying) return
        playing = isPlaying
        onGateChanged()
    }

    private fun onGateChanged() {
        if (!shouldPoll) {
            dripJob?.cancel()
            dripJob = null
            return
        }
        videoId?.takeIf { _isAvailable.value }?.let(::startDrip)
    }

    private fun startDrip(videoId: String) {
        if (dripJob?.isActive == true) return

        dripJob =
            scope.launch(dispatcher) {
                var consecutiveFailures = 0
                while (isActive && this@LiveChatController.videoId == videoId) {
                    val batch = pendingBatch ?: fetch(videoId, cursor)
                    pendingBatch = null
                    if (batch == null) {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_FAILURES) break
                        // A cursor held across a long pause may have lapsed; start the chat afresh.
                        cursor = null
                        delay(RETRY_MS)
                        continue
                    }
                    consecutiveFailures = 0
                    cursor = batch.next
                    show(batch, videoId)
                    if (cursor == null) break
                }
            }
    }

    /** Drips [batch]'s new messages across the wait it asks for, so a busy chat reads as a stream. */
    private suspend fun show(
        batch: LiveChatPoll,
        videoId: String,
    ) {
        val fresh = batch.messages.filter { seen.add(it.id) }
        val visibleFresh =
            if (shownFirstBatch) {
                fresh
            } else {
                shownFirstBatch = true
                fresh.takeLast(INITIAL_BACKFILL_MESSAGES)
            }
        while (seen.size > MAX_SEEN_IDS) {
            val oldest = seen.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        val waitMs = batch.pollAfterMs.coerceAtLeast(MIN_POLL_MS)
        if (visibleFresh.isEmpty()) {
            delay(waitMs)
            return
        }
        val interval = (waitMs / visibleFresh.size).coerceIn(MIN_DRIP_MS, MAX_DRIP_MS)
        var consumed = 0L
        for (message in visibleFresh) {
            if (this.videoId != videoId) return
            append(message)
            delay(interval)
            consumed += interval
        }
        if (consumed < waitMs) delay(waitMs - consumed)
    }

    private fun append(message: LiveChatMessage) {
        _messages.update { current ->
            val combined = current + message
            if (combined.size > MAX_MESSAGES) combined.takeLast(MAX_MESSAGES) else combined
        }
    }

    private companion object {
        const val MAX_MESSAGES = 200
        const val MAX_SEEN_IDS = 1500
        const val RETRY_MS = 3000L
        const val MAX_FAILURES = 6
        const val INITIAL_BACKFILL_MESSAGES = 12
        const val MIN_POLL_MS = 1_000L
        const val MIN_DRIP_MS = 90L
        const val MAX_DRIP_MS = 250L
    }
}
