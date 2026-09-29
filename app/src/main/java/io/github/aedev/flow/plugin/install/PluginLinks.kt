package io.github.aedev.flow.plugin.install

import android.net.Uri
import io.github.aedev.flow.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A plugin link the app was opened with (`milkbeat://add-plugin?url=…`), waiting for Settings,
 * Plugins to fetch it and ask the listener; typing a URL on a remote is slow, so a link from a phone,
 * a QR code or adb is the usual way in.
 */
@Singleton
class PluginLinks
    @Inject
    constructor() {
        private val _pending = MutableStateFlow<String?>(null)
        val pending: StateFlow<String?> = _pending.asStateFlow()

        /** Takes the plugin URL out of [uri] if it is an add-plugin link; true when it was one. */
        fun offer(uri: Uri?): Boolean {
            if (uri?.scheme != "milkbeat" || uri.host != "add-plugin") return false
            // Debug builds also take plain http, so a plugin can be served from a dev machine on the LAN.
            val url =
                uri.getQueryParameter("url")?.takeIf { it.startsWith("https://") || (BuildConfig.DEBUG && it.startsWith("http://")) }
                    ?: return false
            _pending.value = url
            return true
        }

        fun consume() {
            _pending.value = null
        }
    }
