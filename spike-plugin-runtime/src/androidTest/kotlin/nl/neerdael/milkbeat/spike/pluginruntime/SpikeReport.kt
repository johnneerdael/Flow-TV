package nl.neerdael.milkbeat.spike.pluginruntime

import android.os.Debug
import android.util.Log

private const val TAG = "PluginSpike"
private const val NANOS_PER_MILLI = 1_000_000.0

/** Results go to logcat under one tag, so a run is read back with `adb logcat -s PluginSpike`. */
internal object SpikeReport {
    fun line(text: String) {
        Log.i(TAG, text)
    }

    fun nativeHeapKb(): Long = Debug.getNativeHeapAllocatedSize() / 1024

    fun javaHeapKb(): Long = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1024 }
}

/** Runs [block] [warmup] times unmeasured, then [runs] times, and returns the durations in ms. */
internal inline fun measure(
    warmup: Int,
    runs: Int,
    block: () -> Unit,
): Timings {
    repeat(warmup) { block() }
    val samples =
        List(runs) {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / NANOS_PER_MILLI
        }
    return Timings(samples.sorted())
}

internal class Timings(
    private val sorted: List<Double>,
) {
    val median: Double get() = sorted[sorted.size / 2]
    val p90: Double get() = sorted[((sorted.size - 1) * 9) / 10]

    override fun toString() = "median %.1f ms, p90 %.1f ms".format(median, p90)
}
