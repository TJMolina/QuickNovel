package com.lagradost.quicknovel.ui.updates.data

import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.NOVEL_WATCH_FOLDER
import com.lagradost.quicknovel.ui.updates.data.WatchEntry.Companion.fromWatchEntry
import com.lagradost.quicknovel.util.ResultCached
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object UpdatesManager {

    /**
     * Emits a new timestamp every time a check (manual or automatic) finishes.
     * [DownloadFragment] collects it to refresh the notification bell in real-time,
     * even when the fragment is already visible (resumed) and onResume is not triggered again.
     */
    private val _updateTick = MutableStateFlow(0L)
    val updateTick: StateFlow<Long> = _updateTick

    // ── CRUD ────────────────────────────────────────────────────────────────
    fun addToWatchList(cached: ResultCached) {
        if (isWatched(cached.id)) return
        setKey(NOVEL_WATCH_FOLDER, cached.id.toString(), WatchEntry.fromCached(cached))
    }

    fun removeFromWatchList(novelId: Int) {
        removeKey(NOVEL_WATCH_FOLDER, novelId.toString())
    }

    fun isWatched(novelId: Int): Boolean =
        getKey<WatchEntry>(NOVEL_WATCH_FOLDER, novelId.toString()) != null

    fun getWatchList(): List<WatchEntry> {
        val keys = getKeys(NOVEL_WATCH_FOLDER) ?: return emptyList()
        return keys.mapNotNull { getKey(it) }
    }

    fun getEntry(novelId: Int): WatchEntry? =
        getKey(NOVEL_WATCH_FOLDER, novelId.toString())

    fun saveEntry(entry: WatchEntry) {
        setKey(NOVEL_WATCH_FOLDER, entry.novelId.toString(), entry)
    }

    /** Moves baselineChapters forward so the "new" badge disappears. */
    fun markAsSeen(novelId: Int) {
        val e = getEntry(novelId) ?: return
        if (!e.isPermanent) {
            removeFromWatchList(novelId)
        } else {
            saveEntry(e.copy(baselineChapters = e.lastCheckedChapters))
        }
    }

    // ── Network Check ───────────────────────────────────────────────────────

    suspend fun remoteCheking(now:Long){
        _updateTick.value = now
    }

    /** Verifies online and persists the result. Returns the updated WatchEntry. */

    suspend fun checkForUpdate(entry: WatchEntry): WatchEntry {
        val updatedCached = BookDownloader2.getNewTotalChapters(fromWatchEntry(entry), -1)
        val now = System.currentTimeMillis()

        val updatedEntry = if (updatedCached != null) {
            if (updatedCached.id != entry.novelId) {
                removeKey(NOVEL_WATCH_FOLDER, entry.novelId.toString())
            }

            entry.copy(
                novelId = updatedCached.id,
                lastCheckedChapters = updatedCached.totalChapters,
                lastCheckedMs = now,
                checkFailed = false
            )
        } else {
            entry.copy(
                checkFailed = true,
                lastCheckedMs = now
            )
        }

        saveEntry(updatedEntry)

        _updateTick.value = now

        return updatedEntry
    }
}