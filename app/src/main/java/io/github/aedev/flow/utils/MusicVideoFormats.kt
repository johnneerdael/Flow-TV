package io.github.aedev.flow.utils

import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.stream.VideoCodecUtils

/** Which of a track's video streams plays as its music video. */
object MusicVideoFormats {
    const val MAX_HEIGHT = 1080

    private const val H264 = "h264"

    /** Codec keys this device decodes in hardware; H.264 is always assumed playable. */
    val hardwareCodecs: Set<String> by lazy {
        listOf("h264", "vp9", "hevc", "av1")
            .filter { key ->
                val mime = VideoCodecUtils.mimeTypeForCodecKey(key) ?: return@filter false
                runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false).any { it.hardwareAccelerated } }.getOrDefault(false)
            }.toSet() + H264
    }

    /**
     * The tallest SDR stream within [maxHeight] in a codec the device decodes in hardware, the user's
     * codec preference breaking ties, then the higher bitrate. Null when the track has no video.
     */
    fun select(
        formats: List<PlayerResponse.StreamingData.Format>,
        maxHeight: Int,
        codecPreference: String?,
        hardwareCodecs: Set<String>,
    ): PlayerResponse.StreamingData.Format? =
        formats
            .filter { it.mimeType.startsWith("video/") && (it.height ?: 0) in 1..maxHeight && it.colorInfo?.isHdr != true }
            .filter { VideoCodecUtils.codecKeyFromMimeType(it.mimeType) in hardwareCodecs }
            .sortedWith(
                compareByDescending<PlayerResponse.StreamingData.Format> { it.height ?: 0 }
                    .thenBy { VideoCodecUtils.codecRankWithPreference(VideoCodecUtils.codecKeyFromMimeType(it.mimeType), codecPreference) }
                    .thenByDescending { it.averageBitrate ?: it.bitrate },
            ).firstOrNull()
}
