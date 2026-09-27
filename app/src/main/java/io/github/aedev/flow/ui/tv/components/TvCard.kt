package io.github.aedev.flow.ui.tv.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.tv.focus.rememberTvFocusState
import io.github.aedev.flow.ui.tv.focus.tvFocusScale
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

/**
 * Base focusable card for the ten-foot UI. Focus visuals are tokens-only:
 * neutral outline border, container step, tonal elevation — plus the shared
 * focus scale from the TvFocusKit.
 */
@Composable
fun TvCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = MaterialTheme.shapes.medium,
    containerAlpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusState = rememberTvFocusState()
    val focused = focusState.isFocused
    val dimens = LocalTvDimens.current

    Card(
        onClick = onClick,
        modifier = modifier.tvFocusScale(focusState),
        shape = shape,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        focused -> MaterialTheme.colorScheme.primaryContainer
                        selected -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    }.copy(alpha = containerAlpha),
                contentColor =
                    when {
                        focused -> MaterialTheme.colorScheme.onPrimaryContainer
                        selected -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    },
            ),
        border =
            if (focused) {
                BorderStroke(dimens.focusBorderWidth, MaterialTheme.colorScheme.outline)
            } else {
                null
            },
        // A see-through card would show its own shadow through it.
        elevation = CardDefaults.cardElevation(defaultElevation = if (focused && containerAlpha == 1f) 3.dp else 0.dp),
        content = content,
    )
}
