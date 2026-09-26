package io.github.aedev.flow.ui.tv.screens.account

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.data.account.signin.PhoneKey
import io.github.aedev.flow.data.account.signin.SignInCapture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal const val LOGIN_PROFILE = "flow-account-signin"
private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
private const val MUSIC_URL = "https://music.youtube.com"
private const val YTCFG_SCRIPT =
    "JSON.stringify({" +
        "v: (window.ytcfg && ytcfg.get && ytcfg.get('VISITOR_DATA')) || (window.yt && yt.config_ && yt.config_.VISITOR_DATA) || null," +
        "d: (window.ytcfg && ytcfg.get && ytcfg.get('DATASYNC_ID')) || (window.yt && yt.config_ && yt.config_.DATASYNC_ID) || null" +
        "})"

internal fun loginProfileSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

/**
 * Google sign-in in its own WebView profile. The default profile's cookie store is shared with the
 * PoToken and cipher WebViews that playback depends on, so the login must never touch it.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class LoginWebViewController(
    context: Context,
    private val onPageTitle: (String) -> Unit,
    private val onSessionCaptured: (AccountSession) -> Unit,
) {
    val webView: WebView = WebView(context)
    private val cookies: CookieManager
    private var captured = false

    init {
        WebViewCompat.setProfile(webView, LOGIN_PROFILE)
        cookies = ProfileStore.getInstance().getOrCreateProfile(LOGIN_PROFILE).cookieManager
        cookies.removeAllCookies(null)
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    onPageTitle(view.title.orEmpty())
                    if (!captured && SignInCapture.isYouTubeMusic(url)) capture()
                }
            }
        webView.loadUrl(LOGIN_URL)
    }

    /** Suspends until the text is in the page, so a following key press can never overtake it. */
    suspend fun typeText(text: String) {
        webView.requestFocus()
        evaluate(SignInCapture.insertTextScript(text))
        hideKeyboard()
    }

    private suspend fun evaluate(script: String) =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { continuation.resume(Unit) }
        }

    suspend fun pressKey(key: PhoneKey) {
        val code =
            when (key) {
                PhoneKey.ENTER -> KeyEvent.KEYCODE_ENTER
                PhoneKey.TAB -> KeyEvent.KEYCODE_TAB
                PhoneKey.BACKSPACE -> KeyEvent.KEYCODE_DEL
            }
        webView.requestFocus()
        evaluate(SignInCapture.focusFieldScript())
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        hideKeyboard()
    }

    // Typing happens on the phone; the TV's on-screen keyboard would only cover the sign-in page.
    private fun hideKeyboard() {
        webView.context
            .getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(webView.windowToken, 0)
    }

    fun destroy() {
        cookies.removeAllCookies(null)
        cookies.flush()
        webView.stopLoading()
        webView.destroy()
    }

    private fun capture() {
        val cookie = cookies.getCookie(MUSIC_URL)
        if (cookie == null || !SignInCapture.hasSession(cookie)) return
        captured = true
        webView.evaluateJavascript(YTCFG_SCRIPT) { raw ->
            val (visitorData, dataSyncId) = SignInCapture.parseYtcfg(raw)
            onSessionCaptured(SignInCapture.session(cookie, visitorData, dataSyncId))
            cookies.removeAllCookies(null)
            cookies.flush()
        }
    }
}
