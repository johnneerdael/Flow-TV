package io.github.aedev.flow.ui.tv.screens.account

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.LibraryRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginOperations
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvAccountLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val account = MutableStateFlow<ProviderAccount>(ProviderAccount.SignedIn("first"))
    private val requests = mutableListOf<LibraryRequest>()
    private val provider =
        mockk<PluginMetadataProvider> {
            every { account } returns this@TvAccountLibraryViewModelTest.account
            coEvery { call(PluginOperations.library, any()) } answers {
                val request = secondArg<LibraryRequest>()
                requests += request
                Result.success(
                    when (request.cursor) {
                        null -> MetadataPage("liked", listOf(tracks("a", "b")), nextCursor = "more")
                        else -> MetadataPage("liked", listOf(tracks("c")))
                    },
                )
            }
        }

    private fun tracks(vararg ids: String) =
        CollectionBlock(
            id = "tracks",
            header = null,
            layout = CollectionLayout.TRACK_TABLE,
            defaultItemView = ItemView.TRACK_ROW,
            items = ids.map { MetadataItem(id = "tracks#$it", entity = EntityRef(EntityKind.TRACK, it), title = it) },
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a section asks the plugin for its library section and extends it with the pages that follow`() =
        runTest(dispatcher) {
            val vm = TvAccountLibraryViewModel(provider)

            vm.open(TvAccountLibrarySection.LIKED_MUSIC)
            advanceUntilIdle()

            val state = vm.sections.value.getValue(TvAccountLibrarySection.LIKED_MUSIC)
            assertThat(
                (state.blocks.single() as CollectionBlock).items.map { it.entity.providerId },
            ).containsExactly("a", "b", "c").inOrder()
            assertThat(requests).containsExactly(LibraryRequest("liked"), LibraryRequest("liked", "more")).inOrder()
        }

    @Test
    fun `reopening a section just read asks nothing, another account reads it again`() =
        runTest(dispatcher) {
            val vm = TvAccountLibraryViewModel(provider)
            vm.open(TvAccountLibrarySection.LIKED_MUSIC)
            advanceUntilIdle()

            vm.open(TvAccountLibrarySection.LIKED_MUSIC)
            advanceUntilIdle()
            assertThat(requests).hasSize(2)

            account.value = ProviderAccount.SignedIn("second")
            vm.open(TvAccountLibrarySection.LIKED_MUSIC)
            advanceUntilIdle()
            assertThat(requests).hasSize(4)
            assertThat(
                vm.sections.value
                    .getValue(TvAccountLibrarySection.LIKED_MUSIC)
                    .accountKey,
            ).isEqualTo("second")
        }

    @Test
    fun `a signed-out section shows the plugin's reason`() =
        runTest(dispatcher) {
            coEvery { provider.call(PluginOperations.library, any()) } returns Result.failure(IllegalStateException("Sign in first"))
            val vm = TvAccountLibraryViewModel(provider)

            vm.open(TvAccountLibrarySection.PLAYLISTS)
            advanceUntilIdle()

            val state = vm.sections.value.getValue(TvAccountLibrarySection.PLAYLISTS)
            assertThat(state.error).isEqualTo("Sign in first")
            assertThat(state.isLoading).isFalse()
        }

    @Test
    fun `the watch history is paged, not read ahead`() =
        runTest(dispatcher) {
            val vm = TvAccountLibraryViewModel(provider)

            vm.open(TvAccountLibrarySection.WATCH_HISTORY)
            advanceUntilIdle()

            assertThat(requests).isEmpty()
        }
}
