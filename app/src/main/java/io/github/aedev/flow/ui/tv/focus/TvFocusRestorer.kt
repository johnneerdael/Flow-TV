package io.github.aedev.flow.ui.tv.focus

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import kotlinx.coroutines.launch

/**
 * Focus contract for every TV row/grid: children form one focus group so D-pad
 * traversal enters the container as a unit, and 2D focus search picks the
 * spatially nearest child on re-entry.
 *
 * Deliberately NOT using focusRestorer(): on compose-ui 1.7.x it pins the
 * last-focused lazy child and double-releases the pin when the container
 * detaches (e.g. shell -> full-screen player swap), crashing with
 * "Release should only be called once" (fixed upstream in 1.8, which we can't
 * take while Kotlin 1.9 caps the BOM at 2025.02.00).
 */
fun Modifier.tvRowFocus(): Modifier = this.focusGroup()

/**
 * Entering a row from above or below lands on its first item, with the row scrolled back to the
 * start, rather than on whichever card sits nearest the one focus left; sideways traversal and
 * leaving the row are unchanged. [first] must be attached to the row's first item.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.tvRowEntersAtStart(
    first: FocusRequester,
    isAtStart: () -> Boolean,
    scrollToStart: suspend () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    return focusProperties {
        enter = { direction ->
            when {
                direction != FocusDirection.Up && direction != FocusDirection.Down -> {
                    FocusRequester.Default
                }

                isAtStart() -> {
                    first
                }

                else -> {
                    // The first item is not composed while the row is scrolled away from it.
                    scope.launch {
                        scrollToStart()
                        withFrameNanos { }
                        first.requestFocus()
                    }
                    FocusRequester.Cancel
                }
            }
        }
    }
}
