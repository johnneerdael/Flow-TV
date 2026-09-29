package io.github.aedev.flow.data.account

import io.github.aedev.flow.innertube.AccountEndpointPolicy
import io.github.aedev.flow.innertube.InnerTube
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.AccountInfo
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.pages.HistoryPage
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

private const val WATCH_HISTORY_BROWSE_ID = "FEhistory"

/**
 * The only object that ever holds the account cookie. Its InnerTube is separate from the anonymous
 * `YouTube` one that playback uses, and its HTTP client refuses everything except feed reads and
 * adding plays to the account's history.
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

        suspend fun musicHistory(): Result<HistoryPage> = withTube { YouTube.musicHistory(via = it) }

        suspend fun playlist(playlistId: String): Result<PlaylistPage> = withTube { YouTube.playlist(playlistId, via = it) }

        suspend fun accountInfo(): Result<AccountInfo> = withTube { YouTube.accountInfo(via = it) }

        suspend fun watchHistory(): Result<AccountVideoFeed> = videoFeed(WATCH_HISTORY_BROWSE_ID)

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

        private fun newTube(session: AccountSession) =
            InnerTube(AccountEndpointPolicy.ACCOUNT).apply {
                cacheDirectory = File(System.getProperty("java.io.tmpdir"), ACCOUNT_HTTP_CACHE)
                locale = YouTube.locale
                cookie = session.cookie
                visitorData = session.visitorData
                dataSyncId = session.dataSyncId
                useLoginForBrowse = true
            }

        private suspend fun videoFeed(browseId: String): Result<AccountVideoFeed> =
            withTube { tube ->
                runCatching {
                    val body = tube.accountWebBrowse(YouTubeClient.WEB, browseId).bodyAsText()
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

internal fun AccountVideoFeed.requireLoggedIn(): AccountVideoFeed = if (loggedIn == false) throw AccountSessionExpiredException() else this
