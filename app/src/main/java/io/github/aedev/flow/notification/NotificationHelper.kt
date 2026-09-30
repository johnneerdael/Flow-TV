package io.github.aedev.flow.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.aedev.flow.MainActivity
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.update.AppRelease
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Comprehensive notification helper for the app
 * Handles all notification channels and provides methods for showing various notification types
 */
object NotificationHelper {
    // Notification Channel IDs
    const val CHANNEL_DOWNLOADS = "downloads_channel"
    const val CHANNEL_SUBSCRIPTIONS = "subscriptions_channel"
    const val CHANNEL_PLAYBACK = "playback_channel"
    const val CHANNEL_MUSIC_PLAYBACK = "music_playback_channel"
    const val CHANNEL_GENERAL = "general_channel"
    const val CHANNEL_REMINDERS = "reminders_channel"
    const val CHANNEL_UPDATES = "updates_channel"
    const val EXTRA_OPEN_UPDATE = "io.github.aedev.flow.extra.OPEN_UPDATE"
    const val CHANNEL_IMPORTS = "imports_channel"

    const val NOTIFICATION_PLAYBACK = 3001
    const val NOTIFICATION_IMPORT_PROGRESS = 6001
    const val NOTIFICATION_IMPORT_COMPLETE = 6002

    private var channelsCreated = false

    /**
     * Initialize all notification channels
     * Should be called once when the app starts (e.g., in Application.onCreate())
     */
    fun createNotificationChannels(context: Context) {
        if (channelsCreated) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Downloads channel - High importance for active downloads
            val downloadsChannel =
                NotificationChannel(
                    CHANNEL_DOWNLOADS,
                    context.getString(R.string.notification_channel_downloads),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_downloads_description)
                    setShowBadge(true)
                    enableLights(true)
                    enableVibration(false)
                }

            // Subscriptions channel - Default importance for new videos
            val subscriptionsChannel =
                NotificationChannel(
                    CHANNEL_SUBSCRIPTIONS,
                    context.getString(R.string.notification_channel_new_videos),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_new_videos_description)
                    setShowBadge(true)
                    enableLights(true)
                    enableVibration(true)
                }

            // Video playback channel - Low importance for background playback
            val playbackChannel =
                NotificationChannel(
                    CHANNEL_PLAYBACK,
                    context.getString(R.string.notification_channel_video_playback),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_video_playback_description)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }

            // Music playback channel - Low importance for background music
            val musicPlaybackChannel =
                NotificationChannel(
                    CHANNEL_MUSIC_PLAYBACK,
                    context.getString(R.string.notification_channel_music_playback),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_music_playback_description)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }

            // General notifications channel
            val generalChannel =
                NotificationChannel(
                    CHANNEL_GENERAL,
                    context.getString(R.string.notification_channel_general),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_general_description)
                    setShowBadge(true)
                }

            val remindersChannel =
                NotificationChannel(
                    CHANNEL_REMINDERS,
                    context.getString(R.string.notification_channel_reminders),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notification_channel_break_reminders_description)
                    setShowBadge(true)
                }

            // Updates channel
            val updatesChannel =
                NotificationChannel(
                    CHANNEL_UPDATES,
                    context.getString(R.string.notification_channel_updates),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_updates_description)
                    setShowBadge(true)
                }

            val importsChannel =
                NotificationChannel(
                    CHANNEL_IMPORTS,
                    context.getString(R.string.notification_channel_imports),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_import_description)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }

            notificationManager.createNotificationChannels(
                listOf(
                    downloadsChannel,
                    subscriptionsChannel,
                    playbackChannel,
                    musicPlaybackChannel,
                    generalChannel,
                    remindersChannel,
                    updatesChannel,
                    importsChannel,
                ),
            )

            channelsCreated = true
        }
    }

    /**
     * Check if notification permission is granted (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        if (!runBlocking { PlayerPreferences(context).notificationsEnabled.first() }) {
            return false
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // ========== IMPORT NOTIFICATIONS ==========

    /**
     * Show (or update) the import-in-progress notification.
     * When total == 0 the progress bar is indeterminate.
     */
    fun showImportProgress(
        context: Context,
        label: String,
        current: Int,
        total: Int,
    ) {
        if (!hasNotificationPermission(context)) return
        val contentText = if (total > 0) "$current / $total" else "Starting…"
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_IMPORTS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_importing, label))
                .setContentText(contentText)
                .apply {
                    if (total > 0) {
                        setProgress(total, current, false)
                    } else {
                        setProgress(0, 0, true)
                    }
                }.setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_IMPORT_PROGRESS, builder.build())
    }

    /** Replace the progress notification with a one-shot completion notification. */
    fun showImportComplete(
        context: Context,
        label: String,
        count: Int,
        message: String? = null,
    ) {
        if (!hasNotificationPermission(context)) return
        // cancel progress first
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_IMPORT_PROGRESS)
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_IMPORTS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_import_complete))
                .setContentText(message ?: context.getString(R.string.notification_imported, count, label.lowercase()))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_IMPORT_COMPLETE, builder.build())
    }

    /** Cancel the ongoing import progress notification (e.g. on error). */
    fun cancelImportNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_IMPORT_PROGRESS)
    }

    // ========== UPDATE NOTIFICATIONS ==========

    /**
     * Show notification for new app update
     */
    fun showUpdateNotification(
        context: Context,
        release: AppRelease,
    ) {
        if (!hasNotificationPermission(context)) return
        if (!runBlocking { PlayerPreferences(context).notifUpdatesEnabled.first() }) return

        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_UPDATE, true)
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_update_available, release.version))
                .setContentText(context.getString(R.string.notification_tap_to_update))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()

        NotificationManagerCompat.from(context).notify(9999, notification)
    }
}
