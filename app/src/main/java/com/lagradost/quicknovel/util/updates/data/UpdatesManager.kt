package com.lagradost.quicknovel.util.updates.data

import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.NOVEL_WATCH_FOLDER
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.util.ResultCached
object UpdatesManager {

    // ── CRUD ────────────────────────────────────────────────────────────────
    fun addToWatchList(cached: ResultCached) {
        if (isWatched(cached.id)) return
        setKey(NOVEL_WATCH_FOLDER, cached.id.toString(), cached)
    }

    fun removeFromWatchList(novelId: Int) {
        removeKey(NOVEL_WATCH_FOLDER, novelId.toString())
    }

    fun isWatched(novelId: Int): Boolean =
        getKey<ResultCached>(NOVEL_WATCH_FOLDER, novelId.toString()) != null

    fun getWatchList(): List<ResultCached> {
        val keys = getKeys(NOVEL_WATCH_FOLDER) ?: return emptyList()
        return keys.mapNotNull { getKey(it) }
    }

    fun getEntry(novelId: Int): ResultCached? =
        getKey(RESULT_BOOKMARK, novelId.toString())

    fun saveEntry(entry: ResultCached) {
        setKey(RESULT_BOOKMARK, entry.id.toString(), entry)
    }

    /** Moves lastTotalChapters forward so the "new" badge disappears. */
    fun markAsSeen(novelId: Int) {
        val e = getEntry(novelId) ?: return
        saveEntry(e.copy(lastTotalChapters = e.totalChapters))

        val novelIdKey = novelId.toString()

        getKey<ResultCached>(HISTORY_FOLDER, novelIdKey)?.let { cached ->
            setKey(HISTORY_FOLDER, novelIdKey, cached.copy(lastTotalChapters = cached.totalChapters))
        }
    }

    // ── Network Check ───────────────────────────────────────────────────────

    /** Verifies online and persists the result. Returns the updated ResultCached. */
    suspend fun checkForUpdate(entry: ResultCached): ResultCached {
        val updatedCached = BookDownloader2.getNewTotalChapters(entry, -1)
        val now = System.currentTimeMillis()

        val result = if (updatedCached != null) {
            if (updatedCached.id != entry.id) {
                removeKey(NOVEL_WATCH_FOLDER, entry.id.toString())
            }

            val updated = entry.copy(
                id = updatedCached.id,
                totalChapters = updatedCached.totalChapters,
                cachedTime = now
            )
            val id = updated.id.toString()
            getKey<ResultCached>(HISTORY_FOLDER, id)?.let { cached ->
                setKey(HISTORY_FOLDER, id, cached.copy(totalChapters = updated.totalChapters))
            }

            updated
        } else {
            entry // Failed check, return same
        }

        saveEntry(result)
        return result
    }
}
