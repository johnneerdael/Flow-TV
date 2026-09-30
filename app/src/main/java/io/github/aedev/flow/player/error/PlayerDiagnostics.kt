package io.github.aedev.flow.player.error

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Player diagnostics utility.
 *
 * Maintains an in-memory circular log buffer of player events and errors so that
 * when a user encounters a playback problem the developer can ask them to tap
 * "Copy Logs" and paste the result directly into a bug report — no ADB required.
 *
 * Usage:
 *   PlayerDiagnostics.log("TAG", "Something happened")
 *   PlayerDiagnostics.logError("TAG", "Error occurred", throwable)
 *   PlayerDiagnostics.buildReport(context)  // → formatted string
 *   PlayerDiagnostics.copyToClipboard(context)
 */
@UnstableApi
object PlayerDiagnostics {
    private const val MAX_LOG_ENTRIES = 300
    private val DATE_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // ── In-memory ring buffer ──────────────────────────────────────────────────

    data class LogEntry(
        val timestampMs: Long,
        val level: String, // "D", "I", "W", "E"
        val tag: String,
        val message: String,
        val errorCode: Int? = null, // PlaybackException error code, if applicable
        val throwableType: String? = null,
        val throwableMsg: String? = null,
    ) {
        override fun toString(): String {
            val ts = DATE_FORMAT.format(Date(timestampMs))
            val ex =
                when {
                    throwableType != null && throwableMsg != null -> " [$throwableType: $throwableMsg]"
                    throwableType != null -> " [$throwableType]"
                    else -> ""
                }
            val ec = if (errorCode != null) " (errCode=$errorCode)" else ""
            return "$ts $level/$tag: $message$ec$ex"
        }
    }

    private val buffer = ConcurrentLinkedDeque<LogEntry>()

    // ── Logging helpers ────────────────────────────────────────────────────────

    fun log(
        tag: String,
        message: String,
    ) {
        append(LogEntry(System.currentTimeMillis(), "D", tag, message))
    }

    fun logInfo(
        tag: String,
        message: String,
    ) {
        append(LogEntry(System.currentTimeMillis(), "I", tag, message))
    }

    fun logWarning(
        tag: String,
        message: String,
    ) {
        append(LogEntry(System.currentTimeMillis(), "W", tag, message))
    }

    fun logError(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        append(
            LogEntry(
                timestampMs = System.currentTimeMillis(),
                level = "E",
                tag = tag,
                message = message,
                throwableType = throwable?.javaClass?.simpleName,
                throwableMsg = throwable?.message?.take(200),
            ),
        )
    }

    fun logPlaybackError(
        tag: String,
        error: PlaybackException,
    ) {
        append(
            LogEntry(
                timestampMs = System.currentTimeMillis(),
                level = "E",
                tag = tag,
                message = "PlaybackException: ${error.message?.take(200)}",
                errorCode = error.errorCode,
                throwableType = error.cause?.javaClass?.simpleName,
                throwableMsg = error.cause?.message?.take(200),
            ),
        )
    }

    fun logRefocusGlitch(
        tag: String,
        detail: String,
    ) {
        append(
            LogEntry(
                timestampMs = System.currentTimeMillis(),
                level = "W",
                tag = tag,
                message = "REFOCUS_GLITCH: $detail",
            ),
        )
    }

    private fun append(entry: LogEntry) {
        buffer.addLast(entry)
        // Trim to MAX_LOG_ENTRIES
        while (buffer.size > MAX_LOG_ENTRIES) {
            buffer.pollFirst()
        }
    }

    // ── Clear ──────────────────────────────────────────────────────────────────

    fun clear() = buffer.clear()
}
