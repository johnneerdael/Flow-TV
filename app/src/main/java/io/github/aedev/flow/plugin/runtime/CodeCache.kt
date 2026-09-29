package io.github.aedev.flow.plugin.runtime

import java.io.File
import java.security.MessageDigest

private const val BUDGET_BYTES = 64L * 1024 * 1024

/**
 * Compiled bytecode of the scripts a plugin evaluates, in the app's cache: its entry file, and what
 * it derives at run time through `mb.code.load` (a provider's prepared player, for example). Loading
 * bytecode skips parsing, which is most of a large script's start cost on a TV box. Entries are keyed
 * by the plugin's key and a hash of the source, so changed code is never served stale; the least
 * recently used go first once the cache passes its budget.
 */
internal class CodeCache(
    private val directory: File,
) {
    fun get(
        key: String,
        source: String,
    ): ByteArray? =
        file(key, source)
            .takeIf { it.isFile }
            ?.also { it.setLastModified(System.currentTimeMillis()) }
            ?.readBytes()

    fun put(
        key: String,
        source: String,
        bytecode: ByteArray,
    ) {
        directory.mkdirs()
        val target = file(key, source)
        val temp = File(directory, "${target.name}.tmp")
        temp.writeBytes(bytecode)
        if (temp.renameTo(target)) trim() else temp.delete()
    }

    private fun trim() {
        val files = directory.listFiles { file -> file.extension == "qjsbc" }.orEmpty().sortedByDescending { it.lastModified() }
        var total = 0L
        files.forEach { file ->
            total += file.length()
            if (total > BUDGET_BYTES) file.delete()
        }
    }

    private fun file(
        key: String,
        source: String,
    ): File {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(key.toByteArray())
        digest.update(0)
        digest.update(source.toByteArray())
        return File(directory, digest.digest().joinToString("") { "%02x".format(it) } + ".qjsbc")
    }
}
