package io.github.aedev.flow.ui.tv.focus

import android.view.KeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

private class RepeatedListTraversal {
    var active = false
}

@Composable
fun Modifier.tvAcceleratedDpad(): Modifier {
    val focus = LocalFocusManager.current
    val traversal = remember { RepeatedListTraversal() }
    return onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (event.type != KeyEventType.KeyDown || native.repeatCount == 0) return@onPreviewKeyEvent false
        val direction =
            when (native.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                else -> return@onPreviewKeyEvent false
            }
        val steps =
            when {
                native.repeatCount >= 40 -> 8
                native.repeatCount >= 20 -> 5
                native.repeatCount >= 10 -> 3
                native.repeatCount >= 4 -> 2
                else -> 1
            }
        traversal.active = true
        try {
            repeat(steps) { if (!focus.moveFocus(direction)) return@onPreviewKeyEvent true }
        } finally {
            traversal.active = false
        }
        true
    }.focusProperties {
        onExit = {
            if (traversal.active && (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down)) {
                cancelFocusChange()
            }
        }
    }.focusGroup()
}
