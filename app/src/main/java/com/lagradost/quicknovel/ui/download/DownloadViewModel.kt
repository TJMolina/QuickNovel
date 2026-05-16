package com.lagradost.quicknovel.ui.download

import android.content.DialogInterface
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.BookDownloader2.currentDownloads
import com.lagradost.quicknovel.BookDownloader2.currentDownloadsMutex
import com.lagradost.quicknovel.BookDownloader2.downloadInfoMutex
import com.lagradost.quicknovel.BookDownloader2.downloadProgress
import com.lagradost.quicknovel.BookDownloader2.downloadProgressChanged
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE_PDF
import com.lagradost.quicknovel.CURRENT_TAB
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.DEFAULT_LIBRARIES
import com.lagradost.quicknovel.DOWNLOAD_EPUB_LAST_ACCESS
import com.lagradost.quicknovel.DOWNLOAD_NORMAL_SORTING_METHOD
import com.lagradost.quicknovel.DOWNLOAD_SETTINGS
import com.lagradost.quicknovel.DOWNLOAD_SORTING_METHOD
import com.lagradost.quicknovel.DefaultLibrary
import com.lagradost.quicknovel.DownloadActionType
import com.lagradost.quicknovel.DownloadFileWorkManager
import com.lagradost.quicknovel.DownloadProgressState
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.MainActivity.Companion.loadResult
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.RESULT_BOOKMARK_STATE
import com.lagradost.quicknovel.getLibraries
import com.lagradost.quicknovel.mvvm.launchSafe
import com.lagradost.quicknovel.ui.ReadType
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.ResultCached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.set

const val DEFAULT_SORT = 0
const val ALPHA_SORT = 1
const val REVERSE_ALPHA_SORT = 2
const val DOWNLOADSIZE_SORT = 3
const val REVERSE_DOWNLOADSIZE_SORT = 4
const val DOWNLOADPRECENTAGE_SORT = 5
const val REVERSE_DOWNLOADPRECENTAGE_SORT = 6
const val LAST_ACCES_SORT = 7
const val REVERSE_LAST_ACCES_SORT = 8
const val LAST_UPDATED_SORT = 9
const val REVERSE_LAST_UPDATED_SORT = 10

const val CHAPTER_SORT = 11
const val REVERSE_CHAPTER_SORT = 12

data class SortingMethod(@StringRes val name: Int, val id: Int, val inverse: Int = id)
class DownloadViewModel : ViewModel() {

    companion object {
        val sortingMethods = arrayOf(
            SortingMethod(R.string.default_sort, DEFAULT_SORT),
            SortingMethod(R.string.recently_sort, LAST_ACCES_SORT, REVERSE_LAST_ACCES_SORT),
            SortingMethod(
                R.string.recently_updated_sort,
                LAST_UPDATED_SORT,
                REVERSE_LAST_UPDATED_SORT
            ),
            SortingMethod(R.string.alpha_sort, ALPHA_SORT, REVERSE_ALPHA_SORT),
            SortingMethod(R.string.download_sort, DOWNLOADSIZE_SORT, REVERSE_DOWNLOADSIZE_SORT),
            SortingMethod(
                R.string.download_perc, DOWNLOADPRECENTAGE_SORT,
                REVERSE_DOWNLOADPRECENTAGE_SORT
            ),
        )

        val normalSortingMethods = arrayOf(
            SortingMethod(R.string.default_sort, DEFAULT_SORT),
            SortingMethod(R.string.recently_sort, LAST_ACCES_SORT, REVERSE_LAST_ACCES_SORT),
            SortingMethod(R.string.alpha_sort, ALPHA_SORT, REVERSE_ALPHA_SORT),
        )
    }

    fun libraries(): List<DefaultLibrary> =
        context?.getLibraries() ?: DEFAULT_LIBRARIES

    var activeQuery: String = ""
    val _pages: MutableLiveData<List<Page>> = MutableLiveData(null)
    val pages: LiveData<List<Page>> = _pages

    var currentTab: MutableLiveData<Int> =
        MutableLiveData<Int>(getKey(DOWNLOAD_SETTINGS, CURRENT_TAB, 0))

    fun switchPage(position: Int) {
        setKey(DOWNLOAD_SETTINGS, CURRENT_TAB, position)
        currentTab.postValue(position)
    }

    fun refreshCard(card: DownloadFragment.DownloadDataLoaded) {
        DownloadFileWorkManager.download(card, context ?: return)
    }

    fun pause(card: DownloadFragment.DownloadDataLoaded) {
        BookDownloader2.addPendingAction(card.id, DownloadActionType.Pause)
    }

    fun resume(card: DownloadFragment.DownloadDataLoaded) {
        BookDownloader2.addPendingAction(card.id, DownloadActionType.Resume)
    }

    fun load(card: ResultCached) {
        loadResult(card.source, card.apiName)
    }

    fun stream(card: ResultCached) {
        BookDownloader2.stream(card)
    }

    fun search(query: String) {
        activeQuery = query.lowercase()
        resortAllData()
    }

    fun readEpub(card: DownloadFragment.DownloadDataLoaded) = ioSafe {
        try {
            cardsDataMutex.withLock {
                cardsData[card.id] = cardsData[card.id]?.copy(generating = true) ?: return@withLock
            }
            postCards()
            BookDownloader2.readEpub(
                card.id,
                card.downloadedCount.toInt(),
                card.author,
                card.name,
                card.apiName,
                card.synopsis
            )
        } finally {
            setKey(DOWNLOAD_EPUB_LAST_ACCESS, card.id.toString(), System.currentTimeMillis())
            cardsDataMutex.withLock {
                cardsData[card.id] = cardsData[card.id]?.copy(generating = false) ?: return@withLock
            }
            postCards()
        }
    }

    @WorkerThread
    suspend fun refreshInternal() {
        val allValues = cardsDataMutex.withLock {
            cardsData.values
        }

        val values = currentDownloadsMutex.withLock {
            allValues.filter { card ->
                val notImported = !card.isImported && card.apiName != IMPORT_SOURCE_PDF
                val canDownload =
                    card.downloadedTotal <= 0 || (card.downloadedCount * 100 / card.downloadedTotal) > 90
                val notDownloading = !currentDownloads.contains(
                    card.id
                )
                notImported && canDownload && notDownloading
            }
        }

        downloadInfoMutex.withLock {
            for (card in values) {
                downloadProgress[card.id]?.apply {
                    state = DownloadState.IsPending
                    lastUpdatedMs = System.currentTimeMillis()
                    downloadProgressChanged.invoke(card.id to this)
                }
            }
        }

        for (card in values) {
            if (card.downloadedTotal <= 0 || (card.downloadedCount * 100 / card.downloadedTotal) > 90) {
                BookDownloader2.downloadWorkThread(card)
            }
        }
    }

    fun refresh() {
        DownloadFileWorkManager.refreshAll(this@DownloadViewModel, context ?: return)
    }

    fun refreshReadingProgress(){
        DownloadFileWorkManager.refreshAllReadingProgress(this@DownloadViewModel, context ?: return, currentTab.value ?: 1)
    }

    fun showMetadata(card: DownloadFragment.DownloadDataLoaded) {
        MainActivity.loadPreviewPage(card)
    }

    fun importEpub() {
        MainActivity.importEpub()
    }

    fun showMetadata(card: ResultCached) {
        MainActivity.loadPreviewPage(card)
    }

    fun load(card: DownloadFragment.DownloadDataLoaded) {
        loadResult(card.source, card.apiName)
    }

    fun deleteAlert(card: ResultCached) {
        val dialogClickListener =
            DialogInterface.OnClickListener { _, which ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> {
                        delete(card)
                    }

                    DialogInterface.BUTTON_NEGATIVE -> {
                    }
                }
            }
        val act = activity ?: return
        val builder: AlertDialog.Builder = AlertDialog.Builder(act)
        builder.setMessage(act.getString(R.string.permanently_delete_format).format(card.name))
            .setTitle(R.string.delete)
            .setPositiveButton(R.string.delete, dialogClickListener)
            .setNegativeButton(R.string.cancel, dialogClickListener)
            .show()
    }

    fun delete(card: ResultCached) {
        removeKey(RESULT_BOOKMARK, card.id.toString())
        removeKey(RESULT_BOOKMARK_STATE, card.id.toString())
        loadAllData(false)
    }

    fun deleteAlert(card: DownloadFragment.DownloadDataLoaded) {
        val dialogClickListener =
            DialogInterface.OnClickListener { _, which ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> {
                        delete(card)
                    }

                    DialogInterface.BUTTON_NEGATIVE -> {
                    }
                }
            }
        val act = activity ?: return
        val builder: AlertDialog.Builder = AlertDialog.Builder(act)
        builder.setMessage(act.getString(R.string.permanently_delete_format).format(card.name))
            .setTitle(R.string.delete)
            .setPositiveButton(R.string.delete, dialogClickListener)
            .setNegativeButton(R.string.cancel, dialogClickListener)
            .show()
    }

    fun delete(card: DownloadFragment.DownloadDataLoaded) {
        BookDownloader2.deleteNovel(card.author, card.name, card.apiName)
    }

    private fun matchesQuery(x: String): Boolean {
        return activeQuery.isBlank() || FuzzySearch.partialRatio(x.lowercase(), activeQuery) > 50
    }

    private fun sortArray(
        currentArray: List<DownloadFragment.DownloadDataLoaded>,
    ): List<DownloadFragment.DownloadDataLoaded> {
        val filtered = currentArray.filter { matchesQuery(it.name) }
        if(filtered.isEmpty()) return emptyList()

        val newSortingMethod = getKey(DOWNLOAD_SETTINGS, DOWNLOAD_SORTING_METHOD) ?: DEFAULT_SORT
        return when (newSortingMethod) {
            ALPHA_SORT -> filtered.sortedBy { it.name }
            REVERSE_ALPHA_SORT -> filtered.sortedByDescending { it.name }


            REVERSE_DOWNLOADSIZE_SORT  -> filtered.sortedBy { it.downloadedCount }
            DOWNLOADSIZE_SORT -> filtered.sortedByDescending { it.downloadedCount }


            DOWNLOADPRECENTAGE_SORT -> filtered.sortedByDescending {
                it.downloadedCount.toFloat() / (if(it.downloadedTotal <= 0) 1 else it.downloadedTotal)
            }
            REVERSE_DOWNLOADPRECENTAGE_SORT -> filtered.sortedBy {
                it.downloadedCount.toFloat() / (if(it.downloadedTotal <= 0) 1 else it.downloadedTotal)
            }

            LAST_UPDATED_SORT -> filtered.sortedByDescending { it.lastDownloaded ?: 0L }
            REVERSE_LAST_UPDATED_SORT -> filtered.sortedBy { it.lastDownloaded ?: 0L }

            REVERSE_LAST_ACCES_SORT, LAST_ACCES_SORT, DEFAULT_SORT -> {
                val accessTimes = filtered.associate {
                    it.id to (getKey<Long>(DOWNLOAD_EPUB_LAST_ACCESS, it.id.toString(), 0L) ?: 0L)
                }

                if (newSortingMethod == REVERSE_LAST_ACCES_SORT) {
                    filtered.sortedBy { accessTimes[it.id] }
                } else {
                    filtered.sortedByDescending { accessTimes[it.id] }
                }
            }
            else -> filtered
        }
    }

    private fun sortNormalArray(
        currentArray: List<ResultCached>,
    ): List<ResultCached> {
        val newSortingMethod =
            getKey(DOWNLOAD_SETTINGS, DOWNLOAD_NORMAL_SORTING_METHOD) ?: DEFAULT_SORT
        setKey(DOWNLOAD_SETTINGS, DOWNLOAD_NORMAL_SORTING_METHOD, newSortingMethod)
        val filtered = currentArray.filter { matchesQuery(it.name) }
        return when (newSortingMethod) {
            ALPHA_SORT -> filtered.sortedBy { t -> t.name }
            REVERSE_ALPHA_SORT -> filtered.sortedByDescending { t -> t.name }

            REVERSE_LAST_ACCES_SORT -> filtered.sortedBy { t ->
                (getKey<Long>(
                    DOWNLOAD_EPUB_LAST_ACCESS,
                    t.id.toString(),
                    0
                )!!)
            }
            // DEFAULT_SORT, LAST_ACCES_SORT
            else -> filtered.sortedByDescending { t ->
                (getKey<Long>(
                    DOWNLOAD_EPUB_LAST_ACCESS,
                    t.id.toString(),
                    0
                )!!)
            }

        }
    }

    fun resortAllData() {
        val data = _pages.value ?: return
        if (data.isEmpty()) return

        val newList = data.mapIndexed { index, page ->
            if (index == 0) {
                page.copy(
                    items = sortArray(page.unsortedItems.filterIsInstance<DownloadFragment.DownloadDataLoaded>())
                )
            } else {
                page.copy(
                    items = sortNormalArray(ArrayList(page.unsortedItems.filterIsInstance<ResultCached>()))
                )
            }
        }

        _pages.value = newList
    }

    fun loadAllData(refreshAll: Boolean) = viewModelScope.launch {
        if (refreshAll) fetchAllData(false)
        val libraries = libraries()

        val pages = withContext(Dispatchers.IO) {
            //this will save all novels data
            val mapping = LinkedHashMap<Int, MutableList<ResultCached>>()
            //separate by specific library
            libraries.forEach { lib -> mapping[lib.id] = mutableListOf() }

            //get all novel's id saved in libraries
            val keys = getKeys(RESULT_BOOKMARK_STATE) ?: emptyList()
            for (key in keys) {
                //library id from this specific novel
                val type = getKey<Int>(key) ?: continue
                //novel id
                val id = key.replaceFirst(RESULT_BOOKMARK_STATE, RESULT_BOOKMARK)
                //get novel info
                val cached = getKey<ResultCached>(id) ?: continue
                mapping[type]?.add(cached)
            }

            val pagesList = mutableListOf<Page>()
            pagesList.add(getDownloadedCards())

            //sort library
            for (lib in libraries) {
                val items = mapping[lib.id] ?: mutableListOf()
                val sortedItems = sortNormalArray(items)

                pagesList.add(Page(lib.title, unsortedItems = ArrayList(items), items = sortedItems))
            }
            pagesList
        }

        _pages.value = pages
    }

    private suspend fun getDownloadedCards(): Page {
        val rawValues = cardsDataMutex.withLock {
            cardsData.values.toList()
        }

        val sorted = withContext(Dispatchers.Default) {
            sortArray(rawValues)
        }

        return Page(
            ReadType.NONE.name,
            unsortedItems = ArrayList(rawValues),
            items = sorted
        )
    }




    private suspend fun postCards() {
        _pages.value?.let { data ->
            val list = CopyOnWriteArrayList(data)
            if (list.isEmpty()) {
                list.add(getDownloadedCards())
            } else {
                list[0] = getDownloadedCards()
            }
            _pages.postValue(list)
        }
    }

    init {
        BookDownloader2.downloadDataChanged += ::progressDataChanged
        BookDownloader2.downloadProgressChanged += ::progressChanged
        BookDownloader2.downloadDataRefreshed += ::downloadDataRefreshed
        BookDownloader2.downloadRemoved += ::downloadRemoved
    }

    override fun onCleared() {
        super.onCleared()
        BookDownloader2.downloadProgressChanged -= ::progressChanged
        BookDownloader2.downloadDataChanged -= ::progressDataChanged
        BookDownloader2.downloadDataRefreshed -= ::downloadDataRefreshed
        BookDownloader2.downloadRemoved -= ::downloadRemoved
    }

    val activeRefreshTabs = mutableSetOf<Int>()
    val isRefreshing = MutableLiveData(false)
    private val _refresh = MutableSharedFlow<Int>(
        extraBufferCapacity = 32
    )
    val refresh = _refresh.asSharedFlow()
    fun setIsLoading(isActive: Boolean, currentTab: Int){
        isRefreshing.postValue(isActive)
        synchronized(activeRefreshTabs){
            if(isActive && !activeRefreshTabs.contains(currentTab))
                activeRefreshTabs.add(currentTab)
            else{
                _refresh.tryEmit(currentTab)
                activeRefreshTabs.remove(currentTab)
            }
        }
    }

    private val cardsDataMutex = Mutex()
    private val cardsData: HashMap<Int, DownloadFragment.DownloadDataLoaded> = hashMapOf()

    private fun progressChanged(data: Pair<Int, DownloadProgressState>) =
        viewModelScope.launchSafe {
            cardsDataMutex.withLock {
                val (id, state) = data
                val newState = state.eta(context ?: return@launchSafe)
                cardsData[id] = cardsData[id]?.copy(
                    downloadedCount = state.progress,
                    downloadedTotal = state.total,
                    state = state.state,
                    ETA = newState,
                ) ?: return@launchSafe
            }
            postCards()
        }

    private fun downloadRemoved(id: Int) = viewModelScope.launchSafe {
        cardsDataMutex.withLock {
            cardsData -= id
        }
        postCards()
    }

    private fun progressDataChanged(data: Pair<Int, DownloadFragment.DownloadData>) =
        viewModelScope.launchSafe {
            cardsDataMutex.withLock {
                val (id, value) = data
                cardsData[id] = cardsData[id]?.copy(
                    source = value.source,
                    name = value.name,
                    author = value.author,
                    posterUrl = value.posterUrl,
                    rating = value.rating,
                    peopleVoted = value.peopleVoted,
                    views = value.views,
                    synopsis = value.synopsis,
                    tags = value.tags,
                    apiName = value.apiName,
                    lastUpdated = value.lastUpdated,
                    lastDownloaded = value.lastDownloaded
                ) ?: run {
                    DownloadFragment.DownloadDataLoaded(
                        source = value.source,
                        name = value.name,
                        author = value.author,
                        posterUrl = value.posterUrl,
                        rating = value.rating,
                        peopleVoted = value.peopleVoted,
                        views = value.views,
                        synopsis = value.synopsis,
                        tags = value.tags,
                        apiName = value.apiName,
                        downloadedCount = 0,
                        downloadedTotal = 0,
                        ETA = "",
                        state = DownloadState.Nothing,
                        id = id,
                        generating = false,
                        lastUpdated = value.lastUpdated,
                        lastDownloaded = value.lastDownloaded,
                    )
                }
            }
            postCards()
        }

    suspend fun fetchAllData(postCard: Boolean) {
        downloadInfoMutex.withLock {
            cardsDataMutex.withLock {
                BookDownloader2.downloadData.map { (key, value) ->
                    val info = downloadProgress[key] ?: return@map
                    cardsData[key] = DownloadFragment.DownloadDataLoaded(
                        source = value.source,
                        name = value.name,
                        author = value.author,
                        posterUrl = value.posterUrl,
                        rating = value.rating,
                        peopleVoted = value.peopleVoted,
                        views = value.views,
                        synopsis = value.synopsis,
                        tags = value.tags,
                        apiName = value.apiName,
                        downloadedCount = info.progress,
                        downloadedTotal = info.total,
                        ETA = context?.let { ctx -> info.eta(ctx) } ?: "",
                        state = info.state,
                        id = key,
                        generating = false,
                        lastUpdated = value.lastUpdated,
                        lastDownloaded = value.lastDownloaded,
                    )
                }
            }
            if (postCard) postCards()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun downloadDataRefreshed(_id: Int) = viewModelScope.launchSafe {
        fetchAllData(true)
    }
}
