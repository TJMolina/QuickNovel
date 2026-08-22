package com.lagradost.quicknovel.util.updates.services

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lagradost.quicknovel.util.updates.data.UpdatesManager
import com.lagradost.quicknovel.util.updates.util.UpdatesNotificationHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Periodic background worker that checks all watched novels for new chapters.
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
            updatedEntries.forEach { entry ->
                val last = entry.lastTotalChapters ?: entry.totalChapters
                if (entry.totalChapters > last) {
                    UpdatesNotificationHelper.postEntryNotification(entry, ctx)
                }
            }

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}