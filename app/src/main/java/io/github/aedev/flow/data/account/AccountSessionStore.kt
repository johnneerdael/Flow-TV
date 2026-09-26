package io.github.aedev.flow.data.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.aedev.flow.data.local.safePreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

const val ACCOUNT_SESSION_STORE_NAME = "account_session"

internal val Context.accountSessionDataStore by safePreferencesDataStore(name = ACCOUNT_SESSION_STORE_NAME)

class AccountSessionStore(
    private val dataStore: DataStore<Preferences>,
    private val sealer: SecretSealer,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val session: Flow<AccountSession?> =
        dataStore.data
            .map { prefs -> prefs[SESSION_KEY]?.let(::decode) }
            .distinctUntilChanged()

    suspend fun current(): AccountSession? = session.first()

    suspend fun save(session: AccountSession) {
        val sealed = sealer.seal(json.encodeToString(AccountSession.serializer(), session))
        dataStore.edit { it[SESSION_KEY] = sealed }
    }

    /** Flips only the session that failed; a sign-out or a newer sign-in in the meantime wins. */
    suspend fun markExpired(cookie: String) {
        dataStore.edit { prefs ->
            val stored = prefs[SESSION_KEY]?.let(::decode) ?: return@edit
            if (stored.cookie == cookie && !stored.expired) {
                prefs[SESSION_KEY] = sealer.seal(json.encodeToString(AccountSession.serializer(), stored.copy(expired = true)))
            }
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(SESSION_KEY) }
    }

    private fun decode(stored: String): AccountSession? =
        sealer
            .open(stored)
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { json.decodeFromString(AccountSession.serializer(), it) }.getOrNull() }

    private companion object {
        val SESSION_KEY = stringPreferencesKey("session")
    }
}
