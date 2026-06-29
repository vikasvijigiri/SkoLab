package com.company.skolab.utils

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.company.skolab.MainActivity
import com.company.skolab.R
import com.company.skolab.data.UserActivityTracker

object SkoLabNotificationManager {
    private const val TAG = "NotificationManager"
    const val CHANNEL_ID = "skolab_reminders"
    private const val CHANNEL_SPARK = "skolab_spark"
    private const val NOTIFICATION_ID = 1001
    private const val SPARK_NOTIFICATION_ID = 2002
    private const val REMINDER_REQ_CODE = 2001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SkoLab Reminders"
            val descriptionText = "Personalized research reminders and updates"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    fun showReminderNotification(context: Context) {
        // Check if today is Friday
        val calendar = java.util.Calendar.getInstance()
        val isFriday = calendar.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY

        val title = if (isFriday) "SkoLab Weekly Goal" else "SkoLab Research Update"
        
        // Fetch active reminders from derived user memory
        val memory = UserActivityTracker.readMemory(context)
        val reminders = memory.proactiveReminders()
        val message = if (isFriday) {
            "Time to make SkoLab better! Open your workspace to set goals, review progress, and check reminders."
        } else if (reminders.isNotEmpty()) {
            reminders.random()
        } else {
            "Ready to continue your research? Open SkoLab to explore new papers."
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Use standard icon, falls back to application logo
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(context)) {
                notify(NOTIFICATION_ID, builder.build())
                Log.d(TAG, "Notification posted: $message")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission missing to show notification", e)
        }
    }

    fun showSparkCallNotification(context: Context, seekerName: String, topic: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(CHANNEL_SPARK, "Expert Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming expert help requests"
                setSound(ringtoneUri, audioAttrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, SPARK_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_SPARK)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$seekerName needs your help")
            .setContentText(topic.take(100))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(SPARK_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission for spark call notification", e)
        }
    }

    fun scheduleDailyReminder(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, REMINDER_REQ_CODE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Schedule for 24 hours from now
        val triggerAtMillis = System.currentTimeMillis() + AlarmManager.INTERVAL_DAY
        
        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.d(TAG, "Daily reminder scheduled in 24 hours")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule reminder alarm", e)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ReminderReceiver", "Alarm received, showing notification")
        SkoLabNotificationManager.createNotificationChannel(context)
        SkoLabNotificationManager.showReminderNotification(context)
        
        // Reschedule next reminder
        SkoLabNotificationManager.scheduleDailyReminder(context)
    }
}
