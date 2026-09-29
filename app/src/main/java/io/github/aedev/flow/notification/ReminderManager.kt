package io.github.aedev.flow.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object ReminderManager {
    private const val ALARM_ID_BREAK = 102

    fun scheduleBreakReminder(
        context: Context,
        frequencyMinutes: Int,
    ) {
        if (!canScheduleExactAlarms(context)) {
            // Handle appropriately
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent =
            Intent(context, ReminderReceiver::class.java).apply {
                putExtra("type", "break")
                putExtra("frequency", frequencyMinutes)
            }

        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                ALARM_ID_BREAK,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val triggerTime = System.currentTimeMillis() + (frequencyMinutes * 60 * 1000L)

        scheduleAlarmSafe(context, alarmManager, triggerTime, pendingIntent)
    }

    private fun canScheduleExactAlarms(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    private fun scheduleAlarmSafe(
        context: Context,
        alarmManager: AlarmManager,
        triggerTime: Long,
        pendingIntent: PendingIntent,
    ) {
        try {
            if (canScheduleExactAlarms(context)) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent,
                )
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
            // Fallback for when permission is revoked in bg
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
