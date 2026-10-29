package dev.ignorantshuffle.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

class PlaybackIssueNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    companion object { private const val CHANNEL = "playback-debug"; private const val ID = 202 }
    fun allowed(): Boolean {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Playback debug issues", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Warn when the selected track does not match observed AIMP playback"
        })
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun clear() { manager.cancel(ID) }
    fun show(message: String, accent: Accent): Boolean {
        if (!allowed()) return false
        return try {
            val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val public = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_shuffle)
                .setContentTitle("IgnorantShuffle playback issue").setContentText("Open the app for details").build()
            manager.notify(ID, Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_shuffle)
                .setContentTitle("Selected track did not play next").setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message)).setContentIntent(open)
                .setAutoCancel(true).setColor(accent.argb.toInt()).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(public).setCategory(Notification.CATEGORY_ERROR).build())
            true
        } catch (_: SecurityException) { false }
    }
}
