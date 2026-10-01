package io.github.aedev.flow.ui.tv.screens.account

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.aedev.flow.data.account.signin.PhoneField
import io.github.aedev.flow.data.account.signin.PhoneFrame
import io.github.aedev.flow.data.account.signin.PhoneInput
import io.github.aedev.flow.data.account.signin.PhoneKey
import io.github.aedev.flow.data.account.signin.PhonePointerAction
import io.github.aedev.flow.data.account.signin.SignInCapture
import io.github.aedev.flow.data.account.signin.extract
import io.github.aedev.flow.utils.WebViewStreamer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import nl.neerdael.milkbeat.plugin.WebLoginMethod
import nl.neerdael.milkbeat.plugin.WebLoginResult
import kotlin.coroutines.resume

internal const val LOGIN_PROFILE = "flow-account-signin"

private const val EXTRACTION_TIMEOUT_MS = 15_000L

internal fun loginProfileSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

/**
 * A plugin's web sign-in ([method]) in its own WebView profile, so the login never touches the
 * default profile's cookies. Once a page under the method's success prefix has loaded with every
 * required cookie set, the cookies and the method's extracted values go to [onCaptured] and the
 * profile is wiped.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class LoginWebViewController(
    context: Context,
    private val onPageTitle: (String) -> Unit,
    private val method: WebLoginMethod,
    private val onCaptured: (WebLoginResult) -> Unit,
    window: Window? = null,
) {
    val webView: WebView = WebView(context)
    private val cookies: CookieManager
    private var captured = false
    private val scope = MainScope()
    val stream = WebViewStreamer(webView, window)

    suspend fun frame(): PhoneFrame? = stream.frame()?.let { PhoneFrame(it.width, it.height, it.jpeg) }

    fun pointer(input: PhoneInput.Pointer) {
        val action =
            when (input.action) {
                PhonePointerAction.DOWN -> MotionEvent.ACTION_DOWN
                PhonePointerAction.MOVE -> MotionEvent.ACTION_MOVE
                PhonePointerAction.UP -> MotionEvent.ACTION_UP
                PhonePointerAction.CANCEL -> MotionEvent.ACTION_CANCEL
            }
        stream.pointer(action, input.x, input.y)
    }

    fun visible(visible: Boolean) = stream.visible(visible)

    init {
        WebViewCompat.setProfile(webView, LOGIN_PROFILE)
        cookies = ProfileStore.getInstance().getOrCreateProfile(LOGIN_PROFILE).cookieManager
        cookies.removeAllCookies(null)
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.webViewClient =
            object : WebViewClient() {
                // A store's home page can take many seconds to finish loading every tracker and banner;
                // the session is there as soon as it is shown.
                override fun onPageCommitVisible(
                    view: WebView,
                    url: String?,
                ) {
                    if (!captured && SignInCapture.isSuccessPage(url, method)) capture()
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    method.pageScript?.let { view.evaluateJavascript(it, null) }
                    onPageTitle(view.title.orEmpty())
                    if (!captured && SignInCapture.isSuccessPage(url, method)) capture()
                }
            }
        webView.loadUrl(method.startUrl)
    }

    /** Suspends until the text is in the page, so a following key press can never overtake it. */
    suspend fun typeText(
        text: String,
        field: Int?,
    ) {
        if (!stream.acceptsInput) return
        if (field == null && stream.typeText(text)) return
        evaluate(SignInCapture.insertTextScript(text, field))
    }

    suspend fun pageActions(): List<String> = SignInCapture.parsePageActions(evaluateForResult(SignInCapture.pageActionsScript()))

    suspend fun pageFields(): List<PhoneField> = SignInCapture.parsePageFields(evaluateForResult(SignInCapture.pageFieldsScript()))

    suspend fun clickAction(index: Int) {
        evaluate(SignInCapture.clickActionScript(index))
    }

    private suspend fun evaluate(script: String) {
        evaluateForResult(script)
    }

    private suspend fun evaluateForResult(script: String): String? =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { continuation.resume(it) }
        }

    suspend fun pressKey(key: PhoneKey) {
        val code =
            when (key) {
                PhoneKey.ENTER -> KeyEvent.KEYCODE_ENTER
                PhoneKey.TAB -> KeyEvent.KEYCODE_TAB
                PhoneKey.BACKSPACE -> KeyEvent.KEYCODE_DEL
            }
        stream.pressKey(code)
    }

    fun restart() {
        captured = false
        cookies.removeAllCookies(null)
        cookies.flush()
        webView.loadUrl(method.startUrl)
    }

    fun destroy() {
        scope.cancel()
        stream.close()
        cookies.removeAllCookies(null)
        cookies.flush()
        webView.stopLoading()
        webView.destroy()
    }

    private fun capture() {
        val cookie = cookies.getCookie(method.cookieUrl)
        if (cookie == null || !SignInCapture.hasRequiredCookies(cookie, method)) return
        captured = true
        scope.launch {
            val extracted = webView.extract(method, EXTRACTION_TIMEOUT_MS)
            onCaptured(WebLoginResult(method = method.id, cookies = cookie, extracted = extracted))
            cookies.removeAllCookies(null)
            cookies.flush()
        }
    }
}
