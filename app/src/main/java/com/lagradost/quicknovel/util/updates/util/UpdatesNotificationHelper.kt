package com.lagradost.quicknovel.util.updates.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.NotificationHelper
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.receivers.MarkAsSeenReceiver
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute

object UpdatesNotificationHelper {
    const val UPDATES_CHANNEL_ID = "novel_updates_channel"
    const val UPDATES_NAME = "Updates"
    const val UPDATES_DESCRIPT = "The updates notification chanel"
    private var hasCreatedNotChanel = false

    fun Context.createUpdatesNotificationChannel() {
        if (hasCreatedNotChanel) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATES_CHANNEL_ID,
                UPDATES_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = UPDATES_DESCRIPT }
            val notificationManager: NotificationManager =
                this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        hasCreatedNotChanel = true
    }

    suspend fun postEntryNotification(entry: ResultCached, ctx: Context) {
        val last = entry.lastTotalChapters ?: return
        val newChapters = (entry.totalChapters - last).coerceAtLeast(0)
        if(newChapters == 0) return

        ctx.createUpdatesNotificationChannel()

        //open novel touching notification
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_URL, entry.source)
            putExtra(MainActivity.EXTRA_API_NAME, entry.apiName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, entry.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        //mark as seen the novel touching mark as seen button
        val markAsSeenIntent = Intent(ctx, MarkAsSeenReceiver::class.java).apply {
            action = MarkAsSeenReceiver.ACTION_MARK_AS_SEEN
            putExtra(MarkAsSeenReceiver.EXTRA_NOVEL_ID, entry.id)
        }
        val markAsSeenPendingIntent = PendingIntent.getBroadcast(
            ctx, entry.id.hashCode() + 1, markAsSeenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val body = ctx.resources.getQuantityString(
            R.plurals.novel_updates_new_chapters,
            newChapters,
            newChapters,
        )

        val builder = NotificationCompat.Builder(ctx, UPDATES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_notifications_active_24)
            .setContentTitle(entry.name)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(ctx.colorFromAttribute(R.attr.colorPrimary))
            .addAction(
                R.drawable.ic_baseline_check_24,
                ctx.getString(R.string.updates_mark_seen),
                markAsSeenPendingIntent
            )

        NotificationHelper.getLargeIcon(ctx, entry.poster)?.let {
            builder.setLargeIcon(it)
        }

        try {
            NotificationManagerCompat.from(ctx).notify(entry.id.hashCode(), builder.build())
        } catch (e: Throwable) {
            logError(e)
        }
    }
}
