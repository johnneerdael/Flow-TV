package io.github.aedev.flow.plugin.runtime

import java.io.File
import java.security.MessageDigest

private const val BUDGET_BYTES = 64L * 1024 * 1024

/**
 * Compiled bytecode of the scripts a plugin evaluates, in the app's cache: its entry file, and what
 * it derives at run time through `mb.code.load` (a provider's prepared player, for example). Loading
 * bytecode skips parsing, which is most of a large script's start cost on a TV box. Entries are keyed
 * by name alone, so a key must name its content; the cache lives per plugin version, and the least
 * recently used entries go first once it passes its budget.
 */
internal class CodeCache(
    private val directory: File,
) {
    fun get(key: String): ByteArray? =
        file(key)
            .takeIf { it.isFile }
            ?.also { it.setLastModified(System.currentTimeMillis()) }
            ?.readBytes()

    fun put(
        key: String,
        bytecode: ByteArray,
    ) {
        directory.mkdirs()
        val target = file(key)
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

    private fun file(key: String): File =
        File(directory, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".qjsbc")
}
