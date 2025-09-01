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
import java.text.SimpleDateFormat
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

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST
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
                SortOrder.POPULARITY -> "recommend"
                SortOrder.NEWEST -> "newest"
                else -> "recentDate"
            }
            append("&orders%5B%5D=$sortValue")
            // SỬA LỖI 400 TẠI ĐÂY: Mã hóa cứng "genres[]" thành "genres%5B%5D"
            filter.tags.forEach { append("&genres%5B%5D=${it.key}") }
            filter.states.forEach {
                val statusKey = when (it) {
                    MangaState.ONGOING -> "0"
                    MangaState.FINISHED -> "1"
                    else -> null
                }
                if (statusKey != null) append("&status[]=$statusKey")
            }
        }

        val json = webClient.httpGet(url).parseJson()
        val data = json.getJSONObject("result").getJSONArray("data")
        val mangaList = mutableListOf<Manga>()

        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val slug = item.optString("name_slug", null)
            if (slug.isNullOrBlank()) {
                continue
            }

            val mangaUrl = "/truyen/$slug"
            mangaList.add(
                Manga(
                    id = generateUid(mangaUrl),
                    title = item.getString("name"),
                    altTitles = item.optString("name_other", "").split(",").mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet(),
                    url = mangaUrl,
                    publicUrl = "https://$domain$mangaUrl",
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = "https://$domain${item.getString("image_poster")}",
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source
                )
            )
        }
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        enforceRateLimit()
        val response = webClient.httpGet(manga.publicUrl)
        val doc = response.parseHtml()
        val comicId = response.body!!.string().substringAfter("comic = {id:\"").substringBefore("\"")

        enforceRateLimit()
        val chapterApiUrl = "https://$domain/api/comic/$comicId/chapter?limit=-1"
        val chapterJson = webClient.httpGet(chapterApiUrl).parseJson()
        val chaptersData = chapterJson.getJSONObject("result").getJSONArray("chapters")
        val slug = manga.url.substringAfterLast("/")
        val chapters = List(chaptersData.length()) { i ->
            val item = chaptersData.getJSONObject(i)
            val number = item.getString("number")
            val chapterUrl = "/truyen/$slug/chuong-$number"
            MangaChapter(
                id = generateUid(chapterUrl),
                title = item.getString("name"),
                number = number.toFloatOrNull() ?: -1f,
                volume = 0,
                url = chapterUrl,
                scanlator = null,
                uploadDate = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).parse(item.getString("created_at"))?.time }.getOrNull() ?: 0L,
                branch = null,
                source = source
            )
        }

        val nameToIdMap = GTT_GENRES.associate { (name, id) -> name to id }
        val tagElements = doc.select(".group-content > .v-chip-link")
        val tags = tagElements.map { element ->
            val tagName = element.text()
            val tagId = nameToIdMap[tagName] ?: tagName
            MangaTag(key = tagId, title = tagName, source = source)
        }.toSet()

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
        val chapterJsonRaw = doc.selectFirst("script:contains(chapterJson:)")?.data()?.substringAfter("chapterJson: `")?.substringBefore("`")

        val imageUrls: List<String>
        if (chapterJsonRaw != null && chapterJsonRaw.isNotBlank()) {
            val json = JSONObject(chapterJsonRaw)
            val data = json.getJSONObject("body").getJSONObject("result").getJSONArray("data")
            imageUrls = List(data.length()) { i -> data.getString(i) }
        } else {
            val html = doc.html()
            val comicId = html.substringAfter("comic = {id:\"").substringBefore("\"")
            val (slug, chapterNumRaw) = chapter.url.substringAfter("/truyen/").split("/")
            val formBody = mapOf(
                "comicId" to comicId,
                "chapterNumber" to chapterNumRaw.substringAfter("chuong-"),
                "nameEn" to slug
            )
            val authHeaders = Headers.Builder()
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Authorization", TOKEN_KEY)
                .add("Referer", chapter.url.toAbsoluteUrl(domain))
                .build()

            val apiUrl = "https://$domain/api/chapter/auth".toHttpUrl()
            val response = webClient.httpPost(url = apiUrl, form = formBody, extraHeaders = authHeaders)
            val json = JSONObject(response.body!!.string())
            val data = json.getJSONObject("result").getJSONArray("data")
            imageUrls = List(data.length()) { i -> data.getString(i) }
        }

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
        "Action" to "2", "Adult" to "3", "Adventure" to "4", "Anime" to "5", "Chuyển Sinh" to "6",
        "Cổ Đại" to "7", "Comedy" to "8", "Comic" to "9", "Cooking" to "10", "Detective" to "11",
        "Doujinshi" to "12", "Drama" to "13", "Đam Mỹ" to "14", "Ecchi" to "15", "Fantasy" to "16",
        "Gender Bender" to "17", "Harem" to "18", "Historical" to "19", "Horror" to "20",
        "Huyền Huyễn" to "21", "Isekai" to "22", "Josei" to "23", "Live Action" to "24",
        "Magic" to "25", "Manhua" to "26", "Manhwa" to "27", "Martial Arts" to "28",
        "Mature" to "29", "Mecha" to "30", "Medical" to "31", "Military" to "32",
        "Mystery" to "33", "Ngôn Tình" to "34", "One shot" to "35", "Psychological" to "36",
        "Romance" to "37", "School Life" to "38", "Sci-fi" to "39", "Seinen" to "40",
        "Shoujo" to "41", "Shoujo Ai" to "42", "Shounen" to "43", "Shounen Ai" to "44",
        "Slice of Life" to "45", "Smut" to "46", "Soft Yaoi" to "47", "Soft Yuri" to "48",
        "Sports" to "49", "Supernatural" to "50", "Tạp chí truyện tranh" to "51",
        "Tragedy" to "52", "Trinh Thám" to "53", "Truyện scan" to "54", "Truyện Màu" to "55",
        "Việt Nam" to "56", "Webtoon" to "57", "Xuyên Không" to "58", "Yaoi" to "59", "Yuri" to "60"
    )
}
