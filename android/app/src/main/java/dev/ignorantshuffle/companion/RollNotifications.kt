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

class RollNotifications(private val context: Context, private val event: (String) -> Unit) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var last: String? = null
    companion object { const val CHANNEL = "next-roll"; private const val ID = 201 }
    fun allowed(): Boolean = (Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun clear() { manager.cancel(ID); last = null }
    fun render(preferences: AppPreferences, choice: Choice, weight: Double, settings: Settings,
        automatic: Boolean, sequence: Long): String {
        if (!preferences.notifyRoll) { clear(); return "Next-roll notifications are off" }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Next weighted roll", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Quiet preview of the companion's next weighted selection"
            setSound(null, null); enableVibration(false)
        })
        if (!allowed()) { clear(); return "Notifications blocked by Android permission/channel settings" }
        val text = RollNotificationText.create(choice, weight, settings, automatic, preferences.stats, sequence)
            ?: run { clear(); return "Waiting for an eligible next roll" }
        val fingerprint = "$sequence/$text/${preferences.accent}"
        if (fingerprint == last) return "Next-roll notification active"
        try {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val public = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_shuffle)
                .setContentTitle("IgnorantShuffle").setContentText("Next weighted roll is ready").build()
            val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_shuffle)
                .setContentTitle(text.title).setContentText(text.body.replace('\n', ' '))
                .setStyle(Notification.BigTextStyle().bigText(text.body)).setContentIntent(open)
                .setOnlyAlertOnce(true).setAutoCancel(true).setShowWhen(false)
                .setColor(preferences.accent.argb.toInt()).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(public).setCategory(Notification.CATEGORY_STATUS).build()
            manager.notify(ID, notification); last = fingerprint
            event("Notification updated for roll #$sequence")
            return "Next-roll notification active"
        } catch (e: SecurityException) { event("Notification permission denied: $e"); return "Notification permission denied" }
    }
}
