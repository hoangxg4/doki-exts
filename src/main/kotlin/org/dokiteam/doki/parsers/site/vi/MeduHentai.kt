package org.dokiteam.doki.parsers.site.vi

import androidx.collection.ArrayMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaParserAuthProvider
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.AbstractMangaParser
import org.dokiteam.doki.parsers.exception.AuthRequiredException
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import okhttp3.Headers.Companion.toHeaders

@MangaSourceParser("MEDUHENTAI", "MeduHentai", "vi", type = ContentType.HENTAI)
internal class MeduHentaiParser(context: MangaLoaderContext) :
    AbstractMangaParser(context, MangaParserSource.MEDUHENTAI), MangaParserAuthProvider {

    companion object {
        // SỬA MỚI: Xóa PLACEHOLDER_IMAGE_URL
        private const val MANGA_PER_PAGE = 24
    }

    // --- DATA CLASSES ---

    @Serializable
    private data class ApiResponse(
        val mangas: List<MangaListItem> = emptyList(),
        val pagination: Pagination? = null,
        @SerialName("manga") val manga: MangaDetails? = null
    )

    @Serializable
    private data class MangaListItem(
        @SerialName("_id") val id: String,
        val title: String,
        val description: String? = null,
        val coverImage: String?,
        val author: String? = null,
        val genres: List<String> = emptyList(),
        val updatedAt: String? = null,
        val latestChapterUpdate: String? = null,
        val likes: Int? = null
    )

    @Serializable
    private data class MangaDetails(
        @SerialName("_id") val id: String,
        val title: String,
        val alternativeTitles: List<String> = emptyList(),
        val coverImage: String?,
        val description: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val genres: List<String> = emptyList(),
        val chapters: List<ChapterItem> = emptyList(),
        val userId: Uploader? = null,
        val status: String? = null
    )
    
    @Serializable
    private data class ChapterItem(
        @SerialName("_id") val id: String,
        val title: String,
        val chapterNumber: Int,
        val createdAt: String? = null,
        val pages: List<PageItem> = emptyList()
    )

    @Serializable
    private data class Uploader(
        @SerialName("_id") val id: String, 
        val username: String
    )

    @Serializable
    private data class PageItem(
        val pageNumber: Int,
        val imageUrl: String
    )

    @Serializable
    private data class Pagination(
        val currentPage: Int,
        val totalPages: Int,
        val totalItems: Int,
        val hasNextPage: Boolean
    )
    
    @Serializable
    private data class AuthSession(val user: User? = null)
    
    @Serializable
    private data class User(
        val id: String,
        val username: String,
        val email: String? = null
    )

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("meduhentai.com")
    private val json = Json { ignoreUnknownKeys = true }

    // --- MangaParserAuthProvider ---
    override val authUrl: String
        get() = domain

    override suspend fun isAuthorized(): Boolean =
        context.cookieJar.getCookies(domain).any { 
            it.name.startsWith("__Secure-authjs") || it.name.startsWith("__Host-authjs")
        }

    override suspend fun getUsername(): String {
        try {
            val response = webClient.httpGet("/api/auth/session".toAbsoluteUrl(domain))
            if (response.isSuccessful) {
                val sessionJson = response.body!!.string()
                val session = json.decodeFromString<AuthSession>(sessionJson)
                return session.user?.username
                    ?: session.user?.email
                    ?: throw IllegalStateException("User not found in session")
            } else {
                throw IllegalStateException("Failed to get user info: ${response.code}")
            }
        } catch (e: Exception) {
            throw AuthRequiredException(source, e)
        }
    }

    // --- CÁC HÀM PARSER CƠ BẢN ---
    override suspend fun getFavicons(): Favicons = Favicons(
        listOf(Favicon("https://meduhentai.com/medusa.ico", 32, null)),
        domain
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.NEWEST
    )

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = false,
        isSearchSupported = true
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = getOrCreateTagMap().values.toSet()
        )
    }

    override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val page = (offset / MANGA_PER_PAGE.toFloat()).toIntUp() + 1
        
        val apiUrl = buildString {
            append("/api/manga?")
            when {
                !filter.query.isNullOrEmpty() -> {
                    append("q=${filter.query.urlEncoded()}")
                }
                filter.tags.isNotEmpty() -> {
                    val genreKey = filter.tags.first().key.urlEncoded()
                    append("genre=$genreKey")
                }
                else -> {
                    when (order) {
                        SortOrder.NEWEST, SortOrder.UPDATED -> append("sortBy=latestChapter&sortOrder=desc")
                        SortOrder.POPULARITY -> append("sort=popular")
                        SortOrder.RATING -> append("sortBy=likes&sortOrder=desc")
                        else -> append("sortBy=latestChapter&sortOrder=desc")
                    }
                }
            }
            append("&page=$page&limit=$MANGA_PER_PAGE")
        }.toAbsoluteUrl(domain)

        val responseJson = webClient.httpGet(apiUrl).body!!.string()
        val apiResponse = json.decodeFromString<ApiResponse>(responseJson)
        val tagMap = getOrCreateTagMap()
        
        return apiResponse.mangas.map { item ->
            // SỬA MỚI: Gán 'null' nếu rỗng
            val finalCoverUrl = item.coverImage?.takeIf { it.isNotBlank() }
            
            Manga(
                id = generateUid(item.id),
                title = item.title,
                url = "/manga/${item.id}",
                publicUrl = "/manga/${item.id}".toAbsoluteUrl(domain),
                coverUrl = finalCoverUrl, // Sẽ là null nếu rỗng
                authors = setOfNotNull(item.author),
                tags = item.genres.mapNotNullToSet { genreKey -> 
                    tagMap[genreKey.lowercase()] ?: MangaTag(genreKey, genreKey, source)
                },
                source = source,
                contentRating = ContentRating.ADULT,
                altTitles = emptySet(),
                rating = item.likes?.toFloat() ?: RATING_UNKNOWN,
                state = null
            )
        }
    }
    
    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val mangaId = manga.url.substringAfterLast('/')
        val detailsApiUrl = "/api/manga/$mangaId".toAbsoluteUrl(domain)
        
        val headers = mapOf("Referer" to manga.publicUrl).toHeaders()
        val response = webClient.httpGet(detailsApiUrl, extraHeaders = headers)
        val responseJson = response.body!!.string()
        
        val details = json.decodeFromString<ApiResponse>(responseJson).manga
            ?: throw IllegalStateException("Failed to parse manga details for ID: $mangaId")

        val scanlatorName = details.userId?.username
        
        val chapters = details.chapters.map { chapterItem ->
            MangaChapter(
                id = generateUid(chapterItem.id),
                title = chapterItem.title,
                number = chapterItem.chapterNumber.toFloat(),
                url = "$mangaId|${chapterItem.id}",
                uploadDate = parseDate(chapterItem.createdAt) ?: 0L,
                source = source,
                scanlator = scanlatorName,
                volume = 0,
                branch = null
            )
        }.sortedByDescending { it.number }

        // SỬA MỚI: Gán 'null' nếu rỗng
        val finalCoverUrl = details.coverImage?.takeIf { it.isNotBlank() }
        val authorsSet = setOfNotNull(details.author, details.artist)
        val tagMap = getOrCreateTagMap()

        manga.copy(
            coverUrl = finalCoverUrl, // Sẽ là null nếu rỗng
            altTitles = details.alternativeTitles.toSet(),
            authors = authorsSet,
            description = details.description ?: "",
            tags = details.genres.mapNotNullToSet { genreKey -> 
                tagMap[genreKey.lowercase()] ?: MangaTag(genreKey, genreKey, source)
            },
            chapters = chapters,
            state = parseMangaState(details.status)
        )
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (mangaId, chapterId) = chapter.url.split('|').takeIf { it.size == 2 }
            ?: throw IllegalArgumentException("Invalid chapter URL format. Expected 'mangaId|chapterId'")

        val detailsApiUrl = "/api/manga/$mangaId".toAbsoluteUrl(domain)
        
        val readUrl = "/manga/$mangaId/read/$chapterId".toAbsoluteUrl(domain)
        val headers = mapOf("Referer" to readUrl).toHeaders()
        val response = webClient.httpGet(detailsApiUrl, extraHeaders = headers)
        val responseJson = response.body!!.string()
        
        val details = json.decodeFromString<ApiResponse>(responseJson).manga
            ?: throw IllegalStateException("Failed to parse manga details (for pages)")

        val chapterData = details.chapters.find { it.id == chapterId }
            ?: throw IllegalStateException("Chapter $chapterId not found in API response")

        if (chapterData.pages.isEmpty()) {
            // SỬA MỚI: Trả về list rỗng thay vì throw lỗi, vì API có thể trả về pages rỗng
            // (Mặc dù logic anti-bot trước đó cho thấy nó nên có)
             return emptyList()
        }
        
        // SỬA MỚI: Dùng mapNotNull để lọc ra các trang có URL rỗng
        return chapterData.pages
            .sortedBy { it.pageNumber }
            .mapNotNull { pageItem ->
                val finalUrl = pageItem.imageUrl.takeIf { it.isNotBlank() }
                if (finalUrl == null) {
                    null // Bỏ qua trang này
                } else {
                    MangaPage(
                        id = generateUid(finalUrl), 
                        url = finalUrl,
                        source = source, 
                        preview = null
                    )
                }
            }
    }

    private var tagCache: ArrayMap<String, MangaTag>? = null
    private val mutex = Mutex()

    private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
        tagCache?.let { return@withLock it }

        val staticGenres = listOf(
            MangaTag(title = "Hành động", key = "action", source = source),
            MangaTag(title = "Phiêu lưu", key = "adventure", source = source),
            MangaTag(title = "Hài hước", key = "comedy", source = source),
            MangaTag(title = "Drama", key = "drama", source = source),
            MangaTag(title = "Fantasy", key = "fantasy", source = source),
            MangaTag(title = "Kinh dị", key = "horror", source = source),
            MangaTag(title = "Bí ẩn", key = "mystery", source = source),
            MangaTag(title = "Lãng mạn", key = "romance", source = source),
            MangaTag(title = "Khoa học viễn tưởng", key = "sci-fi", source = source),
            MangaTag(title = "Đời thường", key = "slice-of-life", source = source),
            MangaTag(title = "Thể thao", key = "sports", source = source),
            MangaTag(title = "Siêu nhiên", key = "supernatural", source = source),
            MangaTag(title = "Giật gân", key = "thriller", source = source)
        )
        
        val tagMap = ArrayMap<String, MangaTag>()
        for (tag in staticGenres) {
            tagMap[tag.key] = tag
        }

        tagCache = tagMap
        return@withLock tagMap
    }

    private fun parseDate(dateStr: String?): Long? {
        if (dateStr == null) return null
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        
        return try {
            sdf.parse(dateStr)?.time
        } catch (e: ParseException) {
            try {
                val simplerSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                simplerSdf.parse(dateStr)?.time
            } catch (e2: ParseException) { null }
        }
    }
    
    private fun parseMangaState(status: String?): MangaState? {
        return when (status?.lowercase()) {
            "completed" -> MangaState.FINISHED
            "ongoing" -> MangaState.ONGOING
            else -> null
        }
    }
}
