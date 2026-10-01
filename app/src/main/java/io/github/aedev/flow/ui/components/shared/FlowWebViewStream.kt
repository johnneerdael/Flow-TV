package io.github.aedev.flow.ui.components.shared

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.aedev.flow.utils.WebViewStreamer

@Composable
fun FlowWebViewStream(
    controller: WebViewStreamer,
    viewportSize: DpSize,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) controller.visible(true)
                if (event == Lifecycle.Event.ON_STOP) controller.visible(false)
            }
        lifecycle.addObserver(observer)
        controller.visible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            controller.visible(false)
            lifecycle.removeObserver(observer)
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val width = viewportSize.width
        val height = viewportSize.height
        val scale = minOf(1f, maxWidth / width, maxHeight / height)
        Box(modifier = Modifier.width(width * scale).height(height * scale)) {
            AndroidView(
                factory = { controller.view },
                modifier =
                    Modifier
                        .requiredSize(width, height)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin.Center
                        }.onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            controller.viewportBounds =
                                Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
                        },
            )
        }
    }
}
