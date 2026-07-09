package com.lagradost.quicknovel.ui.updates.data

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.util.ResultCached

/**
 * A watch-list record saved under [com.lagradost.quicknovel.NOVEL_WATCH_FOLDER].
 *
 * [baselineChapters]      – chapters when added (or after "mark as seen").
 * [lastCheckedChapters]   – chapters found in the last online check (0 = never).
 * [lastCheckedMs]         – epoch-ms timestamp of the last check (0 = never).
 * [checkFailed]           – true if the last check failed due to network or parser.
 */
data class WatchEntry(
    @JsonProperty("novelId")             val novelId: Int = 0,
    @JsonProperty("novelSource")         val novelSource: String = "",
    @JsonProperty("apiName")             val apiName: String = "",
    @JsonProperty("novelName")           val novelName: String = "",
    @JsonProperty("posterUrl")           val posterUrl: String? = null,
    @JsonProperty("baselineChapters")    val baselineChapters: Int = 0,
    @JsonProperty("lastCheckedChapters") val lastCheckedChapters: Int = 0,
    @JsonProperty("lastCheckedMs")       val lastCheckedMs: Long = 0L,
    @JsonProperty("checkFailed")         val checkFailed: Boolean = false,
    @JsonProperty("isPermanent") val isPermanent: Boolean = true,
) {
    /** New chapters since the baseline. */
    val newChapters: Int get() = (lastCheckedChapters - baselineChapters).coerceAtLeast(0)
    val hasUpdate: Boolean get() = lastCheckedMs > 0 && newChapters > 0
    val neverChecked: Boolean get() = lastCheckedMs == 0L

    companion object {
        fun fromCached(cached: ResultCached) = WatchEntry(
            novelId          = cached.id,
            novelSource      = cached.source,
            apiName          = cached.apiName,
            novelName        = cached.name,
            posterUrl        = cached.poster,
            baselineChapters = cached.totalChapters,
        )
        fun fromWatchEntry(entry: WatchEntry) = ResultCached(
            id = entry.novelId,
            source = entry.novelSource,
            apiName = entry.apiName,
            name = entry.novelName,
            poster = entry.posterUrl,
            totalChapters = entry.baselineChapters,
            author = null,
            tags = null,
            rating = null,
            cachedTime = 0L
        )
    }
}