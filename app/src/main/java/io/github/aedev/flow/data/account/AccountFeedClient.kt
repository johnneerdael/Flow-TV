package io.github.aedev.flow.data.account

import io.github.aedev.flow.innertube.AccountEndpointPolicy
import io.github.aedev.flow.innertube.InnerTube
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.AccountInfo
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.pages.HistoryPage
import io.github.aedev.flow.innertube.pages.HomePage
import io.github.aedev.flow.innertube.pages.LibraryPage
import io.github.aedev.flow.innertube.pages.PlaylistPage
import io.github.aedev.flow.innertube.pages.account.AccountVideoFeed
import io.github.aedev.flow.innertube.pages.account.toAccountVideoFeed
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

const val LIKED_MUSIC_PLAYLIST_ID = "LM"
private const val LIBRARY_PLAYLISTS_BROWSE_ID = "FEmusic_liked_playlists"
private const val VIDEO_HOME_BROWSE_ID = "FEwhat_to_watch"
private const val SUBSCRIPTIONS_BROWSE_ID = "FEsubscriptions"
private const val WATCH_HISTORY_BROWSE_ID = "FEhistory"

/**
 * The only object that ever holds the account cookie. Its InnerTube is separate from the anonymous
 * `YouTube` one that playback uses, and its HTTP client refuses everything except feed reads.
 */
@Singleton
class AccountFeedClient
    @Inject
    constructor(
        private val store: AccountSessionStore,
    ) {
        private val mutex = Mutex()
        private var cached: Pair<AccountSession, InnerTube>? = null
        private var verifiedCookie: String? = null

        // YouTube Music answers a dead cookie with a generic signed-out home, so check the account first.
        suspend fun musicHome(continuation: String? = null): Result<HomePage> =
            withTube { tube ->
                verifyAccount(tube).mapCatching { YouTube.home(continuation = continuation, via = tube).getOrThrow() }
            }

        suspend fun musicHistory(): Result<HistoryPage> = withTube { YouTube.musicHistory(via = it) }

        suspend fun libraryPlaylists(): Result<LibraryPage> = withTube { YouTube.library(LIBRARY_PLAYLISTS_BROWSE_ID, via = it) }

        suspend fun playlist(playlistId: String): Result<PlaylistPage> = withTube { YouTube.playlist(playlistId, via = it) }

        suspend fun accountInfo(): Result<AccountInfo> = withTube { YouTube.accountInfo(via = it) }

        suspend fun videoHome(continuation: String? = null): Result<AccountVideoFeed> = videoFeed(VIDEO_HOME_BROWSE_ID, continuation)

        suspend fun subscriptionsFeed(continuation: String? = null): Result<AccountVideoFeed> =
            videoFeed(SUBSCRIPTIONS_BROWSE_ID, continuation)

        suspend fun watchHistory(continuation: String? = null): Result<AccountVideoFeed> = videoFeed(WATCH_HISTORY_BROWSE_ID, continuation)

        internal suspend fun tube(): InnerTube? = active()?.second

        private suspend fun active(): Pair<AccountSession, InnerTube>? =
            mutex.withLock {
                val session = store.current()?.takeUnless { it.expired }
                if (session == null) {
                    cached = null
                    verifiedCookie = null
                    return@withLock null
                }
                cached?.takeIf { it.first == session }
                    ?: (session to newTube(session)).also { cached = it }
            }

        private suspend fun verifyAccount(tube: InnerTube): Result<Unit> {
            val cookie = cached?.first?.cookie
            if (cookie != null && cookie == verifiedCookie) return Result.success(Unit)
            return YouTube
                .accountInfo(via = tube)
                .map { verifiedCookie = cookie }
                .recoverCatching { error -> throw if (isSignedOutFailure(error)) AccountSessionExpiredException() else error }
        }

        private fun newTube(session: AccountSession) =
            InnerTube(AccountEndpointPolicy.FEEDS_ONLY).apply {
                cacheDirectory = File(System.getProperty("java.io.tmpdir"), ACCOUNT_HTTP_CACHE)
                locale = YouTube.locale
                cookie = session.cookie
                visitorData = session.visitorData
                dataSyncId = session.dataSyncId
                useLoginForBrowse = true
            }

        private suspend fun videoFeed(
            browseId: String,
            continuation: String?,
        ): Result<AccountVideoFeed> =
            withTube { tube ->
                runCatching {
                    val body = tube.accountWebBrowse(YouTubeClient.WEB, browseId, continuation).bodyAsText()
                    Json.parseToJsonElement(body).toAccountVideoFeed().requireLoggedIn()
                }
            }

        private suspend fun <T> withTube(block: suspend (InnerTube) -> Result<T>): Result<T> {
            val (session, tube) = active() ?: return Result.failure(AccountSignedOutException())
            return block(tube).onFailure { error ->
                if (error is AccountSessionExpiredException || error.isAuthRejection()) store.markExpired(session.cookie)
            }
        }
    }

private const val ACCOUNT_HTTP_CACHE = "account_http_cache"
private val AUTH_REJECTIONS = setOf(401, 403)

private fun Throwable.isAuthRejection(): Boolean = (this as? ClientRequestException)?.response?.status?.value in AUTH_REJECTIONS

/** accountInfo() throws a NullPointerException when YouTube returns no active account for the cookie. */
internal fun isSignedOutFailure(error: Throwable): Boolean =
    error is AccountSessionExpiredException || error is NullPointerException || error.isAuthRejection()

internal fun AccountVideoFeed.requireLoggedIn(): AccountVideoFeed = if (loggedIn == false) throw AccountSessionExpiredException() else this
