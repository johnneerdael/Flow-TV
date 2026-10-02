package io.github.aedev.flow.plugin.catalog

import androidx.paging.PagingSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.LibraryRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import org.junit.Test

class MergedLibraryPagingSourceTest {
    private fun page(
        vararg refs: EntityRef,
        next: String? = null,
    ) = MetadataPage(
        "page",
        listOf(
            CollectionBlock(
                "items",
                null,
                CollectionLayout.HORIZONTAL_SHELF,
                ItemView.COVER_CARD,
                refs.map { MetadataItem(it.providerId, it, it.providerId) },
            ),
        ),
        nextCursor = next,
    )

    private fun playlist(id: String) = EntityRef(EntityKind.PLAYLIST, id)

    private fun source(
        section: String = "playlists",
        onFailure: (LibraryProvider, Throwable) -> Unit = { _, _ -> },
        onSuccess: (LibraryProvider) -> Unit = {},
        fetch: suspend (String, LibraryRequest) -> Result<MetadataPage>,
    ) = MergedLibraryPagingSource(
        listOf(
            LibraryProvider("a", "A", 1, "account", "ytm"),
            LibraryProvider("b", "B", 1, "account", "spotify"),
        ),
        section,
        fetch,
        onFailure,
        onSuccess,
    )

    private suspend fun MergedLibraryPagingSource.first() = load(PagingSource.LoadParams.Refresh(null, 30, false))

    private suspend fun MergedLibraryPagingSource.next(key: Int) = load(PagingSource.LoadParams.Append(key, 30, false))

    @Test fun allProvidersAppearAndIdenticalIdsKeepTheirOwners() =
        runTest {
            val requests = mutableListOf<String>()
            val paging =
                source { provider, _ ->
                    requests += provider
                    Result.success(page(playlist("same")))
                }
            val first = paging.first() as PagingSource.LoadResult.Page
            assertThat(first.data.map { it.provider.id }).containsExactly("a", "b").inOrder()
            assertThat(first.data.map { it.key }.distinct()).hasSize(2)
            assertThat(requests).containsExactly("a", "b").inOrder()
        }

    @Test fun continuationsRunInTurnAndRepeatedTokensTerminate() =
        runTest {
            val requests = mutableListOf<Pair<String, String?>>()
            val paging =
                source { provider, request ->
                    requests += provider to request.cursor
                    Result.success(page(playlist(if (request.cursor == null) "one" else "two"), next = "cursor"))
                }
            val first = paging.first() as PagingSource.LoadResult.Page
            val second = paging.next(first.nextKey!!) as PagingSource.LoadResult.Page
            val third = paging.next(second.nextKey!!) as PagingSource.LoadResult.Page
            assertThat(requests).containsExactly("a" to null, "b" to null, "a" to "cursor", "b" to "cursor").inOrder()
            assertThat(second.data.map { it.provider.id }).containsExactly("a")
            assertThat(third.nextKey).isNull()
        }

    @Test fun unavailableProviderDoesNotHideAnotherProvidersPlaylists() =
        runTest {
            val paging =
                source { provider, _ ->
                    if (provider == "a") Result.failure(IllegalStateException("offline")) else Result.success(page(playlist("b-list")))
                }
            val first = paging.first() as PagingSource.LoadResult.Page
            assertThat(first.data.map { it.provider.id }).containsExactly("b")
        }

    @Test fun playlistsOmitProviderFavoritesAndAlbums() =
        runTest {
            val paging =
                source { provider, _ ->
                    val result =
                        if (provider == "a") {
                            page(playlist("LL"), playlist("LM"), playlist("normal"), EntityRef(EntityKind.ALBUM, "album"))
                        } else {
                            page(playlist("spotify:collection:tracks"), playlist("normal"))
                        }
                    Result.success(result)
                }
            val first = paging.first() as PagingSource.LoadResult.Page
            assertThat(first.data.map { it.item.entity.providerId }).containsExactly("normal", "normal")
        }

    @Test fun likedSongsAcceptMusicVideosAndTracksButOmitVideos() =
        runTest {
            val paging =
                source("liked") { _, _ ->
                    val refs =
                        listOf(
                            EntityRef(EntityKind.TRACK, "song"),
                            EntityRef(EntityKind.MUSIC_VIDEO, "music"),
                            EntityRef(EntityKind.VIDEO, "video"),
                        )
                    Result.success(page(*refs.toTypedArray()))
                }
            val first = paging.first() as PagingSource.LoadResult.Page
            assertThat(first.data.map { it.item.entity.kind }).containsExactly(
                EntityKind.TRACK,
                EntityKind.MUSIC_VIDEO,
                EntityKind.TRACK,
                EntityKind.MUSIC_VIDEO,
            )
        }

    @Test fun cancellationStopsBeforeCallingAnotherProvider() =
        runTest {
            val requests = mutableListOf<String>()
            val paging =
                source { provider, _ ->
                    requests += provider
                    throw CancellationException("closed pane")
                }
            try {
                paging.first()
                throw AssertionError("Cancellation was swallowed")
            } catch (_: CancellationException) {
            }
            assertThat(requests).containsExactly("a")
        }

    @Test fun failedFinalContinuationCanBeRetriedWithoutLosingItsCursor() =
        runTest {
            var failing = true
            val unavailable = mutableSetOf<String>()
            val paging =
                source(onFailure = {
                    provider,
                    _,
                    ->
                    unavailable += provider.id
                }, onSuccess = { unavailable -= it.id }) { provider, request ->
                    if (request.cursor == null) {
                        Result.success(page(playlist(provider), next = if (provider == "a") "next" else null))
                    } else if (failing) {
                        Result.failure(IllegalStateException("offline"))
                    } else {
                        Result.success(page(playlist("later")))
                    }
                }
            val first = paging.first() as PagingSource.LoadResult.Page
            assertThat(paging.next(first.nextKey!!)).isInstanceOf(PagingSource.LoadResult.Error::class.java)
            assertThat(unavailable).containsExactly("a")
            failing = false
            val retried = paging.next(first.nextKey!!) as PagingSource.LoadResult.Page
            assertThat(retried.data.map { it.item.entity.providerId }).containsExactly("later")
            assertThat(unavailable).isEmpty()
        }

    @Test fun failedContinuationWaitsForRetryAfterHealthyProviderFinishes() =
        runTest {
            var failing = true
            val calls = mutableListOf<Pair<String, String?>>()
            val paging =
                source { provider, request ->
                    calls += provider to request.cursor
                    if (request.cursor == null) {
                        Result.success(page(playlist(provider), next = "next"))
                    } else if (provider == "a" && failing) {
                        Result.failure(IllegalStateException("offline"))
                    } else {
                        Result.success(page(playlist("later-$provider")))
                    }
                }
            val first = paging.first() as PagingSource.LoadResult.Page
            val failed = paging.next(first.nextKey!!) as PagingSource.LoadResult.Page
            val healthy = paging.next(failed.nextKey!!) as PagingSource.LoadResult.Page
            assertThat(healthy.data.map { it.item.entity.providerId }).containsExactly("later-b")
            assertThat(paging.next(healthy.nextKey!!)).isInstanceOf(PagingSource.LoadResult.Error::class.java)
            failing = false
            val retried = paging.next(healthy.nextKey!!) as PagingSource.LoadResult.Page
            assertThat(retried.data.map { it.item.entity.providerId }).containsExactly("later-a")
            assertThat(calls).containsExactly("a" to null, "b" to null, "a" to "next", "b" to "next", "a" to "next").inOrder()
        }
}
