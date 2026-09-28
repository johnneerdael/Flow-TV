package io.github.aedev.flow.player.audio.visualizer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.VisualizerPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nl.neerdael.projectm.core.DeviceProfile
import nl.neerdael.projectm.core.ProjectMCore
import nl.neerdael.projectm.core.ProjectMJNI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The embedded projectM engine: whether this device can run it, and its one-time start. The engine
 * is process-wide native state, so it starts on the first visualizer shown and stays up; its
 * compiled shaders then survive closing and reopening now-playing.
 */
@Singleton
class VisualizerEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        preferences: VisualizerPreferences,
    ) {
        private var started = false

        /** The native library ships for ARM only, and projectM needs OpenGL ES 3. */
        val isSupported: Boolean by lazy {
            Build.SUPPORTED_ABIS.any { it in NATIVE_ABIS } &&
                context.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion >= GLES_3
        }

        /** Whether now-playing shows the visualizer: the setting, on a device that can run it. */
        val active: Flow<Boolean> = preferences.enabled.map { it && isSupported }

        /** The auto-resolution height reached last time, so reopening starts there instead of ramping again. */
        var lastAutoHeight = 0

        /** The on-screen visualizer's latest once-a-second frame-rate sample, for the diagnostics line. */
        @Volatile
        var renderStats: VisualizerRenderStats? = null

        val profile: DeviceProfile by lazy { DeviceProfile.detect(context) }

        fun start() {
            if (started) return
            started = true
            ProjectMCore.init(context)
            val mesh = DeviceProfile.MESH_SIZES[profile.defaultMeshLevel()]
            ProjectMJNI.setMeshSize(mesh[0], mesh[1])
            ProjectMJNI.setAutoChange(true)
            ProjectMJNI.setBeatCuts(false)
            ProjectMJNI.setPresetDuration(DEFAULT_PRESET_SECONDS)
            ProjectMJNI.setSoftCutDuration(profile.defaultTransitionSeconds())
            ProjectMJNI.setBlankDetection(true)
            ProjectMJNI.setTransitionMode(ProjectMJNI.TRANSITION_AUTO, profile.lowerBlendResolutionByDefault())
        }

        private companion object {
            val NATIVE_ABIS = setOf("arm64-v8a", "armeabi-v7a")
            const val GLES_3 = 0x30000
            const val DEFAULT_PRESET_SECONDS = 30
        }
    }

/** How the visualizer on screen is rendering: frames per second against its target, and at what size. */
data class VisualizerRenderStats(
    val fps: Float,
    val targetFps: Int,
    val width: Int,
    val height: Int,
    val autoResolution: Boolean,
)
