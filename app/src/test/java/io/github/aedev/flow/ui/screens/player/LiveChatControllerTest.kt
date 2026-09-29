package io.github.aedev.flow.ui.screens.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.LiveChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins the gates on [LiveChatController]: the availability probe always runs and its batch is kept,
 * polling runs only while the panel shows the chat and the video plays, it waits the time each batch
 * asks for, and hiding the panel keeps the transcript so reopening costs no wait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveChatControllerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val controllerScope = CoroutineScope(testDispatcher)
    private val requests = mutableListOf<Pair<String, String?>>()
    private var firstBatch: LiveChatPoll? = poll(listOf(message("m1"), message("m2")))
    private var nextBatch: () -> LiveChatPoll? = { poll(listOf(message("n${requests.size}"))) }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        controllerScope.cancel()
        Dispatchers.resetMain()
    }

    private val polls: Int get() = requests.count { it.second != null }

    /**
     * The drip loop never ends on its own, so every test hands its scope back before [runTest]
     * drains the scheduler — an endless virtual-time loop would otherwise hang the runner.
     */
    private fun liveChatTest(body: suspend TestScope.(LiveChatController) -> Unit) =
        runTest(testDispatcher) {
            val controller =
                LiveChatController(
                    fetch = { videoId, cursor ->
                        requests += videoId to cursor
                        if (cursor == null) firstBatch else nextBatch()
                    },
                    scope = controllerScope,
                    dispatcher = testDispatcher,
                )
            try {
                body(controller)
            } finally {
                controllerScope.cancel()
            }
        }

    @Test
    fun `a hidden panel probes availability once but never polls`() =
        liveChatTest { controller ->
            controller.start("v1")
            advanceTimeBy(POLL_WINDOW_MS * 3)
            runCurrent()

            assertThat(requests).containsExactly("v1" to null)
            assertThat(controller.isAvailable.value).isTrue()
            assertThat(controller.isLoading.value).isFalse()
            assertThat(controller.messages.value).isEmpty()
        }

    @Test
    fun `a chat the plugin cannot give resolves unavailable without polling`() =
        liveChatTest { controller ->
            firstBatch = null
            controller.setPanelVisible(true)
            controller.start("v1")
            advanceTimeBy(POLL_WINDOW_MS * 3)
            runCurrent()

            assertThat(controller.isAvailable.value).isFalse()
            assertThat(controller.isLoading.value).isFalse()
            assertThat(requests).containsExactly("v1" to null)
        }

    @Test
    fun `showing the panel shows the probed batch first, then polls with its cursor`() =
        liveChatTest { controller ->
            controller.start("v1")
            runCurrent()
            controller.setPanelVisible(true)
            advanceTimeBy(PAGE_WAIT_MS + 100)
            runCurrent()

            assertThat(controller.messages.value.map { it.id }).containsAtLeast("m1", "m2").inOrder()
            assertThat(requests.first()).isEqualTo("v1" to null)
            assertThat(requests.drop(1).map { it.second }).containsExactly(CURSOR)
        }

    @Test
    fun `polling waits the time each batch asks for`() =
        liveChatTest { controller ->
            controller.setPanelVisible(true)
            controller.start("v1")
            runCurrent()
            advanceTimeBy(PAGE_WAIT_MS - 100)
            runCurrent()
            assertThat(polls).isEqualTo(0)

            advanceTimeBy(200)
            runCurrent()
            assertThat(polls).isEqualTo(1)
        }

    @Test
    fun `pausing playback stops the polling until it resumes`() =
        liveChatTest { controller ->
            controller.setPanelVisible(true)
            controller.start("v1")
            advanceTimeBy(POLL_WINDOW_MS)
            runCurrent()

            controller.setPlaying(false)
            val pollsWhilePaused = polls
            advanceTimeBy(POLL_WINDOW_MS * 4)
            runCurrent()
            assertThat(polls).isEqualTo(pollsWhilePaused)

            controller.setPlaying(true)
            advanceTimeBy(POLL_WINDOW_MS)
            runCurrent()
            assertThat(polls).isGreaterThan(pollsWhilePaused)
        }

    @Test
    fun `hiding the panel stops the polling and keeps the transcript`() =
        liveChatTest { controller ->
            controller.setPanelVisible(true)
            controller.start("v1")
            advanceTimeBy(POLL_WINDOW_MS)
            runCurrent()
            val bufferedMessages = controller.messages.value
            assertThat(bufferedMessages).isNotEmpty()

            controller.setPanelVisible(false)
            val pollsBeforeHiding = polls
            advanceTimeBy(POLL_WINDOW_MS * 4)
            runCurrent()

            assertThat(polls).isEqualTo(pollsBeforeHiding)
            assertThat(controller.messages.value).isEqualTo(bufferedMessages)
        }

    @Test
    fun `a failed poll starts the chat afresh`() =
        liveChatTest { controller ->
            var failNext = true
            nextBatch = {
                if (failNext) {
                    failNext = false
                    null
                } else {
                    poll(listOf(message("late")))
                }
            }
            controller.setPanelVisible(true)
            controller.start("v1")
            advanceTimeBy(PAGE_WAIT_MS + RETRY_MS + 100)
            runCurrent()

            assertThat(requests.map { it.second }).containsExactly(null, CURSOR, null).inOrder()
        }

    @Test
    fun `a new video clears the transcript`() =
        liveChatTest { controller ->
            controller.setPanelVisible(true)
            controller.start("v1")
            advanceTimeBy(POLL_WINDOW_MS)
            runCurrent()
            assertThat(controller.messages.value).isNotEmpty()

            controller.start("v2")

            assertThat(controller.messages.value).isEmpty()
            assertThat(controller.isLoading.value).isTrue()
            assertThat(controller.isAvailable.value).isFalse()
        }

    private companion object {
        const val CURSOR = "cursor"
        const val PAGE_WAIT_MS = 1_000L
        const val POLL_WINDOW_MS = 2_000L
        const val RETRY_MS = 3_000L

        fun poll(messages: List<LiveChatMessage>) = LiveChatPoll(messages, next = CURSOR, pollAfterMs = PAGE_WAIT_MS)

        fun message(id: String) =
            LiveChatMessage(
                id = id,
                author = "author",
                authorPhotoUrl = null,
                message = "hello $id",
                timestamp = null,
            )
    }
}
