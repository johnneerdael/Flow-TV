package io.github.aedev.flow.innertube

import io.ktor.client.plugins.api.createClientPlugin

class AccountEndpointBlockedException(
    url: String,
) : IllegalStateException("Account session may not call $url")

data class AccountEndpointPolicy(
    private val allowed: Set<String>,
) {
    fun permits(
        host: String,
        encodedPath: String,
    ): Boolean = "$host$encodedPath" in allowed

    companion object {
        /**
         * Feed reads, plus what adding a play to the account's history takes: the YouTube Music player
         * response (for its playback-tracking URL) and that URL's playback ping. Nothing that likes,
         * subscribes or edits is allowed.
         */
        val ACCOUNT =
            AccountEndpointPolicy(
                setOf(
                    "music.youtube.com/youtubei/v1/browse",
                    "music.youtube.com/youtubei/v1/account/account_menu",
                    "www.youtube.com/youtubei/v1/browse",
                    "music.youtube.com/youtubei/v1/player",
                    "music.youtube.com/api/stats/playback",
                ),
            )
    }
}

class AccountEndpointGuardConfig {
    var policy: AccountEndpointPolicy? = null
}

// Runs after DefaultRequest has resolved relative paths, so the host is always known here.
val AccountEndpointGuard =
    createClientPlugin("AccountEndpointGuard", ::AccountEndpointGuardConfig) {
        val policy = requireNotNull(pluginConfig.policy) { "AccountEndpointGuard needs a policy" }
        onRequest { request, _ ->
            val url = request.url.build()
            if (!policy.permits(url.host, url.encodedPath)) {
                throw AccountEndpointBlockedException("${url.host}${url.encodedPath}")
            }
        }
    }
