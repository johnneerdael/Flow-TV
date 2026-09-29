/*
 * Copyright (C) 2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/A-EDev/Flow).
 *
 * Flow is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package io.github.aedev.flow.utils

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Performance Dispatcher - Centralized coroutine management for fast performance
 *
 * This module provides:
 * - Optimized thread pools for different workload types
 * - Parallel task execution with automatic error isolation
 * - Timeout protection for network operations
 * - Load balancing across available cores
 */
object PerformanceDispatcher {
    // Get available processors for optimal thread allocation
    private val availableProcessors = Runtime.getRuntime().availableProcessors()
    private const val NETWORK_THREAD_NICE = 5

    // Network I/O dispatcher - Optimized for high-concurrency network operations
    // Uses more threads than CPU cores since network ops are I/O bound, but keeps
    // TLS/socket allocation pressure bounded on 256 MB heap devices.
    private val networkExecutor =
        Executors.newFixedThreadPool(
            (availableProcessors * 2).coerceIn(4, 16),
        ) { runnable ->
            Thread({
                Process.setThreadPriority(NETWORK_THREAD_NICE)
                runnable.run()
            }, "FlowNetwork-${networkThreadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    private val networkThreadCounter = AtomicInteger(0)

    /**
     * Dispatcher optimized for network operations
     * Higher concurrency for I/O bound tasks
     */
    val networkIO: CoroutineDispatcher = networkExecutor.asCoroutineDispatcher()

    /**
     * Dispatcher for disk I/O operations (database, file)
     */
    val diskIO: CoroutineDispatcher = Dispatchers.IO

    /**
     * Main thread dispatcher for UI updates
     */
    val main: CoroutineDispatcher = Dispatchers.Main

    // Global supervisor scope for background tasks
    private val supervisorJob = SupervisorJob()

    /**
     * Execute a task with timeout protection
     */
    suspend fun <T> withTimeout(
        timeoutMs: Long,
        task: suspend () -> T,
    ): T? =
        withTimeoutOrNull(timeoutMs) {
            withContext(networkIO) { task() }
        }

    /**
     * Race multiple tasks and return the first successful result
     * Useful for fallback strategies
     */
    suspend fun <T> race(
        vararg tasks: suspend () -> T?,
        timeoutMs: Long = 10_000L,
    ): T? =
        supervisorScope {
            val deferreds =
                tasks.map { task ->
                    async(networkIO) {
                        withTimeoutOrNull(timeoutMs) {
                            try {
                                task()
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }

            // Wait for first non-null result
            for (deferred in deferreds) {
                val result = deferred.await()
                if (result != null) {
                    // Cancel remaining tasks
                    deferreds.forEach { it.cancel() }
                    return@supervisorScope result
                }
            }
            null
        }

    /**
     * Cleanup resources when app is destroyed
     */
    fun shutdown() {
        supervisorJob.cancel()
        networkExecutor.shutdown()
    }
}
