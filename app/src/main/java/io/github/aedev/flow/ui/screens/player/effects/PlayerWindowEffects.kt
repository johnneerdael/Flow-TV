package io.github.aedev.flow.ui.screens.player.effects

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun KeepScreenOnEffect(
    isPlaying: Boolean,
    activity: Activity?,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    LifecycleStartEffect(activity, isPlaying, lifecycleOwner) {
        val clearScreenOn = {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (isPlaying) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            clearScreenOn()
        }

        onStopOrDispose { clearScreenOn() }
    }
}
