package io.github.aedev.flow.plugin.pkg

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginPackageReaderTest {
    private val author = keyPair()
    private val manifest =
        """{"format":1,"api":{"min":1,"target":1},"id":"dev.example.demo","name":"Demo","version":"1.0.0",""" +
            """"versionCode":3,"roles":{"audio":{"idSpaces":["demo"]}}}"""
    private val files = mapOf("manifest.json" to manifest, "plugin.js" to "definePlugin({})", "assets/a.txt" to "hello")

    @Test
    fun `a signed package reads with its manifest, files and author`() {
        val plugin = PluginPackageReader.read(pack(files))

        assertThat(plugin.manifest.id).isEqualTo("dev.example.demo")
        assertThat(plugin.manifest.versionCode).isEqualTo(3)
        assertThat(plugin.files.keys).containsExactly("manifest.json", "plugin.js", "assets/a.txt")
        assertThat(plugin.signerFingerprint).isEqualTo(PluginPackageReader.fingerprint(author.public))
    }

    @Test
    fun `a package packed and signed by mbplugin verifies here`() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/plugins/fixture-signed.mbplugin"))
        val plugin = fixture.use(PluginPackageReader::read)

        assertThat(plugin.manifest.id).isEqualTo("dev.milkbeat.fixture")
        assertThat(plugin.files.keys).containsExactly("manifest.json", "plugin.js", "assets/page.html")
    }

    @Test
    fun `an altered file is refused`() {
        assertRefused(PluginPackageException.Reason.TAMPERED, pack(files, alter = "plugin.js"))
    }

    @Test
    fun `a file smuggled in after signing is refused`() {
        assertRefused(PluginPackageException.Reason.TAMPERED, pack(files, extra = "evil.js" to "x"))
    }

    @Test
    fun `a listing signed by another key is refused`() {
        assertRefused(PluginPackageException.Reason.TAMPERED, pack(files, signer = keyPair(), publicKey = author))
    }

    @Test
    fun `an unsigned package is refused`() {
        assertRefused(PluginPackageException.Reason.UNSIGNED, pack(files, signed = false))
    }

    @Test
    fun `an entry escaping the package is refused`() {
        assertRefused(PluginPackageException.Reason.MALFORMED, pack(files + ("../outside.js" to "x")))
    }

    @Test
    fun `a plugin for a newer API is refused as incompatible`() {
        val future = files + ("manifest.json" to manifest.replace("\"min\":1", "\"min\":99"))
        assertRefused(PluginPackageException.Reason.INCOMPATIBLE, pack(future))
    }

    @Test
    fun `a manifest naming a missing entry file is refused`() {
        assertRefused(PluginPackageException.Reason.MALFORMED, pack(files - "plugin.js"))
    }

    private fun assertRefused(
        reason: PluginPackageException.Reason,
        bytes: ByteArrayInputStream,
    ) {
        val error = assertThrows(PluginPackageException::class.java) { PluginPackageReader.read(bytes) }
        assertThat(error.reason).isEqualTo(reason)
    }

    private fun pack(
        files: Map<String, String>,
        alter: String? = null,
        extra: Pair<String, String>? = null,
        signer: KeyPair = author,
        publicKey: KeyPair = signer,
        signed: Boolean = true,
    ): ByteArrayInputStream {
        val listing =
            files.entries
                .sortedBy { it.key }
                .joinToString("") { (path, text) -> "${sha256(text.toByteArray())}  $path\n" }
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(signer.private)
                update(listing.toByteArray())
                sign()
            }
        val envelope =
            """{"algorithm":"ECDSA_P256_SHA256","publicKey":"${Base64.getEncoder().encodeToString(publicKey.public.encoded)}",""" +
                """"signature":"${Base64.getEncoder().encodeToString(signature)}"}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(
                name: String,
                text: String,
            ) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            files.forEach { (path, text) -> put(path, if (path == alter) "$text // changed" else text) }
            extra?.let { (path, text) -> put(path, text) }
            if (signed) {
                put("META-INF/CONTENTS", listing)
                put("META-INF/SIGNATURE", envelope)
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
}
