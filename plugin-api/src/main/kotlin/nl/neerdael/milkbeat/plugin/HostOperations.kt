package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * An HTTPS request through the host; only hosts in the manifest's `permissions.network` are reachable,
 * redirects included. There is no cookie jar: a plugin sends and keeps its own cookies (from
 * [HttpResponse.headers] `set-cookie`, or a [WebLoginResult]) itself.
 */
@Serializable
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMs: Long? = null,
    /** Answer with the redirect itself instead of following it, e.g. to read a `Location` header. */
    val followRedirects: Boolean = true,
)

@Serializable
data class HttpResponse(
    val status: Int,
    val url: String,
    /** Header names in lower case; repeated headers are joined with `, `, except `set-cookie` with `\n`. */
    val headers: Map<String, String>,
    val body: String,
)

@Serializable
data class StorageKey(
    val key: String,
)

@Serializable
data class StorageEntry(
    val key: String,
    val value: String,
)

@Serializable
data class StoredValue(
    val value: String? = null,
)

@Serializable
enum class HashAlgorithm {
    SHA1,
    SHA256,
}

@Serializable
data class HashRequest(
    val algorithm: HashAlgorithm,
    val text: String,
)

@Serializable
data class HashResult(
    val hex: String,
)

/**
 * Evaluates [source] once in the plugin's context and keeps its compiled bytecode under [key], so the
 * next start loads it instead of parsing it again. A new plugin version or a new [key] recompiles.
 */
@Serializable
data class CodeLoadRequest(
    val key: String,
    val source: String,
)

@Serializable
data class AssetRequest(
    val path: String,
)

@Serializable
data class AssetText(
    val text: String,
)

/** What the plugin may know about where it runs; there are no device identifiers. */
@Serializable
data class HostEnvironment(
    val apiVersion: Int,
    val appVersion: String,
    val locale: String,
    val region: String,
    val deviceClass: String,
    val pluginVersion: String,
)

@Serializable
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

@Serializable
data class LogRequest(
    val level: LogLevel,
    val message: String,
)

/**
 * A hidden web view for pages only a browser can run, such as a provider's attestation script. It
 * loads [html] from the plugin's assets with [baseUrl] as its origin, or navigates to [url].
 */
@Serializable
data class BrowserOpenRequest(
    val url: String? = null,
    val html: String? = null,
    val baseUrl: String? = null,
    val timeoutMs: Long? = null,
)

@Serializable
data class BrowserSession(
    val id: String,
)

/** Runs [script] (an async function body) in the session and returns what it resolves to, as text. */
@Serializable
data class BrowserEvaluateRequest(
    val session: String,
    val script: String,
    val timeoutMs: Long? = null,
)

@Serializable
data class BrowserResult(
    val value: String,
)

/** Waits [ms] (at most a minute); QuickJS has no timers of its own, so backoff goes through the host. */
@Serializable
data class SleepRequest(
    val ms: Long,
)

/** A host function a plugin calls through the global `mb` object: `area.name`, with its JSON shapes. */
class HostOperation<Request, Response>(
    val path: String,
    val request: KSerializer<Request>,
    val response: KSerializer<Response>,
)

/** Every host function of plugin API v1; each is gated by the plugin's granted permissions. */
object HostOperations {
    val fetch = HostOperation("http.fetch", HttpRequest.serializer(), HttpResponse.serializer())
    val storageGet = HostOperation("storage.get", StorageKey.serializer(), StoredValue.serializer())
    val storageSet = HostOperation("storage.set", StorageEntry.serializer(), Unit.serializer())
    val storageDelete = HostOperation("storage.delete", StorageKey.serializer(), Unit.serializer())
    val secretGet = HostOperation("secrets.get", StorageKey.serializer(), StoredValue.serializer())
    val secretSet = HostOperation("secrets.set", StorageEntry.serializer(), Unit.serializer())
    val secretDelete = HostOperation("secrets.delete", StorageKey.serializer(), Unit.serializer())
    val hash = HostOperation("crypto.hash", HashRequest.serializer(), HashResult.serializer())
    val codeLoad = HostOperation("code.load", CodeLoadRequest.serializer(), Unit.serializer())
    val assetRead = HostOperation("assets.read", AssetRequest.serializer(), AssetText.serializer())
    val environment = HostOperation("env.get", Unit.serializer(), HostEnvironment.serializer())
    val log = HostOperation("log.write", LogRequest.serializer(), Unit.serializer())
    val browserOpen = HostOperation("browser.open", BrowserOpenRequest.serializer(), BrowserSession.serializer())
    val browserEvaluate = HostOperation("browser.evaluate", BrowserEvaluateRequest.serializer(), BrowserResult.serializer())
    val browserClose = HostOperation("browser.close", BrowserSession.serializer(), Unit.serializer())
    val settings = HostOperation("settings.get", Unit.serializer(), ListSerializer(StorageEntry.serializer()))
    val sleep = HostOperation("time.sleep", SleepRequest.serializer(), Unit.serializer())

    val all: List<HostOperation<*, *>> =
        listOf(
            fetch,
            storageGet,
            storageSet,
            storageDelete,
            secretGet,
            secretSet,
            secretDelete,
            hash,
            codeLoad,
            assetRead,
            environment,
            log,
            browserOpen,
            browserEvaluate,
            browserClose,
            settings,
            sleep,
        )
}
