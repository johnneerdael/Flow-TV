package io.github.aedev.flow.plugin.install

import io.github.aedev.flow.plugin.pkg.PluginPackage
import io.github.aedev.flow.plugin.pkg.PluginPackageException
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024

/** A verified plugin waiting for the listener's consent, with what installing it would change. */
class PendingInstall(
    val pack: PluginPackage,
    val sourceUrl: String,
    val installed: InstalledPlugin?,
) {
    val isUpdate: Boolean get() = installed != null

    /** Hosts the plugin wants that the listener has not granted yet: what the consent screen asks about. */
    val newNetwork: List<String> get() = pack.manifest.permissions.network - installed?.grantedNetwork.orEmpty().toSet()
    val newBrowser: List<String> get() = pack.manifest.permissions.browser - installed?.grantedBrowser.orEmpty().toSet()
}

class PluginInstallException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Fetches and verifies plugins, then installs them once the listener agrees. An update must come from
 * the same author key and carry a higher version; it installs without asking unless it wants more.
 */
@Singleton
class PluginInstaller
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val registry: PluginRegistry,
    ) {
        suspend fun fetch(url: String): PendingInstall {
            val bytes = download(url)
            val pack =
                try {
                    PluginPackageReader.read(bytes.inputStream())
                } catch (e: PluginPackageException) {
                    throw PluginInstallException(e.message ?: "Not a valid plugin", e)
                }
            return check(pack, url)
        }

        fun check(
            pack: PluginPackage,
            url: String,
        ): PendingInstall {
            val installed =
                registry.state.value.plugins
                    .firstOrNull { it.id == pack.manifest.id }
            if (installed != null) {
                if (installed.signerFingerprint != pack.signerFingerprint) {
                    throw PluginInstallException("${pack.manifest.name} is signed by a different author than the installed one")
                }
                if (pack.manifest.versionCode < installed.manifest.versionCode) {
                    throw PluginInstallException("${pack.manifest.name} ${pack.manifest.version} is older than the installed version")
                }
            }
            return PendingInstall(pack, url, installed)
        }

        /** Installs with the permissions the manifest asks for; call only after the listener agreed to [PendingInstall.needsConsent]. */
        suspend fun install(pending: PendingInstall): InstalledPlugin =
            registry.install(
                pack = pending.pack,
                sourceUrl = pending.sourceUrl,
                grantedNetwork = pending.pack.manifest.permissions.network,
                grantedBrowser = pending.pack.manifest.permissions.browser,
            )

        private suspend fun download(url: String): ByteArray =
            withContext(Dispatchers.IO) {
                val request =
                    try {
                        Request
                            .Builder()
                            .url(url)
                            .cacheControl(CacheControl.FORCE_NETWORK)
                            .build()
                    } catch (e: IllegalArgumentException) {
                        throw PluginInstallException("$url is not a web address", e)
                    }
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw PluginInstallException("Download failed: HTTP ${response.code}")
                        val length = response.body.contentLength()
                        if (length > MAX_DOWNLOAD_BYTES) throw PluginInstallException("The plugin is too large")
                        val source = response.body.source()
                        if (!source.request(MAX_DOWNLOAD_BYTES + 1)) {
                            source.buffer.readByteArray()
                        } else {
                            throw PluginInstallException("The plugin is too large")
                        }
                    }
                } catch (e: IOException) {
                    throw PluginInstallException("Download failed: ${e.message}", e)
                }
            }
    }
