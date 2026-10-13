package com.kunwooroh.gemmaagent.tools

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kunwooroh.gemmaagent.MainActivity

const val REMINDER_CHANNEL_ID = "reminders"

/** 정해진 시간이 되면 알림을 띄운다. */
class ReminderReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "알림"
    val id = intent.getIntExtra(EXTRA_ID, 0)
    Reminders.showNotification(context, id, message)
  }

  companion object {
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_ID = "id"
  }
}

object Reminders {
  fun createChannel(context: Context) {
    val channel =
      NotificationChannel(REMINDER_CHANNEL_ID, "리마인더", NotificationManager.IMPORTANCE_HIGH)
        .apply { description = "젬마 비서가 예약한 알림" }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  /** [minutesFromNow]분 뒤에 알림을 예약한다. 정확한 알람 권한이 없어도 동작하도록 절전 허용 알람을 쓴다. */
  fun schedule(context: Context, message: String, minutesFromNow: Int): Int {
    val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    val intent =
      Intent(context, ReminderReceiver::class.java).apply {
        putExtra(ReminderReceiver.EXTRA_MESSAGE, message)
        putExtra(ReminderReceiver.EXTRA_ID, id)
      }
    val pending =
      PendingIntent.getBroadcast(
        context,
        id,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val am = context.getSystemService(AlarmManager::class.java)
    val triggerAt = SystemClock.elapsedRealtime() + minutesFromNow * 60_000L
    if (am.canScheduleExactAlarms()) {
      am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
    } else {
      am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
    }
    return id
  }

  fun showNotification(context: Context, id: Int, message: String) {
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val openApp =
      PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val notification =
      NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle("젬마 비서 알림")
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(openApp)
        .build()
    NotificationManagerCompat.from(context).notify(id, notification)
  }
}
