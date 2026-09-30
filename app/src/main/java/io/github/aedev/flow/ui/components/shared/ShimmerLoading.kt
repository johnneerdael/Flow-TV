package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp

/**
 * A loading placeholder that pulses between two surface tones. The pulse is read only while drawing,
 * so a skeleton costs one draw pass per frame and never recomposes; [delayMillis] staggers the bones of
 * one placeholder so they ripple instead of blinking together.
 */
@Composable
fun Modifier.shimmerEffect(
    shape: Shape = MaterialTheme.shapes.small,
    durationMillis: Int = 1200,
    delayMillis: Int = 0,
): Modifier {
    val pulse =
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(delayMillis),
                ),
            label = "skeleton_pulse",
        )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    return this
        .clip(shape)
        .drawBehind { drawRect(lerp(base, highlight, pulse.value)) }
}

@Composable
fun ShimmerBone(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    delayMillis: Int = 0,
) {
    Box(
        modifier =
            modifier
                .shimmerEffect(shape = shape, delayMillis = delayMillis),
    )
}
