package io.github.aedev.flow.plugin.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.VideoStreamSource
import io.github.aedev.flow.player.stream.ResolvedStreamData
import io.github.aedev.flow.player.stream.ServicePlaybackStreamSelector
import io.github.aedev.flow.utils.NetworkState
import kotlinx.coroutines.flow.first
import nl.neerdael.milkbeat.plugin.VideoKind
import org.schabi.newpipe.extractor.stream.StreamType
import javax.inject.Inject
import javax.inject.Singleton

/** The player's own queue advance, autoplay and gapless preload, resolved through the video plugin. */
@Singleton
class PluginVideoStreamSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val pluginVideo: PluginVideo,
        private val preferences: PlayerPreferences,
    ) : VideoStreamSource {
        override suspend fun resolve(video: Video): ResolvedStreamData? {
            val playback =
                pluginVideo.resolve(video.id).getOrElse { error ->
                    Log.w(TAG, "No streams for ${video.id}: ${error.message}")
                    return null
                }
            if (playback.kind == VideoKind.UPCOMING) return null
            val playable = PluginVideoStreams.playable(playback, video, SystemClock.elapsedRealtime())
            val quality =
                if (NetworkState.isOnWifi(context)) preferences.defaultQualityWifi.first() else preferences.defaultQualityCellular.first()
            val codec = preferences.videoCodecPriority.first()
            val (videoStream, audioStream) =
                ServicePlaybackStreamSelector.selectStreams(
                    videoCandidates = playable.videoStreams,
                    audioCandidatesAll = playable.audioStreams,
                    preferredQuality = quality,
                    preferredAudioLanguage = preferences.preferredAudioLanguage.first(),
                    preferredCodecKey = codec,
                )
            return ResolvedStreamData(
                enrichedVideo = playable.video,
                videoStream = videoStream,
                audioStream = audioStream,
                videoStreams = playable.videoStreams,
                audioStreams = playable.audioStreams,
                subtitles = playable.subtitles,
                durationSeconds = playable.durationSeconds,
                dashManifestUrl = playable.dashUrl.takeIf { playable.isLive },
                streamType = if (playable.isLive) StreamType.LIVE_STREAM else StreamType.VIDEO_STREAM,
                relatedVideos = pluginVideo.related(video.id),
                preferredCodec = codec,
                hlsUrl = playable.hlsUrl,
                requestHeaders = playable.requestHeaders,
                skipSegments = playable.skipSegments,
            )
        }

        private companion object {
            const val TAG = "PluginVideoStreamSource"
        }
    }
