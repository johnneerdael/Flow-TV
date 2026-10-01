package io.github.aedev.flow.plugin

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.player.MusicMediaSourceFactory
import io.github.aedev.flow.player.datasource.PluginMusicDataSourceFactory
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PluginMusicPlaybackTest {
    @Test
    fun progressiveResolutionPlaysAndKeepsQueueContinuity() = playback(hls = false)

    @Test
    fun hlsFallbackPlaysEvenWhenFirstProviderDeclaredProgressive() = playback(hls = true)

    private fun playback(hls: Boolean) =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val directory = File(context.cacheDir, "plugin-source-fixture").apply { mkdirs() }
            val clip = File(directory, "silence.aac")
            instrumentation.context.assets
                .open("player/silence.aac")
                .use { input -> clip.outputStream().use(input::copyTo) }
            val playlist = File(directory, "audio.m3u8")
            playlist.writeText(
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:9\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:8.1,\nsilence.aac\n#EXT-X-ENDLIST\n",
            )
            val url = Uri.fromFile(if (hls) playlist else clip).toString()
            val resolutions = AtomicInteger()
            val upstream = DefaultDataSource.Factory(context)
            val dataSource =
                PluginMusicDataSourceFactory(
                    delegate = upstream,
                    resolve = { _, _ ->
                        resolutions.incrementAndGet()
                        ResolvedAudio(
                            "fallback",
                            TrackDescriptor(EntityRef(EntityKind.TRACK, "fixture"), "Fixture"),
                            AudioStream(url, "fixture", "aac", if (hls) "application/x-mpegURL" else "audio/aac"),
                            Long.MAX_VALUE,
                            false,
                        )
                    },
                    bind = { audio ->
                        ResolvingDataSource.Factory(upstream) { spec ->
                            if (spec.uri.scheme == "music") spec.withUri(Uri.parse(audio.stream.url)) else spec
                        }
                    },
                )
            val player =
                withContext(Dispatchers.Main) {
                    ExoPlayer
                        .Builder(context)
                        .setMediaSourceFactory(MusicMediaSourceFactory(DefaultMediaSourceFactory(context), dataSource) { false })
                        .build()
                        .apply {
                            volume = 0f
                            setMediaItems(listOf(MediaItem.fromUri("music://first"), MediaItem.fromUri("music://second")))
                            prepare()
                            play()
                        }
                }
            try {
                waitForPlayback(player, 0)
                withContext(Dispatchers.Main) { player.pause() }
                assertTrue(withContext(Dispatchers.Main) { !player.playWhenReady })
                withContext(Dispatchers.Main) {
                    player.seekToNextMediaItem()
                    player.play()
                }
                waitForPlayback(player, 1)
                assertEquals(2, resolutions.get())
            } finally {
                withContext(Dispatchers.Main) { player.release() }
                directory.deleteRecursively()
            }
        }

    private suspend fun waitForPlayback(
        player: ExoPlayer,
        index: Int,
    ) {
        withTimeout(20_000) {
            while (!withContext(Dispatchers.Main) {
                    player.playerError?.let { throw AssertionError("Fixture playback failed", it) }
                    player.currentMediaItemIndex == index && player.playbackState == Player.STATE_READY && player.currentPosition > 400
                }
            ) {
                delay(100)
            }
        }
    }
}
