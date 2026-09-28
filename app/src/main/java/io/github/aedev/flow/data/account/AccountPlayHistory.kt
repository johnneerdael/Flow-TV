package io.github.aedev.flow.data.account

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.safePreferencesDataStore
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
 * Sends the tracks listened to while signed in to the account's YouTube history, so YouTube Music's
 * recommendations learn from them. On by default; the account settings can switch it off.
 */
@Singleton
class AccountPlayHistory
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val client: AccountFeedClient,
        private val signedInPlayback: SignedInPlayback,
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
            videoId: String,
            playedMs: Long,
            durationMs: Long,
        ) {
            if (!countsAsPlay(playedMs, durationMs)) return
            scope.launch {
                if (!enabled.first()) return@launch
                client
                    .recordPlay(videoId, signedInPlayback.trackingFor(videoId))
                    .onSuccess { Log.d(TAG, "Play of $videoId added to the history") }
                    .onFailure { if (it !is AccountSignedOutException) Log.w(TAG, "Play of $videoId not added to the history", it) }
            }
        }

        private companion object {
            const val TAG = "AccountPlayHistory"
            val ENABLED = booleanPreferencesKey("enabled")
        }
    }
