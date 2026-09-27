package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The projectM visualizer as the now-playing backdrop. It runs for as long as now-playing is open,
 * paused playback included (projectM drifts on silence), and stops rendering and listening only when
 * now-playing closes or the app leaves the screen.
 */
@Composable
internal fun TvVisualizerBackground(
    viewModel: TvVisualizerViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val host = remember(viewModel) { TvVisualizerHost(context, viewModel) }
    DisposableEffect(lifecycle, host) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> host.resume()
                    Lifecycle.Event.ON_STOP -> host.pause()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        context.registerComponentCallbacks(host)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(host)
            host.close()
        }
    }
    AndroidView(factory = { host.view }, modifier = modifier.fillMaxSize())
}
