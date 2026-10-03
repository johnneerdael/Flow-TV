package io.github.aedev.flow.ui.tv.music

import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.tv.theme.TvTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class TvQueueHeldNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val manager = EnhancedMusicPlayerManager

    @After fun cleanup() {
        manager.queueState.value = emptyList()
        manager.currentQueueIndexState.value = 0
    }

    @Test
    fun `opening near the end focuses the playing row and preserves that focus`() {
        manager.queueState.value = (0..49).map { MusicTrack("$it", "Song $it", "Artist", "", 120) }
        manager.automixState.value = emptyList()
        manager.currentQueueIndexState.value = 48
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Box(Modifier.width(650.dp).height(600.dp)) {
                    TvMusicQueuePanel(true, manager, {}, {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithText("Song 48").assertIsFocused()
    }

    @Test
    fun `an open empty queue focuses rows when they arrive later`() {
        manager.queueState.value = emptyList()
        manager.automixState.value = emptyList()
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Box(Modifier.width(650.dp).height(600.dp)) {
                    TvMusicQueuePanel(true, manager, {}, {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithContentDescription("Close").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Close").assertIsFocused()
        compose.runOnIdle {
            manager.queueState.value = listOf(MusicTrack("late", "Late song", "Artist", "", 120))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Late song").assertIsFocused()
    }

    @Test
    fun `a tap moves one row and held down accelerates through the queue`() {
        manager.queueState.value = (0..99).map { MusicTrack("$it", "Song $it", "Artist", "", 120) }
        manager.automixState.value = emptyList()
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Box(Modifier.width(650.dp).height(600.dp)) {
                    TvMusicQueuePanel(true, manager, {}, {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Song 0").assertIsFocused()
        val start = SystemClock.uptimeMillis()
        var eventTime = start
        var downTime = start

        fun send(
            action: Int,
            repeat: Int,
            keyCode: Int = KeyEvent.KEYCODE_DPAD_DOWN,
        ) {
            eventTime += 75
            if (action == KeyEvent.ACTION_DOWN && repeat == 0) downTime = eventTime
            compose.runOnIdle {
                compose.activity.dispatchKeyEvent(
                    KeyEvent(
                        downTime,
                        eventTime,
                        action,
                        keyCode,
                        repeat,
                        0,
                        -1,
                        0,
                        0,
                        InputDevice.SOURCE_DPAD,
                    ),
                )
            }
            compose.waitForIdle()
        }
        send(KeyEvent.ACTION_DOWN, 0)
        compose.onNodeWithText("Song 1").assertIsFocused()
        for (repeat in 1..20) send(KeyEvent.ACTION_DOWN, repeat)
        send(KeyEvent.ACTION_UP, 0)
        val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single()
        val text = focused.config[SemanticsProperties.Text].first().text
        val index = text.removePrefix("Song ").toInt()
        assertThat(index).isAtLeast(32)
        assertThat(index).isLessThan(100)
        send(KeyEvent.ACTION_DOWN, 0)
        compose.onNodeWithText("Song ${index + 1}").assertIsFocused()
        for (repeat in 1..70) send(KeyEvent.ACTION_DOWN, repeat)
        send(KeyEvent.ACTION_UP, 0)
        compose.onNodeWithText("Song 99").assertIsFocused()
        send(KeyEvent.ACTION_DOWN, 0, KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText("Song 98").assertIsFocused()
        for (repeat in 1..70) send(KeyEvent.ACTION_DOWN, repeat, KeyEvent.KEYCODE_DPAD_UP)
        send(KeyEvent.ACTION_UP, 0, KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText("Song 0").assertIsFocused()
    }
}
