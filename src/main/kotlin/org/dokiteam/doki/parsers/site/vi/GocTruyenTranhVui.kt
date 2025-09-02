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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.*

@MangaSourceParser("GOCTRUYENTRANHVUI", "Goc Truyen Tranh Vui", "vi")
internal class GocTruyenTranhVui(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.GOCTRUYENTRANHVUI, 50) {

    override val configKeyDomain = ConfigKey.Domain("goctruyentranhvui17.com")
    private val apiUrl by lazy { "https://$domain/api/v2" }

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

    // A map to convert genre names back to API codes for filtering
    private val genreNameToCodeMap by lazy { GTT_GENRES.associate { it.first to it.second } }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        // FIX: Swap key and title to fix display and filtering issues in the app.
        // The app seems to display the 'key', so we put the full name there.
        availableTags = GTT_GENRES.mapToSet { MangaTag(key = it.first, title = it.second, source = source) },
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

            // FIX: Convert the full genre name (received in filter.tags.key) back to its code for the API call.
            filter.tags.forEach {
                val genreCode = genreNameToCodeMap[it.key]
                if (genreCode != null) {
                    append("&categories%5B%5D=$genreCode")
                }
            }

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
                (0 until arr.length()).mapNotNullTo(mutableSetOf()) { tagName ->
                    // Find the corresponding tag from our full list to create the MangaTag object
                    GTT_GENRES.find { it.first == tagName }?.let {
                        MangaTag(key = it.first, title = it.second, source = source)
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
                    url = chapterUrl,
                    uploadDate = item.optLong("updateTime", 0L),
                    source = source
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

        enforceRateLimit()
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        val tags = doc.select(".group-content > .v-chip-link").mapNotNullTo(mutableSetOf()) { el ->
            GTT_GENRES.find { it.first == el.text() }?.let {
                MangaTag(key = it.first, title = it.second, source = source)
            }
        }

        return manga.copy(
            title = doc.selectFirst(".v-card-title")?.text().orEmpty(),
            tags = tags.ifEmpty { manga.tags },
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
        val response = webClient.httpGet(chapter.url.toAbsoluteUrl(domain))
        val responseBody = response.body?.string() ?: throw Exception("Response body is null")
        
        val chapterJsonRaw = responseBody.substringAfter("chapterJson: `", "").substringBefore("`", "")

        val imageUrls: List<String>
        if (chapterJsonRaw.isNotBlank()) {
            val json = JSONObject(chapterJsonRaw)
            val data = json.getJSONObject("body").getJSONObject("result").getJSONArray("data")
            imageUrls = List(data.length()) { i -> data.getString(i) }
        } else {
            // FALLBACK: Image list is empty, call authenticated API
            val comicId = responseBody.substringAfter("comic = {id:\"", "").substringBefore("\"", "")
            val chapterNumber = chapter.url.substringAfterLast("chuong-")
            val nameEn = chapter.url.substringAfter("/truyen/").substringBefore("/chuong-")

            if (comicId.isBlank()) throw Exception("Cannot find comicId for fallback image request")

            val formBody = mapOf(
                "comicId" to comicId,
                "chapterNumber" to chapterNumber,
                "nameEn" to nameEn
            )
            val authResponse = webClient.httpPost("$apiUrl/chapter/auth", extraHeaders = apiHeaders, formBody = formBody).parseJson()
            val data = authResponse.getJSONObject("result").getJSONArray("data")
            imageUrls = List(data.length()) { i -> data.getString(i) }
        }

        return imageUrls.map { url ->
            val finalUrl = if (url.startsWith("/image/")) "https://$domain$url" else url
            MangaPage(id = generateUid(finalUrl), url = finalUrl, source = source)
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
        "Anime" to "ANI", "Drama" to "DRA", "Josei" to "JOS", "Manhwa" to "MAW",
        "One Shot" to "OSH", "Shounen" to "SHO", "Webtoons" to "WEB", "Shoujo" to "SHJ",
        "Harem" to "HAR", "Ecchi" to "ECC", "Mature" to "MAT", "Slice of life" to "SOL",
        "Isekai" to "ISE", "Manga" to "MAG", "Manhua" to "MAU", "Hành Động" to "ACT",
        "Phiêu Lưu" to "ADV", "Hài Hước" to "COM", "Võ Thuật" to "MAA", "Huyền Bí" to "MYS",
        "Lãng Mạn" to "ROM", "Thể Thao" to "SPO", "Học Đường" to "SCL", "Lịch Sử" to "HIS",
        "Kinh Dị" to "HOR", "Siêu Nhiên" to "SUN", "Bi Kịch" to "TRA", "Trùng Sinh" to "RED",
        "Game" to "GAM", "Viễn Tưởng" to "FTS", "Khoa Học" to "SCF", "Truyện Màu" to "COI",
        "Người Lớn" to "ADU", "BoyLove" to "BBL", "Hầm Ngục" to "DUN", "Săn Bắn" to "HUNT",
        "Ngôn Từ Nhạy Cảm" to "NTNC", "Doujinshi" to "DOU", "Bạo Lực" to "BLM", "Ngôn Tình" to "NTT",
        "Nữ Cường" to "NCT", "Gender Bender" to "GDB", "Murim" to "MRR", "Leo Tháp" to "LTT",
        "Nấu Ăn" to "COO"
    )
}
