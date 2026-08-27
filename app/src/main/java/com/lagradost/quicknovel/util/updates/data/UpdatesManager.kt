package com.lagradost.quicknovel.util.updates.data

import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.NOVEL_WATCH_FOLDER
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.RESULT_BOOKMARK_STATE
import com.lagradost.quicknovel.util.ResultCached
object UpdatesManager {
    fun addToWatchList(cached: ResultCached) {
        if (isWatched(cached.id)) return
        setKey(NOVEL_WATCH_FOLDER, cached.id.toString(), true)
        saveEntry(cached)
    }

    fun removeFromWatchList(novelId: Int) {
        removeKey(NOVEL_WATCH_FOLDER, novelId.toString())
    }

    fun isWatched(novelId: Int?): Boolean =
        getKey<Any>(NOVEL_WATCH_FOLDER, novelId.toString()) != null

    fun getWatchList(): List<ResultCached> {
        val keys = getKeys(NOVEL_WATCH_FOLDER) ?: return emptyList()
        return keys.mapNotNull { key ->
            val idStr = key.substringAfterLast('/')
            val id = idStr.toIntOrNull() ?: return@mapNotNull null
            getEntry(id)
        }
    }

    fun getEntry(novelId: Int): ResultCached? =
        getKey<ResultCached>(RESULT_BOOKMARK, novelId.toString())
            ?: getKey<ResultCached>(HISTORY_FOLDER, novelId.toString())

    fun saveEntry(entry: ResultCached) {
        val id = entry.id.toString()
        // Always save to history as the master record
        setKey(HISTORY_FOLDER, id, entry)

        // Only save to bookmarks if it's actually bookmarked
        if (getKey<Int>(RESULT_BOOKMARK_STATE, id) != null) {
            setKey(RESULT_BOOKMARK, id, entry)
        }
        BookDownloader2.bookmarkChanged(entry.id)
    }

    fun migrateWatchList(oldId: Int, newId: Int, newEntry: ResultCached) {
        if (isWatched(oldId)) {
            removeFromWatchList(oldId)
            addToWatchList(newEntry)
        }
    }

    /** Moves lastTotalChapters forward so the "new" badge disappears. */
    fun markAsSeen(novelId: Int) {
        val e = getEntry(novelId) ?: return
        val updated = e.copy(lastTotalChapters = e.totalChapters)
        saveEntry(updated)
    }

    // ── Network Check ───────────────────────────────────────────────────────

    /** Verifies online and persists the result. Returns the updated ResultCached. */
    suspend fun checkForUpdate(entry: ResultCached): ResultCached {
        val updatedCached = BookDownloader2.getNewTotalChapters(entry, -1)
        val now = System.currentTimeMillis()
        val result = updatedCached?.copy(cachedTime = now) ?: entry // Failed check, return same
        saveEntry(result)
        return result
    }
}
