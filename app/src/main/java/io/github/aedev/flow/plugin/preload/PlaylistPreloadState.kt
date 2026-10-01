package io.github.aedev.flow.plugin.preload

data class PlaylistPreloadProgress(
    val collections: Int = 0,
    val scannedCollections: Int = 0,
    val indexed: Int = 0,
    val matched: Int = 0,
    val unavailable: Int = 0,
    val finished: Boolean = false,
)

enum class PlaylistPreloadFailure {
    ACCOUNT_CHANGED,
    PROVIDERS_CHANGED,
    PLUGIN_CHANGED,
    NO_AUDIO_PROVIDER,
    INVALID_PAGINATION,
    UNSUPPORTED_LIBRARY,
}

class PlaylistPreloadException(
    val reason: PlaylistPreloadFailure,
) : Exception(reason.name)
