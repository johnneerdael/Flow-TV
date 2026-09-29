package io.github.aedev.flow.plugin.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Creates and closes every QuickJS instance in the process. quickjs-kt 1.0.15 keeps a process-wide
 * cache of JNI references and clears it when its last instance closes; an instance created on
 * another thread meanwhile then uses deleted references (the JNI checker aborts on it, a release
 * build would corrupt memory). One anchor instance stays open for the process's life, so the cache
 * is never cleared, and creation and closing never overlap.
 */
internal object QuickJsInstances {
    private val lock = Mutex()
    private var anchor: QuickJs? = null

    suspend fun create(dispatcher: CoroutineDispatcher): QuickJs =
        lock.withLock {
            if (anchor == null) anchor = QuickJs.create(Dispatchers.Default)
            QuickJs.create(dispatcher)
        }

    suspend fun close(js: QuickJs) = lock.withLock { js.close() }
}
