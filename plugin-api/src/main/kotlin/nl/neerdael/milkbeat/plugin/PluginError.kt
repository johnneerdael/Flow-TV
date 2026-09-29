package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.Serializable

/** How the host reacts to a failed call; see `docs/plugin-architecture.md` §5.4. */
@Serializable
enum class PluginErrorCode {
    NOT_FOUND,
    UNAVAILABLE,
    SIGN_IN_REQUIRED,
    SIGN_IN_EXPIRED,
    RATE_LIMITED,
    NETWORK,
    UNSUPPORTED,
    INTERNAL,
}

@Serializable
data class PluginError(
    val code: PluginErrorCode,
    val message: String,
    val retryAfterMs: Long? = null,
)
