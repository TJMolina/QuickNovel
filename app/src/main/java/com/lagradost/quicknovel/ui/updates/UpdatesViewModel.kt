package com.lagradost.quicknovel.ui.updates

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.RESULT_BOOKMARK_STATE
import com.lagradost.quicknovel.ui.ReadType
import com.lagradost.quicknovel.ui.updates.data.UpdatesManager
import com.lagradost.quicknovel.ui.updates.data.WatchEntry
import com.lagradost.quicknovel.util.ResultCached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class UpdatesViewModel : ViewModel() {

    /** Current list of watched novels. */
    val entries: MutableLiveData<List<WatchEntry>> = MutableLiveData(emptyList())

    /** IDs of novels being checked at the moment (to display a spinner). */
    val checkingIds: MutableLiveData<Set<Int>> = MutableLiveData(emptySet())

    /** User library novels (for the "Add novels" dialog). */
    val bookmarkedNovels: MutableLiveData<List<ResultCached>> = MutableLiveData(emptyList())

    init { reload() }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val newList = UpdatesManager.getWatchList()
            if (entries.value != newList)
                entries.postValue(UpdatesManager.getWatchList())
        }
    }

    fun removeFromWatch(novelId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            UpdatesManager.removeFromWatchList(novelId)
            val currentList = entries.value ?: emptyList()
            entries.postValue(currentList.filter { it.novelId != novelId })
        }
    }

    fun markAsSeen(novelId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            UpdatesManager.markAsSeen(novelId)
            val currentList = entries.value ?: emptyList()
            val newList = currentList.map {
                if (it.novelId == novelId) it.copy(baselineChapters = it.lastCheckedChapters)
                else it
            }
            entries.postValue(newList)
        }
    }

    /** Checks a single novel online. */
    fun checkOne(entry: WatchEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                setChecking(entry.novelId, true)
                UpdatesManager.checkForUpdate(entry)
            } finally {
                setChecking(entry.novelId, false)
                reload()
            }
        }
    }

    /** Checks all watched novels in sequence. */
    fun checkAll() {
        val list = entries.value ?: return
        if (list.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Set all as checking
                checkingIds.postValue(list.map { it.novelId }.toSet())

                // Launch all checks in parallel
                list.map { entry ->
                    async { UpdatesManager.checkForUpdate(entry) }
                }.awaitAll()

            } finally {
                checkingIds.postValue(emptySet())
                reload()
            }
        }
    }

    fun addToWatch(cached: ResultCached) {
        viewModelScope.launch(Dispatchers.IO) {
            UpdatesManager.addToWatchList(cached)
            reload()
        }
    }

    /**
     * Loads the user's library (for the picker dialog).
     *
     * Filters entries with NONE(0) state — which are orphan keys without a real novel —
     * and entries with empty names — legacy data from original QN with null injected
     * by Gson into non-nullable String fields. This ensures the picker only shows
     * truly bookmarked novels.
     */
    fun loadBookmarkedNovels() {
        viewModelScope.launch(Dispatchers.IO) {
            val keys = getKeys(RESULT_BOOKMARK_STATE) ?: return@launch
            val list = keys.mapNotNull { key ->
                // Skip NONE(0) / orphan keys — any nonzero state means the novel is in a library
                // (works for both built-in ReadTypes 1-5 and custom dynamic libraries 6+)
                val stateValue = getKey<Int>(key) ?: return@mapNotNull null
                if (stateValue == ReadType.NONE.prefValue) return@mapNotNull null
                val bookKey = key.replaceFirst(RESULT_BOOKMARK_STATE, RESULT_BOOKMARK)
                val book = getKey<ResultCached>(bookKey) ?: return@mapNotNull null
                // Skip entries with blank/null name — legacy data from original QN may have
                // name=null at runtime (Gson bypasses Kotlin non-nullable String)
                if (book.name.isBlank()) return@mapNotNull null
                book
            }.sortedBy { it.name }
            bookmarkedNovels.postValue(list)
        }
    }

    private fun setChecking(id: Int, checking: Boolean) {
        val current = checkingIds.value ?: emptySet()
        val next = if (checking) current + id else current - id
        if (current != next)
            checkingIds.postValue(next)
    }
}