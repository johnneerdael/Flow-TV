package io.github.aedev.flow.data.local

import android.app.ActivityManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.visualizerDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "visualizer")

// A box sold as 2 GB reports about 1.8-1.95 GB after the kernel's reservations.
private const val MIN_DEFAULT_ON_RAM_MB = 1_792L

/** Visualizations start on unless the device has less than 2 GB of memory; the user can switch them either way. */
internal fun visualizerOnByDefault(totalRamMb: Long): Boolean = totalRamMb >= MIN_DEFAULT_ON_RAM_MB

/** Settings of the now-playing visualizer. */
class VisualizerPreferences
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val appContext = context.applicationContext

        val enabledByDefault: Boolean by lazy {
            val memory = ActivityManager.MemoryInfo()
            appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            visualizerOnByDefault(memory.totalMem / (1024 * 1024))
        }

        val enabled: Flow<Boolean> = appContext.visualizerDataStore.data.map { it[ENABLED] ?: enabledByDefault }

        suspend fun setEnabled(enabled: Boolean) {
            appContext.visualizerDataStore.edit { it[ENABLED] = enabled }
        }

        private companion object {
            val ENABLED = booleanPreferencesKey("enabled")
        }
    }
