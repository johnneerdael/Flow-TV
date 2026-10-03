package io.github.aedev.flow.plugin.mirror

import java.util.concurrent.TimeUnit

internal class MirrorPlaybackHandoff(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private data class Prepared(
        val record: MirrorRecord,
        val context: Any,
        val verifiedAtNanos: Long,
    )

    private var prepared: Prepared? = null

    fun offer(
        record: MirrorRecord,
        context: Any,
    ) {
        prepared = record.takeIf { it.ready && it.destination != null }?.let { Prepared(it, context, nowNanos()) }
    }

    fun take(
        key: MirrorKey,
        title: String,
        context: Any,
    ): MirrorRecord? {
        val candidate = prepared
        prepared = null
        return candidate
            ?.takeIf {
                it.record.key == key && it.record.title == title && it.context == context &&
                    nowNanos() - it.verifiedAtNanos in 0 until TimeUnit.SECONDS.toNanos(60)
            }?.record
    }

    fun invalidate(key: MirrorKey) {
        if (prepared?.record?.key == key) prepared = null
    }

    fun retainIf(isValid: (MirrorKey, Any) -> Boolean) {
        prepared = prepared?.takeIf { isValid(it.record.key, it.context) }
    }

    fun clear(record: MirrorRecord? = null) {
        if (record == null || prepared?.record === record) prepared = null
    }
}
