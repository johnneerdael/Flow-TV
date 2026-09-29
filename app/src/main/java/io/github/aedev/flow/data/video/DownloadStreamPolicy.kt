package io.github.aedev.flow.data.video

import io.github.aedev.flow.player.stream.VideoCodecUtils
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Which streams a download offers and which audio track it pairs with a video track. Pure policy:
 * no Compose, no resources, no Android UI, so every rule here is unit-testable.
 */
object DownloadStreamPolicy {
    /**
     * Codec order within one resolution for a *download*, which is deliberately not
     * [VideoCodecUtils.playbackCodecRank]: playback ranks h264 first because it is the codec every
     * decoder handles, while a download ranks vp9 first because it is the smallest file the app can
     * mux reliably, and av1 above the legacy vp8/hevc ladder.
     */
    val DOWNLOAD_CODEC_PRIORITY = mapOf("vp9" to 0, "h264" to 1, "av1" to 2, "vp8" to 3, "hevc" to 4)

    private const val UNRANKED_CODEC = 99

    /**
     * The video ladder both download dialogs show: one entry per (resolution, codec), highest
     * resolution first, [DOWNLOAD_CODEC_PRIORITY] within a resolution. Streams with no URL are
     * dropped — selecting one can only fail.
     */
    fun buildDownloadVideoStreams(
        innerTubeStreams: List<VideoStream>,
        videoOnlyStreams: List<VideoStream>,
        muxedStreams: List<VideoStream>,
    ): List<VideoStream> =
        (innerTubeStreams + videoOnlyStreams + muxedStreams)
            .filter { it.getContent().isNotBlank() }
            .distinctBy {
                VideoCodecUtils.streamSizeKey(
                    VideoCodecUtils.qualityHeightFromStream(it),
                    VideoCodecUtils.codecKeyFromStream(it),
                )
            }.sortedWith(
                compareByDescending<VideoStream> { VideoCodecUtils.qualityHeightFromStream(it) }
                    .thenBy { DOWNLOAD_CODEC_PRIORITY[VideoCodecUtils.codecKeyFromStream(it)] ?: UNRANKED_CODEC },
            )

    fun pickCompatibleAudioForVideo(
        videoCodecKey: String,
        allAudio: List<AudioStream>,
        preferredLang: String?,
    ): AudioStream? {
        if (allAudio.isEmpty()) return null
        val isMp4Container = videoCodecKey == "h264" || videoCodecKey == "hevc"

        fun isAacCompatible(a: AudioStream): Boolean {
            val fmt = (a.format?.name ?: "").lowercase()
            val mime = (a.format?.mimeType ?: "").lowercase()
            return !fmt.contains("opus") && !fmt.contains("vorbis") &&
                !fmt.contains("webm") && !mime.contains("opus") &&
                !mime.contains("vorbis") && !mime.contains("webm")
        }

        val langFilteredAudio =
            if (!preferredLang.isNullOrEmpty() && preferredLang != "original") {
                val langMatches =
                    allAudio.filter {
                        it.audioLocale?.language.equals(preferredLang, ignoreCase = true) ||
                            it.audioLocale?.toLanguageTag().equals(preferredLang, ignoreCase = true)
                    }
                if (langMatches.isNotEmpty()) langMatches else allAudio
            } else {
                val originals = allAudio.filter { it.audioTrackType == AudioTrackType.ORIGINAL }
                if (originals.isNotEmpty()) {
                    originals
                } else {
                    val nonDubbed = allAudio.filter { it.audioTrackType != AudioTrackType.DUBBED }
                    if (nonDubbed.isNotEmpty()) nonDubbed else allAudio
                }
            }

        return if (isMp4Container) {
            langFilteredAudio.filter { isAacCompatible(it) }.maxByOrNull { it.bitrate }
                ?: allAudio.filter { isAacCompatible(it) }.maxByOrNull { it.bitrate }
        } else {
            val opusFilter: (AudioStream) -> Boolean = { a ->
                val fmt = a.format?.name ?: ""
                val mime = a.format?.mimeType ?: ""
                fmt.contains("webm", true) || mime.contains("audio/webm", true) ||
                    fmt.contains("opus", true) || mime.contains("opus", true)
            }
            langFilteredAudio.filter(opusFilter).maxByOrNull { it.bitrate }
                ?: allAudio.filter(opusFilter).maxByOrNull { it.bitrate }
                ?: langFilteredAudio.maxByOrNull { it.bitrate }
                ?: allAudio.maxByOrNull { it.bitrate }
        }
    }
}
