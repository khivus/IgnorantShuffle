package dev.ignorantshuffle.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RollActionReceiver : BroadcastReceiver() {
    companion object {
        const val NEXT = "dev.ignorantshuffle.companion.PLAY_NEXT_ROLL"
        const val TOKEN = "token"
        const val SEQUENCE = "sequence"
        const val TRACK = "track"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NEXT) return
        val token = intent.getStringExtra(TOKEN) ?: return
        val track = intent.getStringExtra(TRACK) ?: return
        val action = RollAction(token, intent.getLongExtra(SEQUENCE, -1), track)
        (context.applicationContext as ShuffleApplication).controller.playNotificationRoll(action)
    }
}
