package com.lagradost.quicknovel.ui.updates.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.NotificationHelper
import com.lagradost.quicknovel.NotificationHelper.createNotificationChannels
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ui.updates.data.WatchEntry
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute

object UpdatesNotificationHelper {

    suspend fun postEntryNotification(entry: WatchEntry, ctx: Context) {
       ctx.createNotificationChannels()

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.Companion.OPEN_UPDATES_EXTRA, true)
        }

        val pendingIntent = PendingIntent.getActivity(
            ctx, entry.novelId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val body = ctx.resources.getQuantityString(
            R.plurals.novel_updates_new_chapters,
            entry.newChapters,
            entry.newChapters,
        )

        val builder = NotificationCompat.Builder(ctx, NotificationHelper.UPDATES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quicknovel)
            .setContentTitle(entry.novelName)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(ctx.colorFromAttribute(R.attr.colorPrimary))

        NotificationHelper.getLargeIcon(ctx, entry.posterUrl)?.let {
            builder.setLargeIcon(it)
        }

        try {
            NotificationManagerCompat.from(ctx).notify(entry.novelId.hashCode(), builder.build())
        } catch (_: SecurityException) {
        }
    }
}