package com.lagradost.quicknovel.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.lagradost.quicknovel.util.updates.data.UpdatesManager

class MarkAsSeenReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_MARK_AS_SEEN = "com.lagradost.quicknovel.ACTION_MARK_AS_SEEN"
        const val EXTRA_NOVEL_ID = "extra_novel_id"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_MARK_AS_SEEN) {
            val novelId = intent.getIntExtra(EXTRA_NOVEL_ID, -1)
            if (novelId != -1) {
                UpdatesManager.markAsSeen(novelId)

                // Dismiss notification
                context?.let {
                    NotificationManagerCompat.from(it).cancel(novelId)
                }
            }
        }
    }
}
