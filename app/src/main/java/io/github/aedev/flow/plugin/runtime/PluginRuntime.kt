package io.github.aedev.flow.plugin.runtime

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import io.github.aedev.flow.plugin.host.CallEnvelope
import io.github.aedev.flow.plugin.host.PluginBrowser
import io.github.aedev.flow.plugin.host.PluginHostApi
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import nl.neerdael.milkbeat.plugin.CodeLoadRequest
import nl.neerdael.milkbeat.plugin.HostOperations
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginOperation
import nl.neerdael.milkbeat.plugin.PluginOperations
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "PluginRuntime"
private const val MB = 1024L * 1024L

// The spike measured these on the AM6 (docs/research/plugin-runtime-spike-2026-09-29.md).
private const val STACK_BYTES = 16 * MB
private const val JS_STACK_BYTES = 8 * MB
private const val MEMORY_LIMIT = 96 * MB
private const val WARM_UP_MEMORY_LIMIT = 256 * MB
private const val CALL_TIMEOUT_MS = 20_000L
private const val WARM_UP_TIMEOUT_MS = 120_000L
private const val IDLE_MS = 5 * 60_000L
private const val MAX_IN_FLIGHT = 4
private const val MAX_CONSECUTIVE_FAILURES = 5

/** A plugin call that failed; [error] says how, as the plugin or the host reported it. */
class PluginCallException(
    val pluginId: String,
    val error: PluginError,
) : Exception("$pluginId: ${error.code} ${error.message}")

/**
 * Runs one plugin: its QuickJS context on a thread of its own, created on the first call and closed
 * after five idle minutes unless something holds it warm. Calls go in through the SDK's dispatcher,
 * at most four at a time, each with a time limit; five internal failures in a row disable the plugin
 * until the app restarts. Warm-up runs in a second, short-lived context and thread, so its long
 * synchronous work never holds up page loads.
 */
internal class PluginRuntime(
    val plugin: InstalledPlugin,
    private val directory: File,
    private val hostApi: PluginHostApi,
    private val browser: PluginBrowser,
    private val codeCache: CodeCache,
    private val scope: CoroutineScope,
) {
    private val shortName = plugin.id.substringAfterLast('.')
    private val contextLock = Mutex()
    private val inFlight = Semaphore(MAX_IN_FLIGHT)
    private val requests = ConcurrentHashMap<Long, Pair<String, String>>()
    private val nextRequest = AtomicLong()
    private val failures = AtomicInteger()
    private val holds = AtomicInteger()
    private val running = AtomicInteger()

    @Volatile
    private var disabled = false
    private var context: PluginContext? = null
    private var idleJob: Job? = null

    suspend fun <Request, Response> call(
        operation: PluginOperation<Request, Response>,
        request: Request,
    ): Response {
        if (disabled) throw PluginCallException(plugin.id, PluginError(PluginErrorCode.UNAVAILABLE, "Disabled after repeated failures"))
        return inFlight.withPermit {
            running.incrementAndGet()
            try {
                val js = contextLock.withLock { (context ?: start(MEMORY_LIMIT, CALL_TIMEOUT_MS).also { context = it }).js }
                invoke(js, operation, request, CALL_TIMEOUT_MS)
            } finally {
                running.decrementAndGet()
                scheduleIdleStop()
            }
        }
    }

    /** Runs the plugin's warm-up, if it has one, in a context of its own that is gone afterwards. */
    suspend fun warmUp() {
        if (disabled) return
        val warm = start(WARM_UP_MEMORY_LIMIT, WARM_UP_TIMEOUT_MS)
        try {
            invoke(warm.js, PluginOperations.warmUp, Unit, WARM_UP_TIMEOUT_MS)
        } catch (e: PluginCallException) {
            if (e.error.code != PluginErrorCode.UNSUPPORTED) Log.w(TAG, "Warm-up of ${plugin.id} failed: ${e.error.message}")
        } finally {
            warm.close()
        }
    }

    /** Keeps the context alive, e.g. while its audio plays; pair every call with [release]. */
    fun hold() {
        holds.incrementAndGet()
    }

    fun release() {
        holds.decrementAndGet()
        scheduleIdleStop()
    }

    suspend fun close() {
        idleJob?.cancel()
        contextLock.withLock {
            context?.close()
            context = null
        }
        browser.closeAll()
    }

    private suspend fun <Request, Response> invoke(
        js: QuickJs,
        operation: PluginOperation<Request, Response>,
        request: Request,
        timeoutMs: Long,
    ): Response {
        val id = nextRequest.incrementAndGet()
        requests[id] = operation.path to PluginJson.encodeToString(operation.request, request)
        val envelope =
            try {
                val text =
                    withTimeout(timeoutMs) {
                        js.evaluate<Any?>("await __mbDispatch(__mbRequest($id, 0), __mbRequest($id, 1))", "call.js", false) as String
                    }
                PluginJson.decodeFromString(CallEnvelope.serializer(), text)
            } catch (e: TimeoutCancellationException) {
                CallEnvelope(error = PluginError(PluginErrorCode.TIMEOUT, "${operation.path} took longer than $timeoutMs ms"))
            } catch (e: QuickJsException) {
                // QuickJS's own time limit ends a runaway script before the coroutine's does.
                val code = if (e.message.orEmpty().contains("interrupted")) PluginErrorCode.TIMEOUT else PluginErrorCode.INTERNAL
                CallEnvelope(error = PluginError(code, e.message ?: "Script error", detail = e.stack))
            } catch (e: SerializationException) {
                CallEnvelope(error = PluginError(PluginErrorCode.INTERNAL, "Unreadable answer: ${e.message}"))
            } finally {
                requests.remove(id)
            }
        // QuickJS interrupts a script at its time limit with an exception the plugin's dispatcher catches.
        envelope.error?.let { reported ->
            val error =
                if (reported.code == PluginErrorCode.INTERNAL && reported.message == "interrupted") {
                    reported.copy(code = PluginErrorCode.TIMEOUT, message = "${operation.path} took longer than $timeoutMs ms")
                } else {
                    reported
                }
            if (error.code == PluginErrorCode.INTERNAL && failures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES) {
                disabled = true
                Log.e(TAG, "${plugin.id} disabled after $MAX_CONSECUTIVE_FAILURES failures in a row; last: ${error.message}")
            }
            throw PluginCallException(plugin.id, error)
        }
        failures.set(0)
        return try {
            PluginJson.decodeFromJsonElement(operation.response, envelope.result ?: JsonObject(emptyMap()))
        } catch (e: SerializationException) {
            throw PluginCallException(plugin.id, PluginError(PluginErrorCode.INTERNAL, "${operation.path} answered wrongly: ${e.message}"))
        }
    }

    private suspend fun start(
        memoryLimit: Long,
        timeoutMs: Long,
    ): PluginContext {
        val thread =
            Executors
                .newSingleThreadExecutor { runnable -> Thread(null, runnable, "plugin-$shortName", STACK_BYTES) }
                .asCoroutineDispatcher()
        return try {
            withContext(thread) {
                val js = QuickJsInstances.create(thread)
                js.memoryLimit = memoryLimit
                js.maxStackSize = JS_STACK_BYTES
                js.evaluationTimeoutMillis = timeoutMs
                js.defineBinding(
                    "__mbRequest",
                    FunctionBinding { args ->
                        val (path, json) = requests[(args[0] as Number).toLong()] ?: error("Unknown request")
                        if ((args[1] as Number).toInt() == 0) path else json
                    },
                )
                js.defineBinding(
                    "__mbHost",
                    AsyncFunctionBinding { args -> host(js, args[0] as String, args[1] as String) },
                )
                val entry = plugin.manifest.entry
                js.evaluate<Any?>(bytecode(js, "entry:${plugin.manifest.versionCode}", File(directory, entry).readText(), entry))
                PluginContext(js, thread)
            }
        } catch (e: CancellationException) {
            thread.close()
            throw e
        } catch (e: Exception) {
            thread.close()
            throw PluginCallException(plugin.id, PluginError(PluginErrorCode.INTERNAL, "Could not start: ${e.message}"))
        }
    }

    private suspend fun host(
        js: QuickJs,
        path: String,
        requestJson: String,
    ): String =
        when (path) {
            HostOperations.codeLoad.path -> {
                hostApi.envelope {
                    val request = PluginJson.decodeFromString(CodeLoadRequest.serializer(), requestJson)
                    js.evaluate<Any?>(bytecode(js, request.key, request.source, "code-${request.key}.js"))
                    PluginHostApi.success(Unit.serializer(), Unit)
                }
            }

            HostOperations.browserOpen.path -> {
                hostApi.envelope {
                    PluginHostApi.success(
                        HostOperations.browserOpen.response,
                        browser.open(PluginJson.decodeFromString(HostOperations.browserOpen.request, requestJson)),
                    )
                }
            }

            HostOperations.browserEvaluate.path -> {
                hostApi.envelope {
                    PluginHostApi.success(
                        HostOperations.browserEvaluate.response,
                        browser.evaluate(PluginJson.decodeFromString(HostOperations.browserEvaluate.request, requestJson)),
                    )
                }
            }

            HostOperations.browserClose.path -> {
                hostApi.envelope {
                    browser.close(PluginJson.decodeFromString(HostOperations.browserClose.request, requestJson))
                    PluginHostApi.success(HostOperations.browserClose.response, Unit)
                }
            }

            else -> {
                hostApi.call(path, requestJson)
            }
        }

    private fun bytecode(
        js: QuickJs,
        key: String,
        source: String,
        name: String,
    ): ByteArray = codeCache.get(key, source) ?: js.compile(source, name, false).also { codeCache.put(key, source, it) }

    private fun scheduleIdleStop() {
        idleJob?.cancel()
        idleJob =
            scope.launch {
                delay(IDLE_MS)
                contextLock.withLock {
                    if (holds.get() <= 0 && running.get() == 0) {
                        context?.close()
                        context = null
                    }
                }
            }
    }

    private class PluginContext(
        val js: QuickJs,
        private val thread: ExecutorCoroutineDispatcher,
    ) {
        suspend fun close() {
            withContext(thread) { QuickJsInstances.close(js) }
            thread.close()
        }
    }
}
