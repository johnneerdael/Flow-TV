package io.github.aedev.flow.player.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Retry policy for sidecar subtitle fetches.
 *
 * YouTube throttles `timedtext` requests that carry `&tlang=` far more aggressively than plain
 * caption fetches — a 429 on a translated track while the untranslated one loads fine from the same
 * IP seconds later is routine. The default three quick attempts frequently fall inside one throttle
 * window, so translated captions get one shot and then look permanently broken; backing off further
 * usually rides it out. Statuses that retrying cannot fix are given up on immediately.
 */
@UnstableApi
internal class SubtitleLoadErrorHandlingPolicy(
    private val isTranslated: Boolean,
) : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (isTranslated) TRANSLATED_MAX_ATTEMPTS else MAX_ATTEMPTS

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val status =
            (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                ?: return super.getRetryDelayMsFor(loadErrorInfo)
        val isTransient = status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR
        if (!isTransient) return C.TIME_UNSET
        val exponent = (loadErrorInfo.errorCount - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
        val initial = if (isTranslated) TRANSLATED_INITIAL_BACKOFF_MS else INITIAL_BACKOFF_MS
        val ceiling = if (isTranslated) TRANSLATED_MAX_BACKOFF_MS else MAX_BACKOFF_MS
        return (initial shl exponent).coerceAtMost(ceiling)
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8_000L

        // A tlang fetch is not rate-limited, it is refused by Google's abuse interstitial: no
        // Retry-After, and it hardens against the IP as attempts continue. Fewer, slower tries
        // give the block time to lapse while the caller falls back to the source track.
        const val TRANSLATED_MAX_ATTEMPTS = 3
        const val TRANSLATED_INITIAL_BACKOFF_MS = 2_000L
        const val TRANSLATED_MAX_BACKOFF_MS = 20_000L
        const val MAX_BACKOFF_EXPONENT = 4
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
