package io.github.aedev.flow.data.account.signin

import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import nl.neerdael.milkbeat.plugin.WebLoginMethod
import kotlin.coroutines.resume

private const val POLL_INTERVAL_MS = 100L

/**
 * Runs [method]'s extraction script in this page and waits, up to [timeoutMs], for its value, which may
 * come from a promise; empty when it never settles. Call on the main thread.
 */
internal suspend fun WebView.extract(
    method: WebLoginMethod,
    timeoutMs: Long,
): Map<String, String> {
    evaluate(SignInCapture.extractionScript(method))
    val raw =
        withTimeoutOrNull(timeoutMs) {
            var result: String?
            do {
                delay(POLL_INTERVAL_MS)
                result = evaluate(SignInCapture.extractionResultScript())
            } while (!SignInCapture.extractionSettled(result))
            result
        }
    return SignInCapture.parseExtracted(raw)
}

private suspend fun WebView.evaluate(script: String): String? =
    suspendCancellableCoroutine { continuation -> evaluateJavascript(script) { continuation.resume(it) } }
