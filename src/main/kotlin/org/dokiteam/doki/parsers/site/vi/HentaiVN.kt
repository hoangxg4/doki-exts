package org.dokiteam.doki.parsers.site.vi

// --- CÁC IMPORT BỊ THIẾU ĐÃ ĐƯỢC THÊM VÀO ĐÂY ---
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
// ----------------------------------------------------

import androidx.collection.ArrayMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.AbstractMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

// Data classes giữ nguyên như trước, giờ sẽ hoạt động vì đã có import @Serializable
@Serializable
data class ApiResponse<T>(
    val data: List<T>,
    val page: Int? = null,
    val total: Int? = null
)
// ... (Các data class khác giữ nguyên)
@Serializable
data class MangaListItem(
    val id: Int,
    val title: String,
    val coverUrl: String,
    val authors: String? = null,
    val genres: List<GenreItem> = emptyList(),
    val blocked: Boolean = false
)
@Serializable
data class GenreItem(
    val id: Int,
    val name: String
)
@Serializable
data class AuthorItem(
    val id: Int,
    val name: String
)
@Serializable
data class Uploader(
    val id: Int,
    val name: String
)
@Serializable
data class MangaDetails(
    val id: Int,
    val title: String,
    val alternativeTitles: List<String> = emptyList(),
    val coverUrl: String,
    val description: String,
    val authors: List<AuthorItem> = emptyList(),
    val genres: List<GenreItem> = emptyList(),
    val uploader: Uploader? = null
)
@Serializable
data class ChapterItem(
    val id: Int,
    val title: String,
    val readOrder: Int,
    @SerialName("createdAt")
    val createdAt: String
)
@Serializable
data class ChapterDetails(
    @SerialName("pages")
    val imageUrls: List<String>
)


@MangaSourceParser("HENTAIVN", "HentaiVN", "vi", type = ContentType.HENTAI)
internal class HentaiVNParser(context: MangaLoaderContext) : AbstractMangaParser(context, MangaParserSource.HENTAIVN) {
    
    // Toàn bộ phần logic bên dưới giữ nguyên như phiên bản trước
    // Nó sẽ hoạt động sau khi bạn đã thêm dependency và import
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("https://hentaivn.su")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getFavicons(): Favicons = Favicons(
        listOf(Favicon("https://raw.githubusercontent.com/dragonx943/listcaidaubuoi/refs/heads/main/hentaivn.png", 512, null)),
        domain
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isSearchSupported = true
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = getOrCreateTagMap().values.toSet()
    )

    override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val page = (offset / 18f).toIntUp() + 1
        val apiUrl = buildString {
            append("/api/")
            when {
                !filter.query.isNullOrEmpty() -> append("manga/search?q=${filter.query.urlEncoded()}&page=$page")
                filter.tags.isNotEmpty() -> {
                    val genreIds = filter.tags.joinToString(",") { it.key }
                    append("manga/search?genres=$genreIds&page=$page")
                }
                else -> append("library/latest?page=$page")
            }
        }.toAbsoluteUrl(domain)

        val responseJson = webClient.httpGet(apiUrl).body!!.string()
        val mangaList: List<MangaListItem> = try {
            json.decodeFromString<ApiResponse<MangaListItem>>(responseJson).data
        } catch (e: Exception) {
            json.decodeFromString<List<MangaListItem>>(responseJson)
        }

        return mangaList.filterNot { it.blocked }.map { item ->
            Manga(
                id = generateUid(item.id.toString()),
                title = item.title,
                url = "/manga/${item.id}",
                publicUrl = "/manga/${item.id}".toAbsoluteUrl(domain),
                coverUrl = item.coverUrl.toAbsoluteUrl(domain),
                authors = setOfNotNull(item.authors),
                tags = item.genres.mapToSet { genre -> MangaTag(genre.name, genre.id.toString(), source) },
                source = source,
                contentRating = ContentRating.ADULT,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                state = null
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val mangaId = manga.url.substringAfterLast('/')
        
        val detailsDeferred = async {
            val apiUrl = "/api/manga/$mangaId".toAbsoluteUrl(domain)
            val responseJson = webClient.httpGet(apiUrl).body!!.string()
            json.decodeFromString<MangaDetails>(responseJson)
        }
        
        val chaptersDeferred = async {
            fetchChaptersFromApi(mangaId)
        }

        val details = detailsDeferred.await()
        val chapters = chaptersDeferred.await()

        manga.copy(
            altTitles = details.alternativeTitles.toSet(),
            authors = details.authors.mapToSet { it.name },
            description = details.description,
            tags = details.genres.mapToSet { genre ->
                MangaTag(genre.name, genre.id.toString(), source)
            },
            chapters = chapters.map { it.copy(scanlator = details.uploader?.name) }
        )
    }
    
    private suspend fun fetchChaptersFromApi(mangaId: String): List<MangaChapter> {
        val apiUrl = "/api/manga/$mangaId/chapters".toAbsoluteUrl(domain)
        
        return try {
            val responseJson = webClient.httpGet(apiUrl).body!!.string()
            val chapterItems = json.decodeFromString<List<ChapterItem>>(responseJson)
            
            chapterItems.map { chapterItem ->
                MangaChapter(
                    id = generateUid(chapterItem.id.toString()),
                    title = chapterItem.title,
                    number = chapterItem.readOrder.toFloat(),
                    url = "/chapter/${chapterItem.id}",
                    uploadDate = parseDate(chapterItem.createdAt) ?: 0L,
                    source = source,
                    scanlator = null, 
                    volume = 0,
                    branch = null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast('/')
        val apiUrl = "/api/chapter/$chapterId".toAbsoluteUrl(domain)
        val responseJson = webClient.httpGet(apiUrl).body!!.string()
        val chapterData = json.decodeFromString<ChapterDetails>(responseJson)

        return chapterData.imageUrls.map { imageUrl ->
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl.toAbsoluteUrl(domain),
                source = source,
                preview = null
            )
        }
    }

    private var tagCache: ArrayMap<String, MangaTag>? = null
    private val mutex = Mutex()
	
    private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
        tagCache?.let { return@withLock it }
        val apiUrl = "/api/tag/genre".toAbsoluteUrl(domain)
        
        val responseJson = webClient.httpGet(apiUrl).body!!.string()
        val genres = json.decodeFromString<List<GenreItem>>(responseJson)

        val tagMap = ArrayMap<String, MangaTag>()
        for (genre in genres) {
            tagMap[genre.name] = MangaTag(title = genre.name, key = genre.id.toString(), source = source)
        }
        tagCache = tagMap
        return@withLock tagMap
    }

    private fun parseDate(dateStr: String?): Long? {
        if (dateStr == null) return null
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return try {
            sdf.parse(dateStr)?.time
        } catch (e: ParseException) {
            try {
                val simplerSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                simplerSdf.parse(dateStr)?.time
            } catch (e2: ParseException) { null }
        }
    }
}
