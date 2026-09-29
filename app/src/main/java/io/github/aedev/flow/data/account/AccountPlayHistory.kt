package io.github.aedev.flow.data.account

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.accountPlayHistoryDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "account_play_history")

private const val PLAY_THRESHOLD_MS = 30_000L

/**
 * Whether a listen counts as a play: 30 seconds, or half of a shorter track. Skipped tracks stay out
 * of the account's history, so they do not teach YouTube's recommendations the wrong thing.
 */
internal fun countsAsPlay(
    playedMs: Long,
    durationMs: Long,
): Boolean = playedMs > 0 && playedMs >= minOf(PLAY_THRESHOLD_MS, durationMs / 2)

/**
 * Reports listens to the plugin that played them, so a provider's history and recommendations learn
 * from them (YouTube Music adds them to the account's history). On by default; settings can switch it off.
 */
@Singleton
class AccountPlayHistory
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val pluginAudio: PluginAudio,
    ) {
        private val dataStore = context.applicationContext.accountPlayHistoryDataStore

        // Outlives the music service: a listen is often finalized from its onDestroy.
        private val scope = CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO)

        val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: true }

        suspend fun setEnabled(enabled: Boolean) {
            dataStore.edit { it[ENABLED] = enabled }
        }

        /** Called once per finished listen; reports it when it counts as a play and the setting is on. */
        fun onListened(
            track: MusicTrack,
            playedMs: Long,
            durationMs: Long,
        ) {
            if (!countsAsPlay(playedMs, durationMs)) return
            scope.launch {
                if (!enabled.first()) return@launch
                try {
                    pluginAudio.reportListen(MusicVideoItems.descriptor(track), playedMs, durationMs)
                    Log.d(TAG, "Play of ${track.videoId} reported")
                } catch (e: PluginCallException) {
                    Log.w(TAG, "Play of ${track.videoId} not reported: ${e.error.message}")
                }
            }
        }

        private companion object {
            const val TAG = "AccountPlayHistory"
            val ENABLED = booleanPreferencesKey("enabled")
        }
    }
