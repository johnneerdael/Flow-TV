package nl.neerdael.milkbeat.spike.pluginruntime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import java.util.concurrent.Executors

private const val WARMUP = 3
private const val RUNS = 10
private const val MB = 1024L * 1024L

// Plugins run on their own thread with a deep stack: the solver's parser recurses through a 3 MB
// script, and a default thread stack is not guaranteed to hold that.
private const val PLUGIN_THREAD_STACK = 16 * MB

private val FIXTURES =
    listOf(
        "youtube_music_home.json",
        "youtube_music_home_continuation.json",
        "youtube_music_artist.json",
        "youtube_music_album.json",
        "youtube_music_playlist.json",
    )

private const val SIG_CHALLENGE =
    "AOq0QJ8wRQIgWJbUgnE4Wt04xXsC5ceshDuUzqsI4ahBoIOHrKs2RzICIQCy7CKn8u9HqzNqtFWP7GHHsfUmr4Ut3eVZEqWbGt7rJw==AOq0QJ8wRQ"

// The shape jsc() answers in, built from solvers kept alive in the context.
private const val SOLVE_WITH_CACHED_SOLVERS =
    "JSON.stringify([" +
        "{type:'result', data:{'ZdZIqFPQK-Ty8wId': solvers.n('ZdZIqFPQK-Ty8wId'), 'dmAa3SiX2TeDqpxK': solvers.n('dmAa3SiX2TeDqpxK')}}," +
        "{type:'result', data:{'$SIG_CHALLENGE': solvers.sig('$SIG_CHALLENGE')}}])"

@RunWith(AndroidJUnit4::class)
class PluginRuntimeSpikeTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun asset(name: String): String = assets.open(name).use { it.readBytes().decodeToString() }

    private fun hasAsset(name: String): Boolean = runCatching { assets.open(name).close() }.isSuccess

    private fun inputs(): Map<String, String> {
        val fixtures = FIXTURES.associateWith(::asset)
        val home = fixtures.getValue("youtube_music_home.json")
        return fixtures + ("home_x10" to List(10) { home }.joinToString(",", "[", "]"))
    }

    @Test
    fun mapsPagesInEveryEngine() =
        runBlocking {
            val mapper = asset("mapper.js")
            for ((name, json) in inputs()) {
                val expected = KotlinPageMapper.map(json)
                val kotlin = measure(WARMUP, RUNS) { KotlinPageMapper.map(json) }

                var quickJsOutput = ""
                val quickJs =
                    onPluginThread {
                        withQuickJs { js ->
                            js.defineBinding("hostInput", FunctionBinding { json })
                            js.evaluate<Any?>(mapper, "mapper.js", false)
                            measureSuspending { quickJsOutput = js.evaluate<String>("mapPage(hostInput())", "call.js", false) }
                        }
                    }

                var rhinoOutput = ""
                val rhino =
                    onPluginThread {
                        withRhino { cx, scope ->
                            cx.evaluateString(scope, mapper, "mapper.js", 1, null)
                            val mapPage = scope.get("mapPage", scope) as Function
                            measure(WARMUP, RUNS) { rhinoOutput = Context.toString(mapPage.call(cx, scope, scope, arrayOf(json))) }
                        }
                    }

                assertEquals("QuickJS output for $name", expected, PageSummary.ofJson(quickJsOutput))
                assertEquals("Rhino output for $name", expected, PageSummary.ofJson(rhinoOutput))
                SpikeReport.line(
                    "map $name (${json.length / 1024} KB, ${expected.shelves} shelves, ${expected.items} items): " +
                        "kotlin $kotlin | quickjs $quickJs | rhino $rhino",
                )
            }
        }

    @Test
    fun solvesYouTubeChallengesWithQuickJs() =
        runBlocking {
            assumeTrue("run spike-plugin-runtime/prepare.sh first", hasAsset("base.js") && hasAsset("expected.json"))
            val player = asset("base.js")
            val expected = Json.parseToJsonElement(asset("expected.json"))
            // Spike only: expose the solver's stages, which a real plugin would keep between calls.
            val core =
                asset("yt.solver.core.js").replace(
                    "  return main;\n})(meriyah, astring);",
                    "  main.preprocessPlayer = preprocessPlayer; main.getFromPrepared = getFromPrepared;\n  return main;\n})(meriyah, astring);",
                )
            check(core.contains("main.getFromPrepared")) { "solver layout changed" }
            val source = "${asset("yt.solver.lib.js")}\nvar meriyah = lib.meriyah, astring = lib.astring;\n$core"
            var preprocessed = ""
            var cachedInit = ByteArray(0)

            onPluginThread {
                withQuickJs { js ->
                    js.memoryLimit = 256 * MB
                    js.maxStackSize = 8 * MB
                    js.defineBinding("hostPlayer", FunctionBinding { player })

                    var start = System.nanoTime()
                    val bytecode = js.compile(source, "solver.js", false)
                    val compileMs = sinceMs(start)
                    start = System.nanoTime()
                    js.evaluate<Any?>(bytecode)
                    val loadMs = sinceMs(start)

                    start = System.nanoTime()
                    preprocessed = js.evaluate<String>("var pre = jsc.preprocessPlayer(hostPlayer()); pre", "preprocess.js", false)
                    val preprocessMs = sinceMs(start)

                    start = System.nanoTime()
                    js.evaluate<Any?>("var solvers = jsc.getFromPrepared(pre); 0", "prepare.js", false)
                    val prepareMs = sinceMs(start)

                    val solve =
                        measureSuspending {
                            val result = js.evaluate<String>(SOLVE_WITH_CACHED_SOLVERS, "solve.js", false)
                            assertEquals(expected, Json.parseToJsonElement(result))
                        }

                    start = System.nanoTime()
                    cachedInit = js.compile("var initSolvers = function (_result) {\n$preprocessed\n};", "prepared.js", false)
                    val compilePreparedMs = sinceMs(start)
                    val memory = js.memoryUsage
                    SpikeReport.line(
                        "solver quickjs: compile solver ${compileMs.format()} ms (${bytecode.size / 1024} KB), " +
                            "load ${loadMs.format()} ms | " +
                            "preprocess player ${preprocessMs.format()} ms " +
                            "(${player.length / 1024} KB -> ${preprocessed.length / 1024} KB) | " +
                            "prepare ${prepareMs.format()} ms | solve 2n+1sig with prepared solvers $solve | " +
                            "compile prepared to bytecode ${compilePreparedMs.format()} ms (${cachedInit.size / 1024} KB) | " +
                            "engine memory ${memory.memoryUsedSize / MB} MB, malloc ${memory.mallocSize / MB} MB",
                    )
                }
                // The next app start: the prepared player comes back as cached bytecode, no parsing at all.
                withQuickJs { js ->
                    js.memoryLimit = 256 * MB
                    js.maxStackSize = 8 * MB
                    var start = System.nanoTime()
                    js.evaluate<Any?>(cachedInit)
                    js.evaluate<Any?>("var solvers = { n: null, sig: null }; initSolvers(solvers); 0", "init.js", false)
                    val warmStartMs = sinceMs(start)
                    start = System.nanoTime()
                    val result = js.evaluate<String>(SOLVE_WITH_CACHED_SOLVERS, "solve.js", false)
                    val firstSolveMs = sinceMs(start)
                    assertEquals(expected, Json.parseToJsonElement(result))
                    SpikeReport.line(
                        "solver quickjs next start: load cached bytecode + init ${warmStartMs.format()} ms, first solve ${firstSolveMs.format()} ms",
                    )
                }
            }
        }

    @Test
    fun solverDoesNotParseInRhino() =
        runBlocking {
            assumeTrue(hasAsset("yt.solver.lib.js"))
            val library = asset("yt.solver.lib.js")
            val failure =
                onPluginThread {
                    withRhino { cx, scope ->
                        runCatching { cx.evaluateString(scope, library, "yt.solver.lib.js", 1, null) }.exceptionOrNull()
                    }
                }
            SpikeReport.line("solver rhino: ${(failure as? RhinoException)?.details() ?: failure?.message ?: "loaded"}")
        }

    @Test
    fun enforcesLimitsAndAwaitsHostCalls() =
        runBlocking {
            onPluginThread {
                withQuickJs { js ->
                    js.memoryLimit = 64 * MB
                    val nativeBefore = SpikeReport.nativeHeapKb()
                    val oom =
                        runCatching {
                            js.evaluate<Any?>("var a = []; while (true) a.push(new Array(100000).fill(1));", "oom.js", false)
                        }.exceptionOrNull()
                    js.gc()
                    SpikeReport.line(
                        "limit memory 64 MB: ${oom?.javaClass?.simpleName}: ${oom?.message?.take(80)}; " +
                            "native heap delta ${SpikeReport.nativeHeapKb() - nativeBefore} KB",
                    )
                    assertTrue("runaway allocation must fail, not crash", oom is QuickJsException)
                    assertEquals(2L, (js.evaluate<Any?>("1 + 1", "after-oom.js", false) as Number).toLong())
                }
                withQuickJs { js ->
                    js.evaluationTimeoutMillis = 500
                    val start = System.nanoTime()
                    val timeout = runCatching { js.evaluate<Any?>("while (true) {}", "loop.js", false) }.exceptionOrNull()
                    SpikeReport.line("limit timeout 500 ms: stopped after ${sinceMs(start).format()} ms with ${timeout?.message?.take(80)}")
                    assertTrue(timeout is QuickJsException)
                }
                withQuickJs { js ->
                    js.defineBinding(
                        "hostFetch",
                        AsyncFunctionBinding { args ->
                            delay(50)
                            "reply:${args[0]}"
                        },
                    )
                    val start = System.nanoTime()
                    val reply =
                        js.evaluate<String>(
                            "const [a, b] = await Promise.all([hostFetch('a'), hostFetch('b')]); a + ',' + b",
                            "async.js",
                            false,
                        )
                    SpikeReport.line("async host calls: '$reply' in ${sinceMs(start).format()} ms (two 50 ms calls in parallel)")
                    assertEquals("reply:a,reply:b", reply)
                }
            }
        }

    @Test
    fun startsAndStopsWithoutLeaking() =
        runBlocking {
            val mapper = asset("mapper.js")
            val home = asset("youtube_music_home.json")
            onPluginThread {
                repeat(5) { withQuickJs { it.evaluate<Any?>(mapper, "mapper.js", false) } }
                System.gc()
                val nativeBefore = SpikeReport.nativeHeapKb()
                val starts =
                    measureSuspending(warmup = 0, runs = 50) {
                        withQuickJs { js ->
                            js.defineBinding("hostInput", FunctionBinding { home })
                            js.evaluate<Any?>(mapper, "mapper.js", false)
                            js.evaluate<String>("mapPage(hostInput())", "call.js", false)
                        }
                    }
                System.gc()
                SpikeReport.line(
                    "lifecycle 50x create+load+map+close: $starts; native heap delta " +
                        "${SpikeReport.nativeHeapKb() - nativeBefore} KB, java heap ${SpikeReport.javaHeapKb() / 1024} MB",
                )
            }
        }

    private suspend fun <T> withQuickJs(block: suspend (QuickJs) -> T): T {
        val js = QuickJs.create(pluginThread)
        try {
            return block(js)
        } finally {
            js.close()
        }
    }

    // Android cannot load bytecode Rhino generates, so it only ever runs interpreted.
    private fun <T> withRhino(block: (Context, Scriptable) -> T): T {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            cx.isInterpretedMode = true
            cx.setClassShutter { false }
            return block(cx, cx.initSafeStandardObjects())
        } finally {
            Context.exit()
        }
    }

    private suspend fun <T> onPluginThread(block: suspend () -> T): T = withContext(pluginThread) { block() }

    private suspend inline fun measureSuspending(
        warmup: Int = WARMUP,
        runs: Int = RUNS,
        block: () -> Unit,
    ): Timings = measure(warmup, runs, block)

    private fun sinceMs(start: Long): Double = (System.nanoTime() - start) / 1_000_000.0

    private fun Double.format() = "%.0f".format(this)

    companion object {
        private val pluginThread: ExecutorCoroutineDispatcher =
            Executors
                .newSingleThreadExecutor { runnable -> Thread(null, runnable, "plugin", PLUGIN_THREAD_STACK) }
                .asCoroutineDispatcher()

        @JvmStatic
        @AfterClass
        fun closeThread() {
            pluginThread.close()
        }
    }
}
