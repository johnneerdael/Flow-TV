package io.github.aedev.flow.player.audio

/**
 * Offload only saves battery with the screen off, which a TV never needs, and some TV audio chips
 * accept an offloaded track and then never advance it: playback looks started but stays silent at 0:00.
 * Offloaded audio also bypasses every audio processor, so it stays off while any of them is needed.
 */
internal fun shouldOffloadAudio(
    isTv: Boolean,
    needsProcessors: Boolean,
): Boolean = !isTv && !needsProcessors
