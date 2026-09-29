package io.github.aedev.flow.data.video

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale

/**
 * Pins the download policy against real NewPipe streams. `bitrate` on an [AudioStream] only exists
 * through its [ItagItem], which is why the fixture takes the two bitrates separately: the helpers
 * treat them differently.
 */
class DownloadStreamPolicyTest {
    private fun audio(
        id: String,
        format: MediaFormat? = MediaFormat.M4A,
        averageBitrate: Int = AudioStream.UNKNOWN_BITRATE,
        itagBitrate: Int? = null,
        trackId: String? = null,
        trackName: String? = null,
        locale: Locale? = null,
        trackType: AudioTrackType? = null,
        content: String = "https://example.invalid/$id",
    ): AudioStream {
        val builder =
            AudioStream
                .Builder()
                .setId(id)
                .setContent(content, true)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setAverageBitrate(averageBitrate)
        format?.let { builder.setMediaFormat(it) }
        trackId?.let { builder.setAudioTrackId(it) }
        trackName?.let { builder.setAudioTrackName(it) }
        locale?.let { builder.setAudioLocale(it) }
        trackType?.let { builder.setAudioTrackType(it) }
        itagBitrate?.let { bitrate ->
            val item = ItagItem(id.toIntOrNull() ?: 140, ItagItem.ItagType.AUDIO, format ?: MediaFormat.M4A, 0)
            item.bitrate = bitrate
            builder.setItagItem(item)
        }
        return builder.build()
    }

    /** The itag in [id] is what decides the codec: 137 is h264, 248/247 vp9, 399 av1. */
    private fun video(
        id: String,
        resolution: String,
        content: String = "https://example.invalid/$id",
    ): VideoStream =
        VideoStream
            .Builder()
            .setId(id)
            .setContent(content, true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setResolution(resolution)
            .setIsVideoOnly(true)
            .build()

    private fun build(
        innerTube: List<VideoStream> = emptyList(),
        videoOnly: List<VideoStream> = emptyList(),
        muxed: List<VideoStream> = emptyList(),
    ) = DownloadStreamPolicy.buildDownloadVideoStreams(innerTube, videoOnly, muxed)

    private fun pick(
        codec: String,
        audio: List<AudioStream>,
        preferredLang: String? = null,
    ) = DownloadStreamPolicy.pickCompatibleAudioForVideo(codec, audio, preferredLang)

    @Test
    fun `mp4 containers take aac even when opus has the higher bitrate`() {
        val opus = audio("251", format = MediaFormat.WEBMA_OPUS, itagBitrate = 160_000)
        val aac = audio("140", itagBitrate = 128_000)

        listOf("h264", "hevc").forEach { codec ->
            assertThat(pick(codec, listOf(opus, aac))).isSameInstanceAs(aac)
        }
    }

    @Test
    fun `webm containers take opus even when aac has the higher bitrate`() {
        val opus = audio("250", format = MediaFormat.WEBMA_OPUS, itagBitrate = 70_000)
        val aac = audio("141", itagBitrate = 256_000)

        listOf("vp9", "av1", "vp8").forEach { codec ->
            assertThat(pick(codec, listOf(aac, opus))).isSameInstanceAs(opus)
        }
    }

    @Test
    fun `webm containers fall back to any audio when there is no opus`() {
        val aac = audio("140", itagBitrate = 128_000)

        assertThat(pick("vp9", listOf(aac))).isSameInstanceAs(aac)
    }

    @Test
    fun `mp4 containers have no last resort`() {
        // Pins current behaviour: an h264 download offered only opus audio gets no audio at all.
        val opus = audio("251", format = MediaFormat.WEBMA_OPUS, itagBitrate = 160_000)

        assertThat(pick("h264", listOf(opus))).isNull()
        assertThat(pick("hevc", listOf(opus))).isNull()
    }

    @Test
    fun `an empty list yields nothing for every container`() {
        assertThat(pick("h264", emptyList())).isNull()
        assertThat(pick("vp9", emptyList())).isNull()
    }

    @Test
    fun `the pick ranks by the itag bitrate not the average bitrate`() {
        // Pins current behaviour: the comparison reads AudioStream.bitrate, which only an ItagItem
        // populates, so a stream with a rich averageBitrate but no ItagItem ranks as 0 kbps.
        val richAverage = audio("140", averageBitrate = 256_000)
        val richItag = audio("139", averageBitrate = 48_000, itagBitrate = 50_000)

        assertThat(pick("h264", listOf(richAverage, richItag))).isSameInstanceAs(richItag)
    }

    @Test
    fun `streams without itag metadata tie and the first one wins`() {
        // Pins current behaviour.
        val first = audio("140", averageBitrate = 48_000)
        val second = audio("141", averageBitrate = 256_000)

        assertThat(pick("h264", listOf(first, second))).isSameInstanceAs(first)
        assertThat(pick("vp9", listOf(first, second))).isSameInstanceAs(first)
    }

    @Test
    fun `a preferred language wins over the original track`() {
        val en = audio("140", itagBitrate = 128_000, locale = Locale.ENGLISH, trackType = AudioTrackType.ORIGINAL)
        val fr = audio("140", itagBitrate = 128_000, locale = Locale.FRANCE, trackType = AudioTrackType.DUBBED)

        assertThat(pick("h264", listOf(en, fr), preferredLang = "fr")).isSameInstanceAs(fr)
        assertThat(pick("h264", listOf(en, fr), preferredLang = "FR")).isSameInstanceAs(fr)
        assertThat(pick("h264", listOf(en, fr), preferredLang = "fr-FR")).isSameInstanceAs(fr)
    }

    @Test
    fun `a regional preference does not match a bare language locale`() {
        // Pins current behaviour: "fr-FR" is compared against the locale's language ("fr") and its
        // full tag ("fr"), neither of which equals it, so the pick falls through to every track.
        val en = audio("140", itagBitrate = 128_000, locale = Locale.ENGLISH, trackType = AudioTrackType.ORIGINAL)
        val fr = audio("140", itagBitrate = 128_000, locale = Locale.FRENCH, trackType = AudioTrackType.DUBBED)

        assertThat(pick("h264", listOf(en, fr), preferredLang = "fr-FR")).isSameInstanceAs(en)
    }

    @Test
    fun `a preferred language with no match ignores the original flag`() {
        // Pins current behaviour: the fallback is every track by bitrate, not the original-first
        // policy used when there is no preference at all.
        val en = audio("140", itagBitrate = 128_000, locale = Locale.ENGLISH, trackType = AudioTrackType.ORIGINAL)
        val frLoud = audio("141", itagBitrate = 256_000, locale = Locale.FRENCH, trackType = AudioTrackType.DUBBED)

        assertThat(pick("h264", listOf(en, frLoud), preferredLang = "de")).isSameInstanceAs(frLoud)
    }

    @Test
    fun `without a preference the original track wins then non dubbed then anything`() {
        val original = audio("140", itagBitrate = 128_000, locale = Locale.ENGLISH, trackType = AudioTrackType.ORIGINAL)
        val dubbed = audio("141", itagBitrate = 256_000, locale = Locale.FRENCH, trackType = AudioTrackType.DUBBED)
        val untyped = audio("139", itagBitrate = 48_000)

        listOf(null, "", "original").forEach { preference ->
            assertThat(pick("h264", listOf(dubbed, original, untyped), preference)).isSameInstanceAs(original)
        }
        assertThat(pick("h264", listOf(dubbed, untyped))).isSameInstanceAs(untyped)
        assertThat(pick("h264", listOf(dubbed))).isSameInstanceAs(dubbed)
    }

    @Test
    fun `the container rule outranks the language filter`() {
        val frOpus =
            audio(
                "251",
                format = MediaFormat.WEBMA_OPUS,
                itagBitrate = 160_000,
                locale = Locale.FRENCH,
                trackType = AudioTrackType.DUBBED,
            )
        val enAac = audio("140", itagBitrate = 128_000, locale = Locale.ENGLISH, trackType = AudioTrackType.ORIGINAL)

        assertThat(pick("h264", listOf(frOpus, enAac), preferredLang = "fr")).isSameInstanceAs(enAac)
        assertThat(pick("vp9", listOf(frOpus, enAac), preferredLang = "en")).isSameInstanceAs(frOpus)
    }

    @Test
    fun `a stream with no format counts as aac compatible`() {
        // Pins current behaviour.
        val unknown = audio("0", format = null, itagBitrate = 1)

        assertThat(pick("h264", listOf(unknown))).isSameInstanceAs(unknown)
    }

    @Test
    fun `the ladder is one row per resolution and codec, tallest first and vp9 ahead of h264 ahead of av1`() {
        val vp91080 = video("248", "1080p")
        val h2641080 = video("137", "1080p")
        val av11080 = video("399", "1080p")
        val vp9720 = video("247", "720p")

        assertThat(build(innerTube = listOf(av11080, h2641080), videoOnly = listOf(vp91080), muxed = listOf(vp9720)))
            .containsExactly(vp91080, h2641080, av11080, vp9720)
            .inOrder()
    }

    @Test
    fun `a stream with no url never reaches the ladder`() {
        // The classic dialog used to list these and then do nothing when one was tapped.
        val playable = video("137", "720p")
        val urlless = video("248", "1080p", content = "")

        assertThat(build(innerTube = listOf(urlless), videoOnly = listOf(playable))).containsExactly(playable)
    }

    @Test
    fun `the first source wins when two carry the same resolution and codec`() {
        val innerTube = video("137", "1080p")
        val extractor = video("137", "1080p", content = "https://example.invalid/extractor")

        assertThat(build(innerTube = listOf(innerTube), videoOnly = listOf(extractor))).containsExactly(innerTube)
    }
}
