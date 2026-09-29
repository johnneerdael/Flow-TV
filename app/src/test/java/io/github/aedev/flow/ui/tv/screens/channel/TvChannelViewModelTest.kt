package io.github.aedev.flow.ui.tv.screens.channel

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.MetadataPage
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvChannelViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val channel = EntityRef(EntityKind.CHANNEL, "UC1")
    private val header =
        EntityHeader(
            id = "header",
            style = HeaderStyle.PORTRAIT,
            entity = channel,
            title = "Cercle",
            artwork = Artwork("https://avatar"),
            details = listOf("@Cercle", "9M subscribers"),
        )
    private val page =
        MetadataPage(
            id = "channel",
            blocks = listOf(header),
            filters = FilterControl(listOf(FilterOption("videos", "Videos"), FilterOption("about", "About"))),
        )
    private val subscribed = MutableStateFlow(false)
    private val video = mockk<PluginVideoProvider>()
    private val subscriptions =
        mockk<SubscriptionRepository>(relaxUnitFun = true) {
            every { isSubscribed("UC1") } returns subscribed
        }

    private fun viewModel() = TvChannelViewModel(SavedStateHandle(mapOf(TvChannelViewModel.CHANNEL_ARG to "UC1")), video, subscriptions)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `one read gives the header and the tabs, and the first tab is showing`() =
        runTest(dispatcher) {
            coEvery { video.page(channel, null, null) } returns Result.success(page)
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()
            vm.load()
            advanceUntilIdle()

            assertThat(vm.state.value.header).isEqualTo(header)
            assertThat(
                vm.state.value.selectedFilter
                    ?.id,
            ).isEqualTo("videos")
            coVerify(exactly = 1) { video.page(any(), any(), any()) }

            vm.selectFilter("about")
            assertThat(
                vm.state.value.selectedFilter
                    ?.id,
            ).isEqualTo("about")
            assertThat(vm.requestFilter("about")).isEqualTo("about")
            assertThat(vm.requestFilter("videos")).isNull()
        }

    @Test
    fun `a failed channel shows the reason and can be read again`() =
        runTest(dispatcher) {
            coEvery { video.page(channel, null, null) } returns Result.failure(IllegalStateException("No channel"))
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.error).isEqualTo("No channel")

            coEvery { video.page(channel, null, null) } returns Result.success(page)
            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.header).isEqualTo(header)
        }

    @Test
    fun `subscribing keeps the channel in the app's own library, and toggles back`() =
        runTest(dispatcher) {
            coEvery { video.page(channel, null, null) } returns Result.success(page)
            val saved = slot<ChannelSubscription>()
            coEvery { subscriptions.subscribe(capture(saved)) } answers { subscribed.value = true }
            val vm = viewModel()
            backgroundScope.launch { vm.isSubscribed.collect {} }
            vm.load()
            advanceUntilIdle()

            vm.toggleSubscription()
            advanceUntilIdle()
            assertThat(saved.captured.channelId).isEqualTo("UC1")
            assertThat(saved.captured.channelName).isEqualTo("Cercle")
            assertThat(saved.captured.channelThumbnail).isEqualTo("https://avatar")

            vm.toggleSubscription()
            advanceUntilIdle()
            coVerify { subscriptions.unsubscribe("UC1") }
        }
}
