package io.github.aedev.flow.player

data class MusicPlayerState(
    val isPlaying: Boolean = false,
    val isEnded: Boolean = false,
    val isBuffering: Boolean = false,
    val isPreparing: Boolean = false,
    val isReady: Boolean = false,
    val playWhenReady: Boolean = false,
    val duration: Long = 0,
    val position: Long = 0,
)

enum class RepeatMode {
    OFF,
    ALL,
    ONE,
}
