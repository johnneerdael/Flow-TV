package io.github.aedev.flow.plugin.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import nl.neerdael.milkbeat.plugin.PluginJson
import java.io.File

class PluginQuotaException(
    message: String,
) : Exception(message)

/**
 * A plugin's key/value store: one JSON file, written whole on each change, capped at [quotaBytes].
 * [seal] and [open] let the same store keep secrets, sealed with a key that never leaves the device.
 */
internal class PluginStore(
    private val file: File,
    private val quotaBytes: Long,
    private val seal: (String) -> String = { it },
    private val open: (String?) -> String = { it.orEmpty() },
) {
    private val mutex = Mutex()
    private var values: MutableMap<String, String>? = null

    suspend fun get(key: String): String? =
        mutex.withLock {
            loaded()[key]?.let(open)?.takeIf { it.isNotEmpty() }
        }

    suspend fun set(
        key: String,
        value: String,
    ) = mutex.withLock {
        val next = loaded().toMutableMap().apply { put(key, seal(value)) }
        val size = next.entries.sumOf { (k, v) -> k.length + v.length }.toLong()
        if (size > quotaBytes) throw PluginQuotaException("Storage quota of $quotaBytes bytes exceeded")
        save(next)
    }

    suspend fun delete(key: String) =
        mutex.withLock {
            val current = loaded()
            if (key in current) save(current.toMutableMap().apply { remove(key) })
        }

    suspend fun entries(): Map<String, String> = mutex.withLock { loaded().mapValues { open(it.value) } }

    private suspend fun loaded(): MutableMap<String, String> =
        values ?: withContext(Dispatchers.IO) {
            runCatching { PluginJson.decodeFromString(serializer, file.readText()).toMutableMap() }
                .getOrElse { mutableMapOf() }
        }.also { values = it }

    private suspend fun save(next: MutableMap<String, String>) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(PluginJson.encodeToString(serializer, next))
            check(temp.renameTo(file)) { "Could not save ${file.name}" }
        }
        values = next
    }

    private companion object {
        val serializer = MapSerializer(String.serializer(), String.serializer())
    }
}
