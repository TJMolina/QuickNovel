package com.lagradost.quicknovel.ui.updates.services

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object NovelAutoUpdateScheduler {

    const val KEY_ENABLED = "novel_auto_update_enabled"
    const val KEY_INTERVAL = "novel_auto_update_interval"
    private const val WORK_TAG = "novel_updates_periodic_work"
    val INTERVAL_OPTIONS = listOf(8L, 12L, 24L)   // hours

    /**
     * Applies the current state (schedule or cancel).
     * Should be called whenever a preference changes.
     */
    fun apply(context: Context, forceUpdate: Boolean = false) {
        val prefs     = PreferenceManager.getDefaultSharedPreferences(context)
        val enabled   = prefs.getBoolean(KEY_ENABLED, false)
        val intervalH = prefs.getString(KEY_INTERVAL, "24")?.toLongOrNull() ?: 24L
        val wm        = WorkManager.Companion.getInstance(context)

        if (!enabled) {
            wm.cancelUniqueWork(WORK_TAG)
            return
        }

        val request = PeriodicWorkRequestBuilder<NovelUpdatesWorker>(intervalH, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(WORK_TAG)
            .build()

        //if the user changes the interval option
        val policy = if (forceUpdate) {
            ExistingPeriodicWorkPolicy.UPDATE
        } else {
            ExistingPeriodicWorkPolicy.KEEP
        }

        wm.enqueueUniquePeriodicWork(
            WORK_TAG,
            policy,
            request,
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(KEY_ENABLED, enabled)
        }
        apply(context)
    }

    fun setInterval(context: Context, intervalHours: Long) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY_INTERVAL, intervalHours.toString())
        }
        apply(context, true)
    }

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_ENABLED, false)

    fun currentInterval(context: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_INTERVAL, "24")?.toLongOrNull() ?: 24L
}