package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A way to sign in that the host knows how to run. Only [WebLoginMethod] exists in API v1. */
@Serializable
sealed interface SignInMethod {
    val id: String
    val label: String
}

/**
 * A sign-in the host runs in its own web view, driven from the listener's phone: it opens
 * [startUrl], waits until a page under [successUrlPrefix] has loaded with every cookie in
 * [requiredCookies] set for [cookieUrl], runs [extractScript] there (it must evaluate to a JSON object
 * of strings), then hands the result to the plugin as a [WebLoginResult] and forgets it.
 */
@Serializable
@SerialName("webLogin")
data class WebLoginMethod(
    override val id: String,
    override val label: String,
    val startUrl: String,
    val successUrlPrefix: String,
    val cookieUrl: String,
    val requiredCookies: List<String>,
    val extractScript: String? = null,
) : SignInMethod

@Serializable
data class WebLoginResult(
    val method: String,
    val cookies: String,
    val extracted: Map<String, String> = emptyMap(),
)
