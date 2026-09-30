package io.github.aedev.flow.ui.tv.screens.account

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Window
import android.webkit.WebView
import io.github.aedev.flow.data.account.signin.PhoneFrame
import io.github.aedev.flow.data.account.signin.PhoneInput
import io.github.aedev.flow.data.account.signin.PhonePointerAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class LoginRemoteView(
    private val view: WebView,
    private val window: Window,
) {
    var visible = true
    var viewportBounds: Rect? = null
    private var downTime: Long? = null

    suspend fun frame(): PhoneFrame? {
        val bitmap = withContext(Dispatchers.Main.immediate) { snapshot() } ?: return null
        try {
            return withContext(Dispatchers.Default) {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, output)
                PhoneFrame(bitmap.width, bitmap.height, Base64.getEncoder().encodeToString(output.toByteArray()))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun snapshot(): Bitmap? {
        if (!visible || !view.isShown || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = viewportBounds ?: Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
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
                        continuation.resume(bitmap)
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

    fun pointer(input: PhoneInput.Pointer) {
        if (!visible || !view.isShown) return
        val now = SystemClock.uptimeMillis()
        if (input.action == PhonePointerAction.DOWN) downTime = now
        val start = downTime ?: return
        val action =
            when (input.action) {
                PhonePointerAction.DOWN -> MotionEvent.ACTION_DOWN
                PhonePointerAction.MOVE -> MotionEvent.ACTION_MOVE
                PhonePointerAction.UP -> MotionEvent.ACTION_UP
                PhonePointerAction.CANCEL -> MotionEvent.ACTION_CANCEL
            }
        val event = MotionEvent.obtain(start, now, action, input.x * max(0, view.width - 1), input.y * max(0, view.height - 1), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
        if (input.action == PhonePointerAction.UP || input.action == PhonePointerAction.CANCEL) downTime = null
    }
}

private const val MAX_FRAME_EDGE = 960
private const val FRAME_QUALITY = 75
