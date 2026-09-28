package io.github.aedev.flow.ui.tv.music

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import io.github.aedev.flow.player.audio.visualizer.VisualizerRenderStats
import nl.neerdael.projectm.core.DisplayInfo
import nl.neerdael.projectm.core.PcmConverter
import nl.neerdael.projectm.core.ProjectMJNI
import nl.neerdael.projectm.core.QualityController
import nl.neerdael.projectm.core.VisualizerRenderer
import nl.neerdael.projectm.core.VisualizerView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One visualizer on screen: the projectM view, fed each frame with the samples the tap says are
 * audible, and kept at a smooth frame rate by ProjectM-TV's adaptive resolution. Wired the way
 * ProjectM-TV's own activity wires it, so both apps render the same.
 */
internal class TvVisualizerHost(
    context: Context,
    private val viewModel: TvVisualizerViewModel,
) : ComponentCallbacks2 {
    private val engine = viewModel.engine
    private val main = Handler(Looper.getMainLooper())
    private val display = DisplayInfo.detect(context)
    private var quality: QualityController? = null
    private var targetFps = 0
    private var listening = false
    private val renderer =
        VisualizerRenderer(
            object : VisualizerRenderer.StatsListener {
                override fun onFpsSample(fps: Float) {
                    main.post { onFps(fps) }
                }

                override fun onPresetChanged() {
                    main.post { quality?.onPresetChanged() }
                }
            },
        )

    val view = VisualizerView(context)

    init {
        engine.start()
        val profile = engine.profile
        val divisor = frameDivisor(display.refreshRate, profile.defaultFrameRateCap())
        targetFps = (display.refreshRate / divisor).roundToInt()
        quality =
            QualityController(display, profile, profile.memorySafeHeight(), ::applyRenderHeight).apply {
                setTransitionSeconds(profile.defaultTransitionSeconds())
                setSkipSlowPresets(profile.defaultSkipSlowPresets())
                setTargetFps(display.refreshRate / divisor)
                setMode(0, engine.lastAutoHeight)
            }
        view.start(AudioFedRenderer(renderer, viewModel::readAudible))
        view.setFrameDivisor(divisor)
        ProjectMJNI.setForceHardCut(false)
    }

    fun resume() {
        view.onResume()
        if (!listening) viewModel.startListening()
        listening = true
    }

    fun pause() {
        if (listening) viewModel.stopListening()
        listening = false
        view.onPause()
    }

    /** projectM owns GL objects, so it is released on the GL thread; a later start cleans up if that thread is gone. */
    fun close() {
        pause()
        engine.renderStats = null
        main.removeCallbacksAndMessages(null)
        view.queueEvent(renderer::release)
    }

    private fun onFps(fps: Float) {
        val quality = quality ?: return
        if (quality.onFpsSample(fps) == QualityController.ACTION_SKIP) ProjectMJNI.skipCurrentPreset()
        engine.renderStats = VisualizerRenderStats(fps, targetFps, renderer.surfaceWidth, renderer.surfaceHeight, quality.isAuto)
    }

    private fun applyRenderHeight(height: Int) {
        view.setRenderSize(display.widthForHeight(height), height)
        ProjectMJNI.setForceHardCut(false)
        quality?.takeIf { it.isAuto }?.let { engine.lastAutoHeight = it.autoHeightToRemember() }
    }

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            quality?.onMemoryPressure(level)
            ProjectMJNI.onMemoryPressure()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = Unit
}

/** Renders on every n-th vsync, n in 1, 2 or 4, whichever rate lands closest to [cap] without dropping below 24 fps. */
internal fun frameDivisor(
    refreshRate: Float,
    cap: Int,
): Int = listOf(1, 2, 4).filter { it == 1 || refreshRate / it >= MIN_FPS }.minBy { abs(refreshRate / it - cap) }

private const val MIN_FPS = 24f

// projectM 4.1 takes at most this many samples per frame; the engine keeps only the newest.
private const val WINDOW_SAMPLES = 576
private const val SILENCE: Byte = -128

private class AudioFedRenderer(
    private val delegate: VisualizerRenderer,
    private val readAudible: (ShortArray) -> Boolean,
) : GLSurfaceView.Renderer {
    private val window = ShortArray(WINDOW_SAMPLES)
    private val waveform = ByteArray(WINDOW_SAMPLES)

    override fun onSurfaceCreated(
        gl: GL10,
        config: EGLConfig,
    ) = delegate.onSurfaceCreated(gl, config)

    override fun onSurfaceChanged(
        gl: GL10,
        width: Int,
        height: Int,
    ) = delegate.onSurfaceChanged(gl, width, height)

    override fun onDrawFrame(gl: GL10) {
        if (readAudible(window)) PcmConverter.toUnsignedMono8(window, WINDOW_SAMPLES, 1, waveform) else waveform.fill(SILENCE)
        ProjectMJNI.addWaveform(waveform, WINDOW_SAMPLES)
        delegate.onDrawFrame(gl)
    }
}
