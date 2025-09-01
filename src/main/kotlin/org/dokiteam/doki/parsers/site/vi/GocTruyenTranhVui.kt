package org.dokiteam.doki.parsers.site.vi

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.dokiteam.doki.network.GET
import org.dokiteam.doki.network.POST
import org.dokiteam.doki.network.bodyAs
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import java.util.EnumSet

@MangaSourceParser("GOCTRUYENTRANHVUI", "Goc Truyen Tranh Vui", "vi")
internal class GocTruyenTranhVui(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.GOCTRUYENTRANHVUI, 50) {

    override val configKeyDomain = ConfigKey.Domain("goctruyentranhvui17.com")
    private val apiUrl by lazy { "https://$domain/api/v2" }

    companion object {
        private const val REQUEST_DELAY_MS = 350L // ~3 requests per second
        private const val TOKEN_KEY = "Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJBbG9uZSBGb3JldmVyIiwiY29taWNJZHMiOltdLCJyb2xlSWQiOm51bGwsImdyb3VwSWQiOm51bGwsImFkbWluIjpmYWxzZSwicmFuayI6MCwicGVybWlzc2lvbiI6W10sImlkIjoiMDAwMTA4NDQyNSIsInRlYW0iOmZhbHNlLCJpYXQiOjE3NTM2OTgyOTAsImVtYWlsIjoibnVsbCJ9.HT080LGjvzfh6XAPmdDZhf5vhnzUhXI4GU8U6tzwlnXWjgMO4VdYL1_jsSFWd-s3NBGt-OAt89XnzaQ03iqDyA"
    }

    private val requestMutex = Mutex()
    private var lastRequestTime = 0L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

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
                else -> "recentDate" // UPDATED
            }
            append("&orders[]=$sortValue")

            filter.tags.forEach {
                append("&genres[]=${it.key}")
            }

            filter.states.forEach {
                val statusKey = when (it) {
                    MangaState.ONGOING -> "0"
                    MangaState.FINISHED -> "1"
                    else -> null
                }
                if (statusKey != null) {
                    append("&status[]=$statusKey")
                }
            }
        }

        val response = webClient.httpGet(url).bodyAs<String>()
        val result = json.decodeFromString<ResultDto<ListingDto>>(response)
        return result.result.data.map { it.toManga(source) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return coroutineScope {
            val (comicId, slug) = manga.id.split(":")
            
            val detailsJob = async {
                enforceRateLimit()
                val doc = webClient.httpGet("https://$domain/truyen/$slug").parseHtml()
                manga.copy(
                    title = doc.selectFirst(".v-card-title")?.text().orEmpty(),
                    tags = doc.select(".group-content > .v-chip-link").mapToSet {
                        MangaTag(it.text(), it.text(), source)
                    },
                    coverUrl = doc.selectFirst("img.image")?.absUrl("src"),
                    state = when (doc.selectFirst(".mb-1:contains(Trạng thái:) span")?.text()) {
                        "Đang thực hiện" -> MangaState.ONGOING
                        "Hoàn thành" -> MangaState.FINISHED
                        else -> null
                    },
                    authors = setOfNotNull(doc.selectFirst(".mb-1:contains(Tác giả:) span")?.text()),
                    description = doc.selectFirst(".v-card-text")?.text()
                )
            }

            val chaptersJob = async {
                enforceRateLimit()
                val url = "https://$domain/api/comic/$comicId/chapter?limit=-1"
                val response = webClient.httpGet(url).bodyAs<String>()
                val chapterJson = json.decodeFromString<ResultDto<ChapterListDto>>(response)
                chapterJson.result.chapters.map { it.toMangaChapter(slug, source) }
            }
            
            val (detailedManga, chapters) = awaitAll(detailsJob, chaptersJob)
            (detailedManga as Manga).copy(chapters = chapters as List<MangaChapter>)
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        enforceRateLimit()
        
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val chapterJsonRaw = doc.selectFirst("script:contains(chapterJson:)")
            ?.data()
            ?.substringAfter("chapterJson: `")
            ?.substringBefore("`")
            ?: throw Exception("Could not find chapter JSON")

        val imageUrls: List<String> = if (chapterJsonRaw.isNotBlank()) {
            json.decodeFromString<ImageListWrapper>(chapterJsonRaw).body.result.data
        } else {
            // Auth required
            val html = doc.html()
            val comicId = html.substringAfter("id: \"").substringBefore("\"")
            val (slug, chapterNum) = chapter.url.substringAfter("/truyen/").split("/")
            
            val formBody = mapOf(
                "comicId" to comicId,
                "chapterNumber" to chapterNum.substringAfter("chuong-"),
                "nameEn" to slug
            )

            val authHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Authorization" to TOKEN_KEY,
                "Referer" to chapter.url.toAbsoluteUrl(domain)
            )

            val response = webClient.httpPost("https://$domain/api/chapter/auth", body = formBody, headers = authHeaders).bodyAs<String>()
            json.decodeFromString<ResultDto<ImageListDto>>(response).result.data
        }
        
        return imageUrls.mapIndexed { index, url ->
            val finalUrl = if (url.startsWith("/image/")) "https://$domain$url" else url
            MangaPage(
                id = generateUid(finalUrl),
                url = finalUrl,
                preview = null,
                source = source,
                pageNumber = index
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

    // Data classes for JSON parsing
    @Serializable
    data class ResultDto<T>(val result: T)

    @Serializable
    data class ListingDto(
        val next: Boolean = false,
        val data: List<MangaItemDto>
    )

    @Serializable
    data class MangaItemDto(
        val id: String,
        @SerialName("name_slug") val nameSlug: String,
        @SerialName("name_other") val nameOther: String? = null,
        val name: String,
        @SerialName("image_poster") val imagePoster: String,
        @SerialName("image_avatar") val imageAvatar: String
    ) {
        fun toManga(source: MangaSource) = Manga(
            id = "$id:$nameSlug",
            title = name,
            altTitles = nameOther?.split(", ")?.mapNotNull { it.trim().takeIf(String::isNotBlank) }?.toSet() ?: emptySet(),
            url = "/truyen/$nameSlug",
            publicUrl = "https://goctruyentranhvui17.com/truyen/$nameSlug",
            coverUrl = "https://goctruyentranhvui17.com$imagePoster",
            source = source
        )
    }

    @Serializable
    data class ChapterListDto(val chapters: List<ChapterItemDto>)

    @Serializable
    data class ChapterItemDto(
        val id: String,
        val name: String,
        val number: String,
        @SerialName("created_at") val createdAt: String
    ) {
        fun toMangaChapter(slug: String, source: MangaSource): MangaChapter {
            val url = "/truyen/$slug/chuong-$number"
            return MangaChapter(
                id = generateUid(url),
                title = name,
                url = url,
                number = number.toFloatOrNull() ?: -1f,
                uploadDate = runCatching {
                    // Assuming format is like "2024-05-21 00:00:00"
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).parse(createdAt)?.time
                }.getOrNull(),
                source = source
            )
        }
    }

    @Serializable
    data class ImageListWrapper(val body: ResultDto<ImageListDto>)

    @Serializable
    data class ImageListDto(val data: List<String>)
    
    // Static data
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
