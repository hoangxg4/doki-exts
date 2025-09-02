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
    // FIX: Image domain is different from the main domain
    private val imageDomain = "https://goctruyentranh2.pro"

    companion object {
        private const val REQUEST_DELAY_MS = 350L
        private const val TOKEN_KEY = "Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJBbG9uZSBGb3JldmVyIiwiY29taWNJZHMiOltdLCJyb2xlSWQiOm51bGwsImdyb3VwSWQiOm51bGwsImFkbWluIjpmYWxzZSwicmFuayI6MCwicGVybWlzc2lvbiI6W10sImlkIjoiMDAwMTA4NDQyNSIsInRlYW0iOmZhbHNlLCJpYXQiOjE3NTM2OTgyOTAsImVtYWlsIjoibnVsbCJ9.HT080LGjvzfh6XAPmdDZhf5vhnzUhXI4GU8U6tzwlnXWjgMO4VdYL1jsSFWd-s3NBGt-OAt89XnzaQ03iqDyA"
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
        availableTags = GTT_GENRES.map { MangaTag(key = it.second, title = it.first, source = source) }.distinctBy { it.key }.toSet(),
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
            val comicId = item.getString("id")
            val slug = item.getString("nameEn")
            val mangaUrl = "/truyen/$slug"
            val tags = item.optJSONArray("category")?.let { arr ->
                (0 until arr.length()).mapNotNullTo(mutableSetOf()) { index ->
                    val tagName = arr.getString(index)
                    GTT_GENRES.find { it.first.equals(tagName, ignoreCase = true) }?.let { genrePair ->
                        MangaTag(key = genrePair.second, title = genrePair.first, source = source)
                    }
                }
            } ?: emptySet()

            Manga(
                id = generateUid(comicId),
                title = item.getString("name"),
                altTitles = item.optString("otherName", "").split(",").mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet(),
                url = comicId,
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
        val comicId = manga.url
        val slug = manga.publicUrl.substringAfterLast("/")

        val chapters = try {
            enforceRateLimit()
            val chapterApiUrl = "https://$domain/api/comic/$comicId/chapter?limit=-1"
            val chapterJson = webClient.httpGet(chapterApiUrl, extraHeaders = apiHeaders).parseJson()
            val chaptersData = chapterJson.getJSONObject("result").getJSONArray("chapters")

            List(chaptersData.length()) { i ->
                val item = chaptersData.getJSONObject(i)
                val number = item.getString("numberChapter")
                val name = item.getString("name")
                val chapterUrl = "/truyen/$slug/chuong-$number"
                MangaChapter(
                    id = generateUid(chapterUrl),
                    title = if (name != "N/A" && name.isNotBlank()) name else "Chapter $number",
                    number = number.toFloatOrNull() ?: -1f,
                    volume = 0,
                    url = chapterUrl,
                    scanlator = null,
                    uploadDate = item.optLong("updateTime", 0L),
                    branch = null,
                    source = source
                )
            }
        } catch (e: Exception) {
            emptyList()
        }.reversed() // FIX: Reverse chapter list to show from oldest to newest

        enforceRateLimit()
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        
        val detailTags = doc.select(".group-content > .v-chip-link").mapNotNullTo(mutableSetOf()) { el ->
            GTT_GENRES.find { it.first.equals(el.text(), ignoreCase = true) }?.let {
                MangaTag(key = it.second, title = it.first, source = source)
            }
        }

        return manga.copy(
            title = doc.selectFirst(".v-card-title")?.text().orEmpty(),
            tags = manga.tags + detailTags,
            coverUrl = doc.selectFirst("img.image")?.absUrl("src"),
            state = when (doc.selectFirst(".mb-1:contains(Trạng thái:) span")?.text()) {
                "Đang thực hiện" -> MangaState.ONGOING
                "Hoàn thành" -> MangaState.FINISHED
                else -> manga.state
            },
            authors = setOfNotNull(doc.selectFirst(".mb-1:contains(Tác giả:) span")?.text()),
            description = doc.selectFirst(".v-card-text")?.text(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        enforceRateLimit()
        val responseBody = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).body?.string() 
            ?: throw Exception("Response body is null for chapter page")

        val scriptContent = Jsoup.parse(responseBody).selectFirst("script:contains(chapterJson:)")?.data()
            ?: throw Exception("Could not find script with chapterJson")
        
        val chapterJsonRaw = scriptContent.substringAfter("chapterJson: `", "").substringBefore("`", "")
        if (chapterJsonRaw.isBlank()) {
            throw Exception("chapterJson is blank, cannot load images.")
        }

        val json = JSONObject(chapterJsonRaw)
        val data = json.getJSONObject("body").getJSONObject("result").getJSONArray("data")
        val imageUrls = List(data.length()) { i -> data.getString(i) }

        return imageUrls.map { url ->
            // FIX: Use the correct image domain
            val finalUrl = if (url.startsWith("/image/")) "$imageDomain$url" else url
            MangaPage(id = generateUid(finalUrl), url = finalUrl, preview = null, source = source)
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

    // UPDATE: Comprehensive genre list with aliases for better matching
    private val GTT_GENRES = listOf(
        "Action" to "ACT", "Hành Động" to "ACT",
        "Adult" to "ADU", "Người Lớn" to "ADU",
        "Adventure" to "ADV", "Phiêu Lưu" to "ADV",
        "Anime" to "ANI",
        "BoyLove" to "BBL",
        "Comedy" to "COM", "Hài Hước" to "COM",
        "Cooking" to "COO", "Nấu Ăn" to "COO",
        "Doujinshi" to "DOU",
        "Drama" to "DRA",
        "Ecchi" to "ECC",
        "Fantasy" to "FTS", "Viễn Tưởng" to "FTS",
        "Game" to "GAM",
        "Gender Bender" to "GDB",
        "Harem" to "HAR",
        "Historical" to "HIS", "Lịch Sử" to "HIS",
        "Horror" to "HOR", "Kinh Dị" to "HOR",
        "Isekai" to "ISE",
        "Josei" to "JOS",
        "Manga" to "MAG",
        "Manhua" to "MAU",
        "Manhwa" to "MAW",
        "Martial Arts" to "MAA", "Võ Thuật" to "MAA",
        "Mature" to "MAT",
        "Murim" to "MRR",
        "Mystery" to "MYS", "Huyền Bí" to "MYS",
        "Nữ Cường" to "NCT",
        "One Shot" to "OSH",
        "Romance" to "ROM", "Lãng Mạn" to "ROM", "Ngôn Tình" to "NTT",
        "School Life" to "SCL", "Học Đường" to "SCL",
        "Sci-fi" to "SCF", "Khoa Học" to "SCF",
        "Shoujo" to "SHJ",
        "Shounen" to "SHO",
        "Slice of life" to "SOL",
        "Sports" to "SPO", "Thể Thao" to "SPO",
        "Supernatural" to "SUN", "Siêu Nhiên" to "SUN",
        "Tragedy" to "TRA", "Bi Kịch" to "TRA",
        "Webtoons" to "WEB",
        "Chuyển Sinh" to "RED", "Trùng Sinh" to "RED",
        "Truyện Màu" to "COI",
        "Hầm Ngục" to "DUN",
        "Săn Bắn" to "HUNT",
        "Ngôn Từ Nhạy Cảm" to "NTNC",
        "Bạo Lực" to "BLM",
        "Leo Tháp" to "LTT"
    )
}
