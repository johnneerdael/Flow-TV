package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.Serializable

/**
 * A sign-in the host runs in its own web view, driven from the listener's phone: it opens
 * [startUrl], waits until a page under [successUrlPrefix] has loaded with every cookie in
 * [requiredCookies] set for [cookieUrl], runs [extractScript] there (it must evaluate to a JSON object
 * of strings), then hands the result to the plugin as a [WebLoginResult] and forgets it.
 */
@Serializable
data class WebLoginMethod(
    val id: String,
    val label: String,
    val startUrl: String,
    val successUrlPrefix: String,
    val cookieUrl: String,
    val requiredCookies: List<String>,
    val extractScript: String? = null,
)

@Serializable
data class WebLoginResult(
    val method: String,
    val cookies: String,
    val extracted: Map<String, String> = emptyMap(),
)
