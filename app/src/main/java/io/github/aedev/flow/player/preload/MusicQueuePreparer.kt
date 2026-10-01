package io.github.aedev.flow.player.preload

import io.github.aedev.flow.plugin.playback.QueuePreparationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal data class QueuePreparationTarget(
    val uid: Any,
    val uri: String,
)

internal class MusicQueuePreparer(
    scope: CoroutineScope,
    private val targets: () -> List<QueuePreparationTarget>,
    private val resolve: suspend (QueuePreparationTarget) -> QueuePreparationResult,
    private val remove: (QueuePreparationTarget) -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                val visited = HashSet<QueuePreparationTarget>()
                var candidates = targets().take(MAX_TRACKS)
                while (visited.size < MAX_TRACKS) {
                    if (requests.tryReceive().isSuccess) candidates = targets().take(MAX_TRACKS)
                    val target = candidates.firstOrNull { it !in visited } ?: break
                    visited += target
                    val result =
                        try {
                            resolve(target)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            QueuePreparationResult.Retryable
                        }
                    if (result is QueuePreparationResult.Unmatched && target in targets().take(MAX_TRACKS) &&
                        result.isCurrent()
                    ) {
                        remove(target)
                        candidates = targets().take(MAX_TRACKS)
                    }
                }
            }
        }
    }

    fun schedule() {
        requests.trySend(Unit)
    }

    companion object {
        const val MAX_TRACKS = 100
    }
}
