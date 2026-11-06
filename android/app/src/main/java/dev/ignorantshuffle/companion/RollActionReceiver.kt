package dev.ignorantshuffle.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RollActionReceiver : BroadcastReceiver() {
    companion object {
        const val NEXT = "dev.ignorantshuffle.companion.PLAY_NEXT_ROLL"
        const val PREVIOUS = "dev.ignorantshuffle.companion.PLAY_PREVIOUS_ROLL"
        const val TOKEN = "token"
        const val SEQUENCE = "sequence"
        const val TRACK = "track"
        const val SOURCE = "source"
        const val HISTORY_INDEX = "historyIndex"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NEXT && intent.action != PREVIOUS) return
        val token = intent.getStringExtra(TOKEN) ?: return
        val track = intent.getStringExtra(TRACK) ?: return
        val controller = (context.applicationContext as ShuffleApplication).controller
        val sequence = intent.getLongExtra(SEQUENCE, -1)
        if (intent.action == NEXT) controller.playNotificationRoll(RollAction(token, sequence, track))
        else {
            val source = intent.getStringExtra(SOURCE) ?: return
            val index = intent.getIntExtra(HISTORY_INDEX, -1)
            if (index >= 0) controller.playNotificationPrevious(PreviousRollAction(token, sequence, source, track, index))
        }
    }
}
