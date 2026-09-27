package io.github.aedev.flow.ui.tv.music

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.player.audio.visualizer.VisualizerAudioTap
import io.github.aedev.flow.player.audio.visualizer.VisualizerEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import nl.neerdael.projectm.core.ProjectMJNI
import javax.inject.Inject

/** The now-playing visualizer: whether it shows, the engine behind it, and the audio it hears. */
@HiltViewModel
class TvVisualizerViewModel
    @Inject
    constructor(
        val engine: VisualizerEngine,
        private val tap: VisualizerAudioTap,
    ) : ViewModel() {
        val active: StateFlow<Boolean> = engine.active.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        fun startListening() = tap.acquire()

        fun stopListening() = tap.release()

        /** Called on the render thread every frame. */
        fun readAudible(out: ShortArray): Boolean = tap.readAudible(out)

        /** Left steps back through the presets shown, right jumps to a random one, as in ProjectM-TV. */
        fun stepPreset(forward: Boolean) {
            if (forward) ProjectMJNI.randomPreset(true) else ProjectMJNI.previousPreset(true)
        }
    }

/** The preset step a remote key asks for while the controls are hidden: right forward, left back. */
internal fun presetStepFor(keyCode: Int): Boolean? =
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT -> true
        KeyEvent.KEYCODE_DPAD_LEFT -> false
        else -> null
    }
