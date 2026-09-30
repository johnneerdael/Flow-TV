package io.github.aedev.flow.ui.tv.screens.account

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

@Composable
internal fun LoginPhoneViewport(
    controller: LoginWebViewController,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val width = LocalTvDimens.current.signInViewportWidth
        val height = LocalTvDimens.current.signInViewportHeight
        val scale = minOf(1f, maxWidth / width, maxHeight / height)
        Box(modifier = Modifier.width(width * scale).height(height * scale)) {
            AndroidView(
                factory = { controller.webView },
                modifier =
                    Modifier
                        .requiredSize(width, height)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin.Center
                        }.onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            controller.viewportBounds(
                                Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()),
                            )
                        },
            )
        }
    }
}
