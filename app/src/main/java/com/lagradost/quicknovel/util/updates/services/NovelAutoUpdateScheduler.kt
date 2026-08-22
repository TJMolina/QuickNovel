package com.lagradost.quicknovel.util.updates.services

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.tachiyomi.AndroidPreferenceStore
import java.util.concurrent.TimeUnit

object NovelAutoUpdateScheduler {
    private const val WORK_TAG = "novel_updates_periodic_work"

    /**
     * Applies the current state (schedule or cancel)
     * Should be called whenever notification settings change.
     */
    fun apply(context: Context, enabled: Boolean? = null, interval: Long? = null) {
        val store = AndroidPreferenceStore(context)

        val enable2 = enabled ?: store.getBoolean(
            context.getString(R.string.novel_auto_update_enabled_key),
            false
        ).get()

        val intervalH = interval ?: store.getLong(
            context.getString(R.string.novel_auto_update_interval_key),
            24L
        ).get()

        val wm = WorkManager.getInstance(context)
        if (!enable2) wm.cancelUniqueWork(WORK_TAG)
        else wm.enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<NovelUpdatesWorker>(intervalH, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(WORK_TAG)
                .build()
        )
    }
}