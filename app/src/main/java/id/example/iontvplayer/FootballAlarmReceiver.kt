package id.example.iontvplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

class FootballAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val channelId = "iontv_football_reminders"
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Pengingat Sepak Bola",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Pengingat pertandingan lima menit sebelum kick-off"
                }
            )
        }

        val id = intent.getIntExtra("match_id", 1)
        val title = intent.getStringExtra("match_title") ?: "Pertandingan sepak bola"
        val league = intent.getStringExtra("league") ?: "Jadwal pertandingan"

        val openApp = Intent(context, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            context,
            id,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⚽ $title")
            .setContentText("$league • Kick-off sekitar 5 menit lagi")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "$league\nPertandingan dijadwalkan mulai sekitar 5 menit lagi."
                )
            )
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(Notification.PRIORITY_HIGH)

        manager.notify(id, builder.build())
    }
}
