package com.lagradost.quicknovel.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.util.ResultCached
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

                // Also update ResultCached in Library/History
                updateCached(novelId)

                // Dismiss notification
                context?.let {
                    NotificationManagerCompat.from(it).cancel(novelId)
                }
            }
        }
    }

    private fun updateCached(novelId: Int) {
        val bookmarkKey = novelId.toString()
        getKey<ResultCached>(RESULT_BOOKMARK, bookmarkKey)?.let { cached ->
            setKey(RESULT_BOOKMARK, bookmarkKey, cached.copy(lastTotalChapters = cached.totalChapters))
        }
        getKey<ResultCached>(HISTORY_FOLDER, bookmarkKey)?.let { cached ->
            setKey(HISTORY_FOLDER, bookmarkKey, cached.copy(lastTotalChapters = cached.totalChapters))
        }
    }
}
