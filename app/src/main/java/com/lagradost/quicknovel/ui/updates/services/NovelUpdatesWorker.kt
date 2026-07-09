package com.lagradost.quicknovel.ui.updates.services

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lagradost.quicknovel.ui.updates.data.UpdatesManager
import com.lagradost.quicknovel.ui.updates.util.UpdatesNotificationHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Periodic background worker that checks all watched novels for new chapters.
 * Fired by [NovelAutoUpdateScheduler] at the user-chosen interval (8 / 12 / 24 h).
 *
 * One notification per novel is posted (same style as download notifications):
 *   - title  = novel name
 *   - body   = "N new chapter(s) available"
 *   - icon   = rddone
 *   - cover  = large icon loaded from posterUrl
 */
class NovelUpdatesWorker(
    private val ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val watchList = UpdatesManager.getWatchList()
            if (watchList.isEmpty()) return Result.success()

            val updatedEntries = coroutineScope {
                watchList.map { entry ->
                    async {
                        UpdatesManager.checkForUpdate(entry)
                    }
                }.awaitAll()
            }

            // Post one notification per novel that has new chapters
            updatedEntries.filter { it.hasUpdate }.forEach { entry ->
                UpdatesNotificationHelper.postEntryNotification(entry, ctx)
            }

            Result.success()
        } catch (_: Exception) {
            // If it's a network error, WorkManager will retry later based on backoff policy
            Result.retry()
        }
    }
}