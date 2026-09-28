package io.github.aedev.flow.data.catalog.youtube

import io.github.aedev.flow.data.account.AccountFeedClient
import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.data.account.AccountSessionStore
import io.github.aedev.flow.innertube.InnerTube
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.response.BrowseResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.ProviderAccount
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube Music as a catalog provider: the signed-in account's own home while a session is active,
 * the anonymous home otherwise.
 */
@Singleton
class YouTubeMusicProvider
    @Inject
    constructor(
        private val store: AccountSessionStore,
        private val client: AccountFeedClient,
        private val youTube: YouTube,
    ) : MetadataProvider {
        override val id: String = PROVIDER_ID

        override val account: Flow<ProviderAccount> =
            store.session
                .map { it.toProviderAccount() }
                .distinctUntilChanged()

        override suspend fun home(request: HomeRequest): Result<MetadataPage> {
            // Chip params select the first page only; continuations already carry them.
            val params = request.filterId.takeIf { request.cursor == null }
            val signedIn = store.current()?.takeUnless { it.expired } != null
            val home =
                if (signedIn) {
                    client.musicHome(continuation = request.cursor, params = params)
                } else {
                    youTube.home(continuation = request.cursor, params = params)
                }
            return home.map(YouTubeHomeMapper::page)
        }

        override suspend fun page(
            entity: EntityRef,
            cursor: String?,
        ): Result<MetadataPage> {
            val tube = if (store.current()?.takeUnless { it.expired } != null) client.tube() else null
            if (cursor != null) return browse(null, cursor, tube).map(YouTubePageMapper::tracksContinuation)
            return when (entity.kind) {
                EntityKind.ARTIST -> browse(entity.providerId, null, tube).map { YouTubePageMapper.artist(it, entity.providerId) }
                EntityKind.ALBUM -> browse(entity.providerId, null, tube).map { YouTubePageMapper.collection(it, entity) }
                EntityKind.PLAYLIST -> browse("VL${entity.providerId}", null, tube).map { YouTubePageMapper.collection(it, entity) }
                else -> Result.failure(IllegalArgumentException("YouTube Music has no page for ${entity.kind}"))
            }
        }

        private suspend fun browse(
            browseId: String?,
            continuation: String?,
            tube: InnerTube?,
        ): Result<BrowseResponse> =
            if (tube != null) {
                youTube.browseResponse(browseId, continuation, via = tube)
            } else {
                youTube.browseResponse(browseId, continuation)
            }

        private fun AccountSession?.toProviderAccount(): ProviderAccount =
            when {
                this == null -> ProviderAccount.Anonymous
                expired -> ProviderAccount.Expired
                else -> ProviderAccount.SignedIn(key = dataSyncId ?: cookie.digest())
            }

        private fun String.digest(): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(toByteArray())
                .joinToString("") { "%02x".format(it) }

        companion object {
            const val PROVIDER_ID = "youtube-music"
        }
    }
