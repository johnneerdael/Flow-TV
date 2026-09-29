package io.github.aedev.flow.ui.screens.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The player screen's one stream load at a time, and the token that says which load is current.
 *
 * Every writer of the screen state gates on [isCurrent]; a new load, a cancellation and a video the
 * screen gives up all move the token on, so whatever the superseded load still produces is dropped.
 */
internal class PlaybackLoadJobs(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val onCancel: () -> Unit,
) {
    var token: Long = 0L
        private set

    private var activeJob: Job? = null
    private var loadingVideoId: String? = null

    val isInFlight: Boolean get() = activeJob?.isActive == true

    fun next(): Long {
        token += 1L
        return token
    }

    fun isCurrent(token: Long): Boolean = this.token == token

    /** Whether a load for [videoId] is already running, so a repeat trigger adds nothing. */
    fun isLoading(videoId: String): Boolean = isInFlight && loadingVideoId == videoId

    fun cancel(invalidateToken: Boolean = false) {
        if (invalidateToken) next()
        activeJob?.cancel()
        activeJob = null
        loadingVideoId = null
        onCancel()
    }

    /** Cancels what runs, then runs [block] as the current load for [videoId]. */
    fun launch(
        videoId: String,
        block: suspend CoroutineScope.(LoadContext) -> Unit,
    ) {
        cancel()
        val loadToken = next()
        loadingVideoId = videoId
        activeJob =
            scope.launch(dispatcher) {
                try {
                    block(LoadContext(videoId, loadToken))
                } finally {
                    if (isCurrent(loadToken)) {
                        activeJob = null
                    }
                }
            }
    }
}
