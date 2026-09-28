package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.MusicVideoItems
import org.junit.Test

class MusicVideoFormatsTest {
    private fun format(
        itag: Int,
        mimeType: String,
        height: Int? = null,
        bitrate: Int = 1_000_000,
    ) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = "https://example/$itag",
        mimeType = mimeType,
        bitrate = bitrate,
        width = height?.let { it * 16 / 9 },
        height = height,
        contentLength = null,
        quality = "",
        fps = 30,
        qualityLabel = height?.let { "${it}p" },
        averageBitrate = null,
        audioQuality = null,
        approxDurationMs = null,
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
    )

    private val h264At1080 = format(137, "video/mp4; codecs=\"avc1.640028\"", 1080)
    private val h264At720 = format(136, "video/mp4; codecs=\"avc1.4d401f\"", 720)
    private val vp9At1080 = format(248, "video/webm; codecs=\"vp9\"", 1080, bitrate = 2_000_000)
    private val av1At2160 = format(401, "video/mp4; codecs=\"av01.0.12M.08\"", 2160)
    private val audio = format(251, "audio/webm; codecs=\"opus\"")
    private val all = listOf(audio, h264At720, h264At1080, vp9At1080, av1At2160)

    @Test
    fun `the tallest stream the display takes wins, never taller`() {
        val chosen = MusicVideoFormats.select(all, maxHeight = 1080, codecPreference = null, hardwareCodecs = setOf("h264", "vp9", "av1"))

        assertThat(chosen?.height).isEqualTo(1080)
    }

    @Test
    fun `without a preference H264 wins a tie, being the safest decode`() {
        val chosen = MusicVideoFormats.select(all, maxHeight = 1080, codecPreference = null, hardwareCodecs = setOf("h264", "vp9"))

        assertThat(chosen).isEqualTo(h264At1080)
    }

    @Test
    fun `the user's codec preference breaks a tie`() {
        val chosen = MusicVideoFormats.select(all, maxHeight = 1080, codecPreference = "vp9", hardwareCodecs = setOf("h264", "vp9"))

        assertThat(chosen).isEqualTo(vp9At1080)
    }

    @Test
    fun `a codec the device cannot decode in hardware is never chosen`() {
        val chosen =
            MusicVideoFormats.select(
                listOf(av1At2160, format(399, "video/mp4; codecs=\"av01.0.08M.08\"", 1080), h264At720),
                maxHeight = 1080,
                codecPreference = "av1",
                hardwareCodecs = setOf("h264"),
            )

        assertThat(chosen).isEqualTo(h264At720)
    }

    @Test
    fun `a track without a video stream has none`() {
        assertThat(MusicVideoFormats.select(listOf(audio), 1080, null, setOf("h264"))).isNull()
    }

    @Test
    fun `a music video's picture is cached apart from its sound`() {
        assertThat(MusicVideoItems.videoKey("abc")).isNotEqualTo("abc")
        assertThat(MusicVideoItems.videoIdOfVideoKey(MusicVideoItems.videoKey("abc"))).isEqualTo("abc")
        assertThat(MusicVideoItems.videoIdOfVideoKey("abc")).isNull()
    }
}
