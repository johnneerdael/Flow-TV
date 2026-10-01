package io.github.aedev.flow.utils

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class WebViewStreamFrame(
    val width: Int,
    val height: Int,
    val jpeg: String,
)

class WebViewStreamer(
    val view: WebView,
    private val window: Window?,
) : AutoCloseable {
    private var closed = false
    var visible = true
        private set

    val acceptsInput: Boolean get() = visible && view.isShown && view.isAttachedToWindow

    fun visible(visible: Boolean) {
        if (closed) return
        if (!visible) {
            pointer(MotionEvent.ACTION_CANCEL, 0f, 0f)
            downTime = null
        }
        this.visible = visible
        if (visible) view.onResume() else view.onPause()
    }

    override fun close() {
        if (closed) return
        visible(false)
        closed = true
        viewportBounds = null
    }

    fun typeText(text: String): Boolean {
        if (!acceptsInput) return false
        view.requestFocus()
        val inserted = view.onCreateInputConnection(EditorInfo())?.commitText(text, 1) == true
        hideKeyboard()
        return inserted
    }

    fun pressKey(code: Int) {
        if (!acceptsInput) return
        view.requestFocus()
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        hideKeyboard()
    }

    private fun hideKeyboard() {
        view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    var viewportBounds: Rect? = null
    private var downTime: Long? = null

    suspend fun frame(): WebViewStreamFrame? =
        withContext(Dispatchers.Main.immediate) {
            val bitmap = snapshot() ?: return@withContext null
            try {
                val frame =
                    withContext(Dispatchers.Default) {
                        val output = ByteArrayOutputStream()
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, output)) return@withContext null
                        WebViewStreamFrame(bitmap.width, bitmap.height, Base64.getEncoder().encodeToString(output.toByteArray()))
                    }
                if (visible && view.isShown) frame else null
            } finally {
                bitmap.recycle()
            }
        }

    private suspend fun snapshot(): Bitmap? {
        if (!visible || !view.isShown || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val window = window ?: return null
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = viewportBounds ?: Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        if (rect.isEmpty) return null
        val scale = min(1f, MAX_FRAME_EDGE.toFloat() / max(rect.width(), rect.height()))
        val bitmap =
            Bitmap.createBitmap(
                max(1, (rect.width() * scale).roundToInt()),
                max(1, (rect.height() * scale).roundToInt()),
                Bitmap.Config.ARGB_8888,
            )
        return suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(window, rect, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS && continuation.isActive) {
                        continuation.resume(bitmap) { _, value, _ -> value?.recycle() }
                    } else {
                        bitmap.recycle()
                        if (continuation.isActive) continuation.resume(null)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: IllegalArgumentException) {
                bitmap.recycle()
                continuation.resume(null)
            }
        }
    }

    fun pointer(
        action: Int,
        x: Float,
        y: Float,
    ) {
        if (!acceptsInput || !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return
        if (action !in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val start = downTime ?: return
        view.requestFocus()
        val event = MotionEvent.obtain(start, now, action, x * max(0, view.width - 1), y * max(0, view.height - 1), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
            hideKeyboard()
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) downTime = null
    }
}

private const val MAX_FRAME_EDGE = 960
private const val FRAME_QUALITY = 75
