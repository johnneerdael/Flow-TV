package io.github.aedev.flow.player.resolver

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(UnstableApi::class)
internal class ResolvingMusicMediaSource(
    private val item: MediaItem,
    private val resolve: suspend () -> MediaSource,
) : CompositeMediaSource<Unit>() {
    private var scope: CoroutineScope? = null
    private var child: MediaSource? = null
    private var failure: IOException? = null

    override fun getMediaItem(): MediaItem = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        val handler = Handler(checkNotNull(Looper.myLooper()))
        val preparing = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = preparing
        preparing.launch {
            try {
                val source = resolve()
                handler.post {
                    if (scope === preparing && preparing.isActive) {
                        try {
                            child = source
                            prepareChildSource(Unit, source)
                        } catch (e: Exception) {
                            failure = if (e is IOException) e else IOException(e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException && !preparing.isActive) throw e
                handler.post {
                    if (scope === preparing && preparing.isActive) failure = if (e is IOException) e else IOException(e)
                }
            }
        }
    }

    override fun maybeThrowSourceInfoRefreshError() {
        failure?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: Unit,
        mediaSource: MediaSource,
        newTimeline: Timeline,
    ) {
        refreshSourceInfo(newTimeline)
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = checkNotNull(child).createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        checkNotNull(child).releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        scope?.cancel()
        scope = null
        super.releaseSourceInternal()
        child = null
        failure = null
    }
}
