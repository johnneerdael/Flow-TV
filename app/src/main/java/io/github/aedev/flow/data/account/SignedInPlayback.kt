package io.github.aedev.flow.data.account

import android.util.LruCache
import io.github.aedev.flow.innertube.InnerTube
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The account a track is played as: its request session and the visitor identity its PoTokens are bound to. */
class PlaybackAccount(
    val tube: InnerTube,
    val visitorData: String,
)

/**
 * Plays music as the signed-in account, as YouTube Music itself does: the stream request runs on the
 * account session, and the playback-tracking address it returns is what adds the play to the account's
 * history. Signed out, [account] is null and playback stays on the anonymous clients.
 */
@Singleton
class SignedInPlayback
    @Inject
    constructor(
        private val store: AccountSessionStore,
        private val client: AccountFeedClient,
    ) {
        private val tracking = LruCache<String, String>(TRACKING_ENTRIES)

        /**
         * The signed-in account's visitor identity, or null signed out. PoTokens are minted against it so
         * one BotGuard session serves music and video alike.
         */
        val identity: Flow<String?> =
            store.session
                .map { session -> session?.takeUnless { it.expired }?.visitorData }
                .distinctUntilChanged()

        suspend fun account(): PlaybackAccount? {
            val visitorData = store.current()?.takeUnless { it.expired }?.visitorData ?: return null
            val tube = client.tube() ?: return null
            return PlaybackAccount(tube, visitorData)
        }

        fun rememberTracking(
            videoId: String,
            playbackUrl: String,
        ) {
            tracking.put(videoId, playbackUrl)
        }

        fun trackingFor(videoId: String): String? = tracking.get(videoId)

        fun forgetTracking() {
            tracking.evictAll()
        }

        private companion object {
            const val TRACKING_ENTRIES = 200
        }
    }
