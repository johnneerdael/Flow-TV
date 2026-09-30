package io.github.aedev.flow.plugin.host

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.account.signin.extract
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.WebLoginMethod
import nl.neerdael.milkbeat.plugin.WebLoginRefreshRequest
import nl.neerdael.milkbeat.plugin.WebLoginResult
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val REFRESH_PROFILE = "flow-plugin-web-refresh"
private const val PAGE_TIMEOUT_MS = 20_000L
private const val EXTRACTION_TIMEOUT_MS = 10_000L

/**
 * Runs a plugin's `signIn.refresh`: its web sign-in's [WebLoginMethod.refreshUrl] opened in a web view
 * of its own profile holding only the cookies the plugin handed over, the method's extraction script
 * run again there, and the fresh values and cookies answered. The profile is wiped before and after,
 * so nothing outlives the call and no other page ever sees those cookies.
 */
@Singleton
class WebLoginRefresher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val mutex = Mutex()

        internal suspend fun refresh(
            plugin: InstalledPlugin,
            request: WebLoginRefreshRequest,
        ): WebLoginResult {
            val method =
                plugin.manifest.signIn
                    .filterIsInstance<WebLoginMethod>()
                    .firstOrNull { it.id == request.method }
                    ?: throw HostCallException(PluginErrorCode.UNSUPPORTED, "${plugin.id} has no web sign-in ${request.method}")
            val url = method.refreshUrl ?: throw HostCallException(PluginErrorCode.UNSUPPORTED, "${method.id} cannot be refreshed")
            if (!sameSecureHost(url, method.cookieUrl)) {
                throw HostCallException(PluginErrorCode.UNSUPPORTED, "$url is not on the sign-in's own site")
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                throw HostCallException(PluginErrorCode.UNSUPPORTED, "This web view cannot keep a sign-in apart")
            }
            return mutex.withLock {
                withContext(Dispatchers.Main) { withTimeout(PAGE_TIMEOUT_MS + EXTRACTION_TIMEOUT_MS) { run(method, url, request.cookies) } }
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private suspend fun run(
            method: WebLoginMethod,
            url: String,
            cookieHeader: String,
        ): WebLoginResult {
            val webView = WebView(context)
            WebViewCompat.setProfile(webView, REFRESH_PROFILE)
            val cookies = ProfileStore.getInstance().getOrCreateProfile(REFRESH_PROFILE).cookieManager
            try {
                cookies.clear()
                cookieHeader
                    .split(';')
                    .map { it.trim() }
                    .filter { '=' in it }
                    .forEach { cookies.setCookie(method.cookieUrl, "$it; Secure; Path=/") }
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                val loaded = CompletableDeferred<Unit>()
                webView.webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView,
                            finishedUrl: String?,
                        ) {
                            loaded.complete(Unit)
                        }
                    }
                webView.loadUrl(url)
                withTimeout(PAGE_TIMEOUT_MS) { loaded.await() }
                val extracted = webView.extract(method, EXTRACTION_TIMEOUT_MS)
                return WebLoginResult(
                    method = method.id,
                    cookies = cookies.getCookie(method.cookieUrl) ?: cookieHeader,
                    extracted = extracted,
                )
            } finally {
                cookies.clear()
                webView.stopLoading()
                webView.destroy()
            }
        }

        private suspend fun CookieManager.clear() {
            suspendCancellableCoroutine { continuation -> removeAllCookies { continuation.resume(Unit) } }
            flush()
        }

        private fun sameSecureHost(
            url: String,
            cookieUrl: String,
        ): Boolean {
            val page = runCatching { URI(url) }.getOrNull() ?: return false
            val site = runCatching { URI(cookieUrl) }.getOrNull() ?: return false
            return page.scheme.equals("https", ignoreCase = true) && page.host != null && page.host.equals(site.host, ignoreCase = true)
        }
    }
