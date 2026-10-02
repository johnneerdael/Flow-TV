package io.github.aedev.flow.ui.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.ui.tv.components.TvSearchField
import io.github.aedev.flow.ui.tv.theme.TvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvMusicFolderPasswordDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun passwordFieldMasksEveryCharacterAndReceivesTyping() {
        var received = ""
        compose.setContent {
            TvTheme {
                var password by remember { mutableStateOf("") }
                TvSearchField(password, {
                    password = it
                    received = it
                }, {}, modifier = Modifier.testTag("password"), secure = true)
            }
        }
        val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        node.performTextInput("fixture-password")
        compose.waitForIdle()
        assertEquals("fixture-password", received)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(action(layouts))
            assertEquals(
                16,
                layouts
                    .single()
                    .layoutInput.text.length,
            )
            assertTrue(
                layouts
                    .single()
                    .layoutInput.text.text
                    .all { it == '•' },
            )
        }
    }

    @Test fun passwordVisibilityTogglePreservesSymbolsAndRemasks() {
        var received = ""
        compose.setContent {
            TvTheme {
                var password by remember { mutableStateOf("") }
                TvSearchField(password, {
                    password = it
                    received = it
                }, {}, secure = true)
            }
        }
        val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        val value = "fixture-$# !"
        node.performTextInput(value)
        compose.waitForIdle()

        fun rendered(): String {
            var text = ""
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(action(layouts))
                text =
                    layouts
                        .single()
                        .layoutInput.text.text
            }
            return text
        }
        assertEquals("•".repeat(value.length), rendered())
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.waitForIdle()
        assertEquals(value, rendered())
        compose.onNodeWithContentDescription("Hide password").performClick()
        compose.waitForIdle()
        assertEquals("•".repeat(value.length), rendered())
        assertEquals(value, received)
    }
}
