package io.github.aedev.flow.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.aedev.flow.data.local.AppDatabase
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.subscriptionsDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "subscriptions")

class SubscriptionRepository private constructor(
    private val context: Context,
) {
    companion object {
        @Volatile
        private var instance: SubscriptionRepository? = null

        fun getInstance(context: Context): SubscriptionRepository =
            instance ?: synchronized(this) {
                instance ?: SubscriptionRepository(context.applicationContext).also { instance = it }
            }

        // Keys format: "channel_{channelId}" -> JSON string with channel info
        private fun channelKey(channelId: String) = stringPreferencesKey("channel_$channelId")

        /** "unsub_{channelId}" -> epoch ms the user unsubscribed. See [unsubscribedTombstones]. */
        private fun unsubscribedKey(channelId: String) = stringPreferencesKey("unsub_$channelId")

        private const val UNSUBSCRIBED_PREFIX = "unsub_"

        private const val SUBSCRIPTIONS_ORDER_KEY = "subscriptions_order"

        /**
         * How long an unsubscribe is remembered. Device sync resolves subscribe-vs-unsubscribe by
         * comparing their timestamps, so forgetting a tombstone lets a peer that still holds the
         * subscription resurrect it. Long enough that any realistic sync gap is covered, bounded so
         * the store cannot grow without limit.
         */
        private const val UNSUBSCRIBE_TOMBSTONE_RETENTION_MS = 365L * 24 * 60 * 60 * 1000
    }

    /**
     * Subscribe to a channel
     */
    suspend fun subscribe(channel: ChannelSubscription) {
        context.subscriptionsDataStore.edit { preferences ->
            val safeChannel = channel.withPreservedThumbnail(preferences)

            // Save channel data
            preferences[channelKey(safeChannel.channelId)] = serializeChannel(safeChannel)
            preferences.remove(unsubscribedKey(safeChannel.channelId)) // re-subscribing clears the tombstone

            // Update order list
            val currentOrder = preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] ?: ""
            val orderList =
                if (currentOrder.isEmpty()) {
                    mutableListOf()
                } else {
                    currentOrder.split(",").toMutableList()
                }

            if (!orderList.contains(safeChannel.channelId)) {
                orderList.add(0, safeChannel.channelId)
                preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] = orderList.joinToString(",")
            }
        }
    }

    suspend fun subscribeAll(channels: Collection<ChannelSubscription>) {
        if (channels.isEmpty()) return

        context.subscriptionsDataStore.edit { preferences ->
            val currentOrder =
                preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)]
                    .orEmpty()
                    .split(",")
                    .filter { it.isNotEmpty() }
            val knownIds = currentOrder.toMutableSet()
            val newIds = mutableListOf<String>()

            channels.forEach { channel ->
                val safeChannel = channel.withPreservedThumbnail(preferences)
                preferences[channelKey(safeChannel.channelId)] = serializeChannel(safeChannel)
                preferences.remove(unsubscribedKey(safeChannel.channelId))
                if (knownIds.add(safeChannel.channelId)) {
                    newIds += safeChannel.channelId
                }
            }

            if (newIds.isNotEmpty()) {
                preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] =
                    (newIds.asReversed() + currentOrder).joinToString(",")
            }
        }
    }

    /**
     * Unsubscribe from a channel
     */
    suspend fun unsubscribe(channelId: String) {
        val now = System.currentTimeMillis()
        context.subscriptionsDataStore.edit { preferences ->
            preferences.remove(channelKey(channelId))
            // Remember *when*, so device sync can tell an unsubscribe from "this device never knew
            // about the channel" and the removal actually reaches the other device.
            preferences[unsubscribedKey(channelId)] = now.toString()
            prune(preferences, now)

            // Update order list
            val currentOrder = preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] ?: ""
            if (currentOrder.isNotEmpty()) {
                val orderList = currentOrder.split(",").toMutableList()
                orderList.remove(channelId)
                preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] = orderList.joinToString(",")
            }
        }

        AppDatabase
            .getDatabase(context)
            .cacheDao()
            .deleteSubscriptionFeedForChannel(channelId)
    }

    /**
     * Check if subscribed to a channel
     */
    fun isSubscribed(channelId: String): Flow<Boolean> =
        context.subscriptionsDataStore.data.map { preferences ->
            preferences.contains(channelKey(channelId))
        }

    /**
     * Get all subscriptions
     */
    fun getAllSubscriptions(): Flow<List<ChannelSubscription>> =
        context.subscriptionsDataStore.data.map { preferences ->
            val orderString = preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] ?: ""
            if (orderString.isEmpty()) {
                emptyList()
            } else {
                val orderList = orderString.split(",")
                orderList.mapNotNull { channelId ->
                    val channelData = preferences[channelKey(channelId)]
                    channelData?.let { deserializeChannel(it) }
                }
            }
        }

    /** Drop tombstones past the retention window so the store cannot grow without limit. */
    private fun prune(
        preferences: MutablePreferences,
        now: Long,
    ) {
        val cutoff = now - UNSUBSCRIBE_TOMBSTONE_RETENTION_MS
        preferences
            .asMap()
            .keys
            .filter { it.name.startsWith(UNSUBSCRIBED_PREFIX) }
            .forEach { key ->
                val at = (preferences[key] as? String)?.toLongOrNull()
                if (at == null || at < cutoff) preferences.remove(key)
            }
    }

    /**
     * Get subscription by channel ID
     */
    fun getSubscription(channelId: String): Flow<ChannelSubscription?> =
        context.subscriptionsDataStore.data.map { preferences ->
            val channelData = preferences[channelKey(channelId)]
            channelData?.let { deserializeChannel(it) }
        }

    private fun serializeChannel(channel: ChannelSubscription): String =
        "${channel.channelId}|${channel.channelName}|${channel.channelThumbnail}|${channel.subscribedAt}|${channel.lastVideoId ?: ""}|${channel.lastCheckTime}|${channel.isNotificationEnabled}|${channel.isMusic}|${channel.lastFeedFetchAt}"

    private fun ChannelSubscription.withPreservedThumbnail(preferences: Preferences): ChannelSubscription {
        val existing = preferences[channelKey(channelId)]?.let { deserializeChannel(it) }
        return if (
            ThumbnailUrlResolver.isYoutubeVideoThumbnail(channelThumbnail) &&
            existing?.channelThumbnail?.isNotBlank() == true &&
            !ThumbnailUrlResolver.isYoutubeVideoThumbnail(existing.channelThumbnail)
        ) {
            copy(channelThumbnail = existing.channelThumbnail)
        } else {
            this
        }
    }

    private fun deserializeChannel(data: String): ChannelSubscription? =
        try {
            val parts = data.split("|")
            if (parts.size >= 4) {
                ChannelSubscription(
                    channelId = parts[0],
                    channelName = parts[1],
                    channelThumbnail = parts[2],
                    subscribedAt = parts[3].toLong(),
                    lastVideoId = if (parts.size > 4 && parts[4].isNotEmpty()) parts[4] else null,
                    lastCheckTime = if (parts.size > 5 && parts[5].isNotEmpty()) parts[5].toLong() else 0L,
                    isNotificationEnabled = if (parts.size > 6 && parts[6].isNotEmpty()) parts[6].toBoolean() else false,
                    isMusic = if (parts.size > 7 && parts[7].isNotEmpty()) parts[7].toBoolean() else false,
                    lastFeedFetchAt = if (parts.size > 8 && parts[8].isNotEmpty()) parts[8].toLong() else 0L,
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

    /**
     * Update the notification state for a channel
     */
    suspend fun updateNotificationState(
        channelId: String,
        enabled: Boolean,
    ) {
        context.subscriptionsDataStore.edit { preferences ->
            val channelData = preferences[channelKey(channelId)]
            if (channelData != null) {
                val subscription = deserializeChannel(channelData)
                if (subscription != null) {
                    val updated = subscription.copy(isNotificationEnabled = enabled)
                    preferences[channelKey(channelId)] = serializeChannel(updated)
                }
            }
        }
    }
}

data class ChannelSubscription(
    val channelId: String,
    val channelName: String,
    val channelThumbnail: String,
    val subscribedAt: Long = System.currentTimeMillis(),
    val lastVideoId: String? = null,
    /** When the background new-upload check last saw a *new* video for this channel. */
    val lastCheckTime: Long = 0L,
    val isNotificationEnabled: Boolean = false,
    val isMusic: Boolean = false,
    /** When the subscription feed last fetched this channel; 0 means never. */
    val lastFeedFetchAt: Long = 0L,
)
