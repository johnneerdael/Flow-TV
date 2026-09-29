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
    TIMEOUT,
    UNSUPPORTED,
    INTERNAL,
}

@Serializable
data class PluginError(
    val code: PluginErrorCode,
    val message: String,
    /** Said to the listener as it is, e.g. "This video is not available in your country". */
    val userMessage: String? = null,
    val retryAfterMs: Long? = null,
    /** For logs only, such as a stack trace. */
    val detail: String? = null,
)
