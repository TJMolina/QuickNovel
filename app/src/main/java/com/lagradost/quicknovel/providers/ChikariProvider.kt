package com.lagradost.quicknovel.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.Score
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import java.net.URLEncoder

object ChikariProvider : MainAPI() {
    override val name = "Chikari"
    override val mainUrl = "https://chikari.moe"
    private val apiUrl = "$mainUrl/api"
    override val iconId = R.drawable.icon_chikari
    override val hasMainPage = true
    override val hasReviews = true
    override val lang = "en"

    override val orderBys = listOf(
        "Popular" to "popular",
        "Trending" to "trending",
        "Top Rated" to "top_rated",
        "Recently Updated" to "updated",
        "Recently Added" to "added",
        "Most Bookmarked" to "most_bookmarked"
    )

    override val tags = listOf(
        "Action" to "action",
        "Adventure" to "adventure",
        "Comedy" to "comedy",
        "Drama" to "drama",
        "Ecchi" to "ecchi",
        "Fantasy" to "fantasy",
        "Gender Bender" to "gender_bender",
        "Harem" to "harem",
        "Historical" to "historical",
        "Horror" to "horror",
        "Josei" to "josei",
        "Martial Arts" to "martial_arts",
        "Mature" to "mature",
        "Mecha" to "mecha",
        "Mystery" to "mystery",
        "Psychological" to "psychological",
        "Romance" to "romance",
        "School Life" to "school_life",
        "Sci-Fi" to "sci_fi",
        "Seinen" to "seinen",
        "Shoujo" to "shoujo",
        "Shoujo Ai" to "shoujo_ai",
        "Shounen" to "shounen",
        "Shounen Ai" to "shounen_ai",
        "Slice of Life" to "slice_of_life",
        "Sports" to "sports",
        "Supernatural" to "supernatural",
        "Tragedy" to "tragedy",
        "Yaoi" to "yaoi",
        "Yuri" to "yuri"
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val limit = 20
        val offset = (page - 1) * limit
        val url = "$apiUrl/series?type=novel&sort=$orderBy&limit=$limit&offset=$offset&genre=$tag"

        val root = app.get(url).parsed<ApiNovelsRoot>()

        val novels = root.items?.map { item ->
            newSearchResponse(
                name = item.title ?: "Unknown",
                url = "$mainUrl/novels/${item.slug}/"
            ) {
                posterUrl = item.cover ?: "https://cdn.chikari.moe/novels/${item.id}/cover.webp"
            }
        }.orEmpty()

        return HeadMainPageResponse(url, novels)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/novels/search?q=$encodedQuery&limit=50"
        val items = app.get(url).parsed<Array<ApiSearchItem>>()
        return items.map { item ->
            newSearchResponse(
                name = item.title ?: "Unknown",
                url = "$mainUrl/novels/${item.slug}/"
            ) {
                posterUrl = item.cover ?: "https://cdn.chikari.moe/novels/${item.id}/cover.webp"
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.removeSuffix("/").substringAfterLast("/")
        val data = app.get("$apiUrl/novels/$slug").parsed<ApiNovelDetails>()
        val chapters = loadAllChapters(slug)

        return newStreamResponse(data.title, url, chapters) {
            author = data.authors?.joinToString { it.name ?: "" }
            posterUrl = data.cover ?: "https://cdn.chikari.moe/novels/${data.id}/cover.webp"
            synopsis = data.description
            tags = (data.genres?.mapNotNull { it.name } ?: emptyList()) + (data.tags?.mapNotNull { it.name } ?: emptyList())
            setStatus(data.status)
            score = Score.from10(data.rating)
        }
    }

    private suspend fun loadAllChapters(slug: String): List<ChapterData> {
        val limit = 100
        val url = "$apiUrl/novels/$slug/chapters?limit=$limit&offset=0"
        val response = app.get(url).parsed<ApiChaptersRoot>()
        val chaptersTot = response.items?.getOrNull(0)?.number?.toInt() ?: return emptyList()

        return (1..chaptersTot).map { chNumber ->
            newChapterData(
                "Chapter $chNumber",
                "$apiUrl/novels/$slug/chapters/$chNumber/read"
            )
        }
    }


    override suspend fun loadHtml(url: String): String? {
        val res = app.get(url).parsed<ApiChapterItem>().body ?: return null
        return textToHtml(res)
    }

    private fun textToHtml(text: String): String {
        return text.trim().split(Regex("\n\n+"))
            .joinToString(separator = "") { "<p>${it.replace("\n", "<br>")}</p>" }
    }

    override suspend fun loadReviews(url: String, page: Int, data: String?): List<UserReview> {
        val slug = url.removeSuffix("/").substringAfterLast("/")
        val limit = 20
        val offset = (page - 1) * limit
        val response = app.get("$apiUrl/novels/$slug/comments?limit=$limit&offset=$offset&sort=new")
            .parsed<ApiCommentRoot>()

        return response.comments?.map { comment ->
            UserReview(
                review = comment.body,
                username = comment.author.username,
                date = comment.createdAt,
                avatarUrl = comment.author.avatarUrl
            )
        }.orEmpty()
    }

    data class ApiNovelsRoot(
        @JsonProperty("items") val items: List<ApiSearchItem>?,
        @JsonProperty("total") val total: Int
    )

    data class ApiSearchItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("cover_url") val cover: String?,
    )

    data class ApiNovelDetails(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String,
        @JsonProperty("authors") val authors: List<ApiAuthor>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("genres") val genres: List<ApiGenre>?,
        @JsonProperty("tags") val tags: List<ApiTag>?,
        @JsonProperty("rating") val rating: Float?,
        @JsonProperty("cover_url") val cover: String?,
    )

    data class ApiAuthor(@JsonProperty("name") val name: String?)
    data class ApiGenre(@JsonProperty("name") val name: String?)
    data class ApiTag(@JsonProperty("name") val name: String?)

    data class ApiChaptersRoot(
        @JsonProperty("items") val items: List<ApiChapterItem>?,
        @JsonProperty("total") val total: Int
    )

    data class ApiChapterItem(
        @JsonProperty("number") val number: Double,
        @JsonProperty("title") val title: String?,
        @JsonProperty("created_at") val createdAt: String?,
        @JsonProperty("body") val body: String?,
    )

    data class ApiCommentRoot(
        @JsonProperty("comments") val comments: List<ApiComment>?,
        @JsonProperty("total") val total: Int
    )

    data class ApiComment(
        @JsonProperty("id") val id: Int,
        @JsonProperty("author") val author: ApiCommentAuthor,
        @JsonProperty("body") val body: String,
        @JsonProperty("created_at") val createdAt: String
    )

    data class ApiCommentAuthor(
        @JsonProperty("username") val username: String,
        @JsonProperty("avatar_url") val avatarUrl: String?
    )
}
