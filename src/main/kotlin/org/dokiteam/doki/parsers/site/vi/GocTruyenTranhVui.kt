package org.dokiteam.doki.parsers.site.vi

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.*

@MangaSourceParser("GOCTRUYENTRANHVUI", "Goc Truyen Tranh Vui", "vi")
internal class GocTruyenTranhVui(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.GOCTRUYENTRANHVUI, 50) {

    override val configKeyDomain = ConfigKey.Domain("goctruyentranhvui17.com")
    private val apiUrl by lazy { "https://$domain/api/v2" }

    companion object {
        private const val REQUEST_DELAY_MS = 350L
        private const val TOKEN_KEY = "Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJBbG9uZSBGb3JldmVyIiwiY29taWNJZHMiOltdLCJyb2xlSWQiOm51bGwsImdyb3VwSWQiOm51bGwsImFkbWluIjpmYWxzZSwicmFuayI6MCwicGVybWlzc2lvbiI6W10sImlkIjoiMDAwMTA4NDQyNSIsInRlYW0iOmZhbHNlLCJpYXQiOjE3NTM2OTgyOTAsImVtYWlsIjoibnVsbCJ9.HT080LGjvzfh6XAPmdDZhf5vhnzUhXI4GU8U6tzwlnXWjgMO4VdYL1_jsSFWd-s3NBGt-OAt89XnzaQ03iqDyA"
    }

    private val requestMutex = Mutex()
    private var lastRequestTime = 0L

    private val apiHeaders by lazy {
        Headers.Builder()
            .add("Authorization", TOKEN_KEY)
            .add("Referer", "https://$domain/")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = GTT_GENRES.mapToSet { MangaTag(it.second, it.first, source) },
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED)
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        enforceRateLimit()
        val url = buildString {
            append(apiUrl)
            append("/search?p=${page - 1}")
            if (!filter.query.isNullOrBlank()) {
                append("&searchValue=${filter.query.urlEncoded()}")
            }

            val sortValue = when (order) {
                SortOrder.POPULARITY -> "viewCount"
                SortOrder.NEWEST -> "createdAt"
                SortOrder.RATING -> "evaluationScore"
                else -> "recentDate" // UPDATED
            }
            append("&orders%5B%5D=$sortValue")

            filter.tags.forEach { append("&categories%5B%5D=${it.key}") }

            filter.states.forEach {
                val statusKey = when (it) {
                    MangaState.ONGOING -> "PRG"
                    MangaState.FINISHED -> "END"
                    else -> null
                }
                if (statusKey != null) append("&status%5B%5D=$statusKey")
            }
        }

        val json = webClient.httpGet(url, extraHeaders = apiHeaders).parseJson()
        val result = json.optJSONObject("result") ?: return emptyList()
        val data = result.optJSONArray("data") ?: return emptyList()

        return List(data.length()) { i ->
            val item = data.getJSONObject(i)
            val slug = item.getString("nameEn")
            val mangaUrl = "/truyen/$slug"

            val categoryNames = item.optJSONArray("category")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()

            val categoryCodes = item.optJSONArray("categoryCode")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()

            val tags = categoryNames.zip(categoryCodes).map { (name, code) ->
                MangaTag(key = code, title = name, source = source)
            }.toSet()

            Manga(
                id = generateUid(mangaUrl),
                title = item.getString("name"),
                altTitles = item.optString("otherName", "").split(",").mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet(),
                url = mangaUrl,
                publicUrl = "https://$domain$mangaUrl",
                rating = item.optDouble("evaluationScore", 0.0).toFloat(),
                contentRating = null,
                coverUrl = "https://$domain${item.getString("photo")}",
                tags = tags,
                state = when (item.optString("statusCode")) {
                    "PRG" -> MangaState.ONGOING
                    "END" -> MangaState.FINISHED
                    else -> null
                },
                authors = setOf(item.optString("author", "Updating")),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        enforceRateLimit()
        val responseBody = webClient.httpGet(manga.publicUrl).body!!.string()
        val doc = Jsoup.parse(responseBody)
        val comicId = responseBody.substringAfter("comic = {id:\"").substringBefore("\"")

        enforceRateLimit()
        val chapterApiUrl = "https://$domain/api/comic/$comicId/chapter?limit=-1"
        val chapterJson = webClient.httpGet(chapterApiUrl, extraHeaders = apiHeaders).parseJson()
        val chaptersData = chapterJson.getJSONObject("result").getJSONArray("chapters")
        val slug = manga.url.substringAfterLast("/")
        val chapters = List(chaptersData.length()) { i ->
            val item = chaptersData.getJSONObject(i)
            val number = item.getString("numberChapter")
            val name = item.getString("name")
            val chapterUrl = "/truyen/$slug/chuong-$number"
            MangaChapter(
                id = generateUid(chapterUrl),
                title = if (name != "N/A") name else "Chapter $number",
                number = number.toFloatOrNull() ?: -1f,
                volume = 0,
                url = chapterUrl,
                scanlator = null,
                uploadDate = item.optLong("updateTime", 0L),
                branch = null,
                source = source
            )
        }

        val nameToIdMap = GTT_GENRES.associate { (name, id) -> name to id }
        val tagElements = doc.select(".group-content > .v-chip-link")
        val tags = mutableSetOf<MangaTag>()
        for (element in tagElements) {
            val tagName = element.text()
            val tagId = nameToIdMap[tagName] ?: tagName
            tags.add(MangaTag(key = tagId, title = tagName, source = source))
        }

        return manga.copy(
            title = doc.selectFirst(".v-card-title")?.text().orEmpty(),
            tags = tags,
            coverUrl = doc.selectFirst("img.image")?.absUrl("src"),
            state = when (doc.selectFirst(".mb-1:contains(Trạng thái:) span")?.text()) {
                "Đang thực hiện" -> MangaState.ONGOING
                "Hoàn thành" -> MangaState.FINISHED
                else -> null
            },
            authors = setOfNotNull(doc.selectFirst(".mb-1:contains(Tác giả:) span")?.text()),
            description = doc.selectFirst(".v-card-text")?.text(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        enforceRateLimit()
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val scriptContent = doc.selectFirst("script:contains(chapterJson:)")?.data()
            ?: throw Exception("Không tìm thấy script chứa thông tin chapter")

        val chapterJsonRaw = scriptContent.substringAfter("chapterJson: `").substringBefore("`")

        if (chapterJsonRaw.isBlank()) {
            throw Exception("Trang web không nhúng sẵn danh sách ảnh. Có thể cần cập nhật lại parser.")
        }

        val json = JSONObject(chapterJsonRaw)
        val data = json.getJSONObject("body").getJSONObject("result").getJSONArray("data")
        val imageUrls = List(data.length()) { i -> data.getString(i) }

        return imageUrls.map { url ->
            val finalUrl = if (url.startsWith("/image/")) "https://$domain$url" else url
            MangaPage(
                id = generateUid(finalUrl),
                url = finalUrl,
                preview = null,
                source = source
            )
        }
    }

    private suspend fun enforceRateLimit() {
        requestMutex.withLock {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastRequest = currentTime - lastRequestTime
            if (timeSinceLastRequest < REQUEST_DELAY_MS) {
                delay(REQUEST_DELAY_MS - timeSinceLastRequest)
            }
            lastRequestTime = System.currentTimeMillis()
        }
    }
    
    private val GTT_GENRES = listOf(
        "Action" to "ACT", "Adult" to "ADU", "Adventure" to "ADV", "Anime" to "ANI",
        "Chuyển Sinh" to "RED", "Cổ Đại" to "HIS", "Comedy" to "COM", "Comic" to "CMC",
        "Cooking" to "COO", "Doujinshi" to "DOU", "Drama" to "DRA", "Ecchi" to "ECC",
        "Fantasy" to "FTS", "Gender Bender" to "GDB", "Harem" to "HAR", "Historical" to "HIS",
        "Horror" to "HOR", "Huyền Huyễn" to "MYS", "Isekai" to "ISE", "Josei" to "JOS",
        "Live Action" to "LIA", "Magic" to "MAG", "Manhua" to "MAU", "Manhwa" to "MAW",
        "Martial Arts" to "MAA", "Mature" to "MAT", "Mystery" to "MYS", "Ngôn Tình" to "ROM",
        "One shot" to "OSH", "Romance" to "ROM", "School Life" to "SCL", "Sci-fi" to "SCF",
        "Shoujo" to "SHJ", "Shounen" to "SHO", "Slice of Life" to "SOL", "Sports" to "SPO",
        "Supernatural" to "SUN", "Tragedy" to "TRA", "Truyện Màu" to "COI", "Webtoon" to "WEB"
    )
}
