package io.github.aedev.flow.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import io.github.aedev.flow.player.resolver.ResolvingMusicMediaSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ResolvingMusicMediaSourceTest {
    private val item = MediaItem.fromUri("music://fixture")
    private val bandwidth = mockk<BandwidthMeter>(relaxed = true)

    @Test
    fun `resolved child prepares once forwards its timeline and releases`() {
        val child = mockk<MediaSource>(relaxed = true)
        every { child.prepareSource(any(), any<PlayerId>(), any<BandwidthMeter>()) } answers {
            firstArg<MediaSource.MediaSourceCaller>().onSourceInfoRefreshed(child, Timeline.EMPTY)
        }
        var refreshed = false
        val caller = MediaSource.MediaSourceCaller { _, timeline -> refreshed = timeline === Timeline.EMPTY }
        val source = ResolvingMusicMediaSource(item) { child }
        source.prepareSource(caller, PlayerId.UNSET, bandwidth)
        try {
            await { refreshed }
            verify(exactly = 1) { child.prepareSource(any(), any<PlayerId>(), any<BandwidthMeter>()) }
        } finally {
            source.releaseSource(caller)
        }
        verify(exactly = 1) { child.releaseSource(any()) }
    }

    @Test
    fun `resolution failure reaches the player as a source error`() {
        val resolved = CountDownLatch(1)
        val source =
            ResolvingMusicMediaSource(item) {
                resolved.countDown()
                throw IOException("fixture failure")
            }
        val caller = MediaSource.MediaSourceCaller { _, _ -> }
        source.prepareSource(caller, PlayerId.UNSET, bandwidth)
        try {
            assertTrue(resolved.await(5, TimeUnit.SECONDS))
            await { runCatching { source.maybeThrowSourceInfoRefreshError() }.isFailure }
            assertEquals("fixture failure", assertThrows(IOException::class.java) { source.maybeThrowSourceInfoRefreshError() }.message)
        } finally {
            source.releaseSource(caller)
        }
    }

    @Test
    fun `release cancels resolution without preparing a late child`() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val gate = CompletableDeferred<MediaSource>()
        val source =
            ResolvingMusicMediaSource(item) {
                started.countDown()
                try {
                    gate.await()
                    awaitCancellation()
                } finally {
                    cancelled.countDown()
                }
            }
        val caller = MediaSource.MediaSourceCaller { _, _ -> }
        source.prepareSource(caller, PlayerId.UNSET, bandwidth)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        source.releaseSource(caller)
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        source.maybeThrowSourceInfoRefreshError()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue(condition())
    }
}
