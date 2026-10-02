package io.github.aedev.flow.ui.screens.music

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.ProviderAccount
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogPageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val playlist = EntityRef(EntityKind.PLAYLIST, "PL1")
    private val requests = mutableListOf<String?>()
    private val stores = mutableListOf<ViewModelStore>()
    private val collectors = mutableListOf<Job>()

    private fun tracks(vararg ids: String) =
        CollectionBlock(
            id = "tracks",
            header = null,
            layout = CollectionLayout.TRACK_TABLE,
            defaultItemView = ItemView.TRACK_ROW,
            items = ids.map { MetadataItem(id = "tracks#$it", entity = EntityRef(EntityKind.TRACK, it), title = it) },
        )

    private val header = EntityHeader(id = "header", style = HeaderStyle.COVER, entity = playlist, title = "Long playlist")

    private fun viewModel(
        accountState: MutableStateFlow<ProviderAccount> = MutableStateFlow(ProviderAccount.Anonymous),
        pages: suspend (String?) -> Result<MetadataPage>,
    ) = CatalogPageViewModel(
        SavedStateHandle(
            mapOf(CatalogPageViewModel.KIND_ARG to playlist.kind.name, CatalogPageViewModel.ID_ARG to playlist.providerId),
        ),
        object : MetadataProvider {
            override val id = "fake"
            override val account = accountState

            override suspend fun home(request: HomeRequest): Result<MetadataPage> = Result.failure(UnsupportedOperationException())

            override suspend fun page(
                entity: EntityRef,
                cursor: String?,
            ): Result<MetadataPage> {
                requests += cursor
                return pages(cursor)
            }
        },
        CatalogPlayback { null },
        mockk<SubscriptionRepository> { every { isSubscribed(any()) } returns flowOf(false) },
    ).also { vm ->
        stores += ViewModelStore().apply { put("catalog", vm) }
        collectors += CoroutineScope(dispatcher).launch { vm.state.collect {} }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        collectors.forEach(Job::cancel)
        Dispatchers.resetMain()
    }

    @Test
    fun `a long playlist's further pages extend its tracks, in order`() =
        runTest(dispatcher) {
            val vm =
                viewModel { cursor ->
                    Result.success(
                        when (cursor) {
                            null -> MetadataPage("p", listOf(header, tracks("a", "b")), nextCursor = "c1")
                            "c1" -> MetadataPage("tracks", listOf(tracks("c", "d")), nextCursor = "c2")
                            else -> MetadataPage("tracks", listOf(tracks("e")))
                        },
                    )
                }

            vm.load()
            advanceUntilIdle()

            val blocks = vm.state.value.blocks
            assertThat(blocks.map { it.id }).containsExactly("header", "tracks").inOrder()
            assertThat((blocks[1] as CollectionBlock).items.map { it.entity.providerId }).containsExactly("a", "b", "c", "d", "e").inOrder()
            assertThat(requests).containsExactly(null, "c1", "c2").inOrder()
        }

    @Test
    fun `a failed page is shown as an error and loads again on the next visit`() =
        runTest(dispatcher) {
            var online = false
            val vm =
                viewModel {
                    if (online) Result.success(MetadataPage("p", listOf(header))) else Result.failure(IllegalStateException("offline"))
                }

            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.error).isEqualTo("offline")

            online = true
            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.blocks).containsExactly(header)
            assertThat(vm.state.value.error).isNull()
        }

    @Test
    fun `a loaded page is not fetched again`() =
        runTest(dispatcher) {
            val vm = viewModel { Result.success(MetadataPage("p", listOf(header))) }

            vm.load()
            advanceUntilIdle()
            vm.load()
            advanceUntilIdle()

            assertThat(requests).hasSize(1)
        }

    @Test fun aChangedAccountCannotReusePrivatePlaylistBlocks() =
        runTest(dispatcher) {
            val account = MutableStateFlow<ProviderAccount>(ProviderAccount.SignedIn("a"))
            val vm =
                viewModel(account) {
                    val owner = (account.value as ProviderAccount.SignedIn).key
                    Result.success(MetadataPage("p", listOf(tracks(owner))))
                }
            vm.load()
            advanceUntilIdle()
            account.value = ProviderAccount.SignedIn("b")
            vm.load()
            advanceUntilIdle()
            assertThat(
                (
                    vm.state.value.blocks
                        .single() as CollectionBlock
                ).items.single().title,
            ).isEqualTo("b")
        }

    @Test fun signingOutHidesCachedPrivatePlaylistWithoutAnotherFetch() =
        runTest(dispatcher) {
            val account = MutableStateFlow<ProviderAccount>(ProviderAccount.SignedIn("a"))
            val vm = viewModel(account) { Result.success(MetadataPage("p", listOf(header))) }
            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.blocks).isNotEmpty()
            account.value = ProviderAccount.Anonymous
            advanceUntilIdle()
            assertThat(vm.state.value.blocks).isEmpty()
            assertThat(requests).containsExactly(null)
        }

    @Test fun retiredAccountResultsCannotOverwriteNewAccount() =
        runTest(dispatcher) {
            val account = MutableStateFlow<ProviderAccount>(ProviderAccount.SignedIn("a"))
            val oldStarted = CompletableDeferred<Unit>()
            val finishOld = CompletableDeferred<Unit>()
            val vm =
                viewModel(account) {
                    val owner = (account.value as ProviderAccount.SignedIn).key
                    if (owner == "a") {
                        oldStarted.complete(Unit)
                        withContext(NonCancellable) { finishOld.await() }
                    }
                    Result.success(MetadataPage("p", listOf(tracks(owner))))
                }
            vm.load()
            runCurrent()
            assertThat(oldStarted.isCompleted).isTrue()
            account.value = ProviderAccount.SignedIn("b")
            vm.load()
            finishOld.complete(Unit)
            advanceUntilIdle()
            assertThat(
                (
                    vm.state.value.blocks
                        .single() as CollectionBlock
                ).items.single().title,
            ).isEqualTo("b")
        }
}
