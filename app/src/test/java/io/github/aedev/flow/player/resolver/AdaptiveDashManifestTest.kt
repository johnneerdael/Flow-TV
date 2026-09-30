package io.github.aedev.flow.player.resolver

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.playback.PluginVideoStreams
import io.github.aedev.flow.plugin.playback.PluginVideoStreamsTest.Companion.audioOriginal
import io.github.aedev.flow.plugin.playback.PluginVideoStreamsTest.Companion.video1080
import nl.neerdael.milkbeat.plugin.ByteRange
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AdaptiveDashManifestTest {
    private fun vp9(
        height: Int,
        itag: Int,
        bitrate: Int,
    ) = MediaFormat(
        id = "$itag:1700000000",
        type = FormatType.VIDEO,
        url = "https://rr1.invalid/videoplayback?itag=$itag&c=VISIONOS&n=a&sig=b",
        mimeType = "video/webm",
        codecs = "vp9",
        width = height * 16 / 9,
        height = height,
        fps = 25,
        bitrate = bitrate,
        durationMs = 212_000,
        initRange = ByteRange(0, 219),
        indexRange = ByteRange(220, 900),
    )

    private val videos =
        PluginVideoStreams.videoStreams(listOf(vp9(2160, 313, 18_000_000), vp9(1080, 248, 3_000_000), vp9(720, 247, 1_500_000), video1080))
    private val audio = PluginVideoStreams.audioStreams(listOf(audioOriginal)).single()

    private fun parse(manifest: String) =
        DashManifestParser().parse(Uri.parse("https://rr1.invalid/"), ByteArrayInputStream(manifest.toByteArray()))

    @Test
    fun `every picture becomes a representation, one adaptation set per codec, beside the audio`() {
        val manifest = parse(AdaptiveDashManifest.build(videos, audio, durationSeconds = 212)!!)

        val period = manifest.getPeriod(0)
        val video = period.adaptationSets.filter { it.type == C.TRACK_TYPE_VIDEO }
        assertThat(video.map { set -> set.representations.map { it.format.height } })
            .containsExactly(listOf(720, 1080, 2160), listOf(1080))
        val audioSet = period.adaptationSets.single { it.type == C.TRACK_TYPE_AUDIO }
        assertThat(
            audioSet.representations
                .single()
                .format.language,
        ).isEqualTo("en")
        assertThat(manifest.durationMs).isEqualTo(212_000L)
    }

    @Test
    fun `URLs keep every query parameter and the byte ranges address the index and init`() {
        val manifest = parse(AdaptiveDashManifest.build(videos, audio, durationSeconds = 212)!!)

        val top =
            manifest
                .getPeriod(0)
                .adaptationSets
                .flatMap { it.representations }
                .single { it.format.height == 2160 }
        assertThat(top.baseUrls.single().url).isEqualTo("https://rr1.invalid/videoplayback?itag=313&c=VISIONOS&n=a&sig=b")
        assertThat(top.indexUri!!.start).isEqualTo(220)
        assertThat(top.initializationUri!!.length).isEqualTo(220)
        assertThat(top.format.bitrate).isEqualTo(18_000_000)
    }

    @Test
    fun `nothing to adapt between, or no ranges to address, means no manifest`() {
        assertThat(AdaptiveDashManifest.build(videos.take(1), audio, durationSeconds = 212)).isNull()
        val unaddressable =
            PluginVideoStreams.videoStreams(
                listOf(vp9(1080, 248, 1).copy(indexRange = null), vp9(720, 247, 1).copy(indexRange = null)),
            )
        assertThat(AdaptiveDashManifest.build(unaddressable, audio, durationSeconds = 212)).isNull()
        assertThat(AdaptiveDashManifest.build(videos, audio, durationSeconds = 0)).isNull()
    }
}
