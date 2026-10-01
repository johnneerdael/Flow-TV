package io.github.aedev.flow.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import io.mockk.mockk
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicMediaSourceFactoryTest {
    private val factory = MusicMediaSourceFactory(mockk<MediaSource.Factory>(), mockk<DataSource.Factory>()) { false }
    private val item =
        MediaItem
            .Builder()
            .setUri(Uri.parse("music://spotify-song"))
            .setMediaId("spotify-song")
            .build()

    @Test
    fun `a Beatport fallback chooses HLS from the resolved stream`() {
        assertThat(factory.resolvedSource(item, audio("application/x-mpegURL"))).isInstanceOf(HlsMediaSource::class.java)
        assertThat(
            factory.resolvedSource(item, audio("application/vnd.apple.mpegurl; charset=utf-8")),
        ).isInstanceOf(HlsMediaSource::class.java)
    }

    @Test
    fun `YouTube and downloaded audio retain progressive sources`() {
        assertThat(factory.resolvedSource(item, audio("audio/mp4"))).isInstanceOf(ProgressiveMediaSource::class.java)
        assertThat(factory.resolvedSource(item, null)).isInstanceOf(ProgressiveMediaSource::class.java)
    }

    private fun audio(mime: String): ResolvedAudio =
        ResolvedAudio(
            "provider",
            TrackDescriptor(EntityRef(EntityKind.TRACK, "resolved"), "Song"),
            AudioStream("https://fixture/audio", "resolved", "aac", mime),
            Long.MAX_VALUE,
            false,
        )
}
