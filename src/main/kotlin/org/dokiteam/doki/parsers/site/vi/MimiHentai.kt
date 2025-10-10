package org.dokiteam.doki.parsers.site.vi

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.bitmap.Bitmap
import org.dokiteam.doki.parsers.bitmap.Rect
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.network.UserAgents
import org.dokiteam.doki.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject

@MangaSourceParser("MIMIHENTAI", "MimiHentai", "vi", type = ContentType.HENTAI)
internal class MimiHentai(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MIMIHENTAI, 18) {

	private val apiSuffix = "api/v2/manga"
	override val configKeyDomain = ConfigKey.Domain("mimihentai.com", "hentaihvn.com")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}
	
	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.US)
	private val dateFormatFallback = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)


	init {
		setFirstPage(0)
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.remove(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
		SortOrder.RATING
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isAuthorSearchSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(availableTags = fetchTags())

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildListUrl(page, order, filter)
		val response = webClient.httpGet(url).body!!.string()

		val mangaItems = if (url.contains("/top-manga")) {
			json.decodeFromString<List<ApiMangaItem>>(response)
		} else {
			json.decodeFromString<ApiMangaListResponse>(response).data
		}
		
		return mangaItems.map { toManga(it) }
	}
	
	private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
		val baseUrl = "https://$domain/$apiSuffix"

		val isSearching = !filter.query.isNullOrEmpty() || !filter.author.isNullOrEmpty() || filter.tags.isNotEmpty()

		return if (isSearching) {
			val params = buildMap {
				put("page", page.toString())
				put("max", "18")
				filter.query?.takeIf { it.isNotEmpty() }?.let { put("name", it.urlEncoded()) }
				filter.author?.takeIf { it.isNotEmpty() }?.let { put("author", it.urlEncoded()) }
				if (filter.tags.isNotEmpty()) put("genre", filter.tags.joinToString(",") { it.key })
				if (filter.tagsExclude.isNotEmpty()) put("ex", filter.tagsExclude.joinToString(",") { it.key })
				put("sort", when (order) {
					SortOrder.UPDATED -> "updated_at"
					SortOrder.ALPHABETICAL -> "title"
					SortOrder.POPULARITY -> "follows"
					SortOrder.RATING -> "likes"
					else -> "views"
				})
			}
			"$baseUrl/advance-search?${params.toUrlQuery()}"
		} else {
			val path: String
			val params = mutableMapOf("page" to page.toString())
			
			when (order) {
				SortOrder.POPULARITY_WEEK -> {
					path = "/top-manga"
					params["timeType"] = "1"
					params["limit"] = "18"
				}
				SortOrder.POPULARITY_MONTH -> {
					path = "/top-manga"
					params["timeType"] = "2"
					params["limit"] = "18"
				}
				else -> {
					path = "/tatcatruyen"
					params["sort"] = when (order) {
						SortOrder.UPDATED -> "updated_at"
						SortOrder.ALPHABETICAL -> "title"
						SortOrder.POPULARITY -> "follows"
						SortOrder.RATING -> "likes"
						else -> "views"
					}
				}
			}
			if (filter.tagsExclude.isNotEmpty()) {
				params["ex"] = filter.tagsExclude.joinToString(",") { it.key }
			}
			"$baseUrl$path?${params.toUrlQuery()}"
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val mangaId = manga.url.substringAfter("/info/")
		
		val detailsUrl = "https://$domain/$apiSuffix/info/$mangaId"
		val detailsJson = webClient.httpGet(detailsUrl).body!!.string()
		val detailsData = json.decodeFromString<ApiMangaItem>(detailsJson)

		val chaptersDeferred = async {
			val chaptersUrl = "https://$domain/$apiSuffix/gallery/$mangaId"
			val chaptersJson = webClient.httpGet(chaptersUrl).body!!.string()
			json.decodeFromString<List<ApiChapter>>(chaptersJson)
		}

		val chaptersData = chaptersDeferred.await()
		val chapters = chaptersData.map {
			MangaChapter(
				id = generateUid(it.id),
				title = it.title,
				number = it.order,
				url = it.id.toString(),
				uploadDate = parseDate(it.createdAt) ?: 0L,
				source = source,
				scanlator = detailsData.authors.firstOrNull()?.name,
				branch = null,
				volume = 0,
			)
		}.reversed()
		
		return@coroutineScope manga.copy(
			description = detailsData.description,
			tags = manga.tags + toManga(detailsData).tags,
			chapters = chapters
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = "https://$domain/$apiSuffix/chapter?id=${chapter.url}"
		val response = webClient.httpGet(url).body!!.string()
		val pageData = json.decodeFromString<ApiChapterPagesResponse>(response)

		return pageData.pages.map {
			MangaPage(
				id = generateUid(it.imageUrl),
				url = if (it.drm != null) "${it.imageUrl}#$GT${it.drm}" else it.imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val response = chain.proceed(chain.request())
		val fragment = response.request.url.fragment

		if (fragment == null || !fragment.contains(GT)) {
			return response
		}

		return context.redrawImageResponse(response) { bitmap ->
			val gt = fragment.substringAfter(GT)
			runBlocking {
				extractMetadata(bitmap, gt)
			}
		}
	}

	private fun extractMetadata(bitmap: Bitmap, gt: String): Bitmap {
		var sw = 0
		var sh = 0
		val posMap = mutableMapOf<String, String>()
		val dimsMap = mutableMapOf<String, DrmRect>()

		for (t in gt.split("|")) {
			when {
				t.startsWith("sw:") -> sw = t.substring(3).toInt()
				t.startsWith("sh:") -> sh = t.substring(3).toInt()
				t.contains("@") && t.contains(">") -> {
					val (left, right) = t.split(">")
					val (n, rectStr) = left.split("@")
					val (x, y, w, h) = rectStr.split(",").map { it.toInt() }
					dimsMap[n] = DrmRect(x, y, w, h)
					posMap[n] = right
				}
			}
		}
		val metadata = DrmMetadata(sw, sh, dimsMap, posMap)

		if (metadata.sw <= 0 || metadata.sh <= 0) return bitmap
		val fullW = bitmap.width
		val fullH = bitmap.height
		val working = context.createBitmap(metadata.sw, metadata.sh).also { k ->
			k.drawBitmap(bitmap, Rect(0, 0, metadata.sw, metadata.sh), Rect(0, 0, metadata.sw, metadata.sh))
		}

		val keys = arrayOf("00","01","02","10","11","12","20","21","22")
		val baseW = metadata.sw / 3
		val baseH = metadata.sh / 3
		val rw = metadata.sw % 3
		val rh = metadata.sh % 3
		val defaultDims = HashMap<String, DrmRect>().apply {
			for (k in keys) {
				val i = k[0].digitToInt()
				val j = k[1].digitToInt()
				val w = baseW + if (j == 2) rw else 0
				val h = baseH + if (i == 2) rh else 0
				put(k, DrmRect(j * baseW, i * baseH, w, h))
			}
		}

		val invPos = metadata.pos.entries.associate { (k, v) -> v to k }
		val result = context.createBitmap(fullW, fullH)

		for (k in keys) {
			val srcKey = invPos[k] ?: continue
			val sRect = metadata.dims[k] ?: defaultDims.getValue(k)
			val dRect = metadata.dims[srcKey] ?: defaultDims.getValue(srcKey)
			
			result.drawBitmap(
				working,
				Rect(sRect.x, sRect.y, sRect.x + sRect.width, sRect.y + sRect.height),
				Rect(dRect.x, dRect.y, dRect.x + dRect.width, dRect.y + dRect.height),
			)
		}

		if (metadata.sh < fullH) {
			result.drawBitmap(bitmap, Rect(0, metadata.sh, fullW, fullH), Rect(0, metadata.sh, fullW, fullH))
		}
		if (metadata.sw < fullW) {
			result.drawBitmap(bitmap, Rect(metadata.sw, 0, fullW, fullH), Rect(metadata.sw, 0, fullW, fullH))
		}
		return result
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val url = "https://$domain/$apiSuffix/genres"
		val response = webClient.httpGet(url).body!!.string()
		val tags = json.decodeFromString<List<ApiTag>>(response)
		return tags.mapToSet {
			MangaTag(
				title = it.name.toTitleCase(sourceLocale),
				key = it.id.toString(),
				source = source
			)
		}
	}
	
	private fun parseDate(dateString: String?): Long? {
        if (dateString.isNullOrBlank()) return null
        return try {
            dateFormat.parse(dateString)?.time
        } catch (e: Exception) {
            try {
                dateFormatFallback.parse(dateString)?.time
            } catch (e2: Exception) {
                null
            }
        }
    }

	private fun Map<String, String>.toUrlQuery(): String {
		return this.entries.joinToString("&") { (k, v) -> "$k=$v" }
	}
	
	private fun toManga(item: ApiMangaItem): Manga {
		val additionalTags = (item.parody.orEmpty() + item.characters.orEmpty()).mapToSet {
			MangaTag(it.toTitleCase(), it, source)
		}
		
		val altTitles = (item.differentNames.orEmpty() + item.parody.orEmpty()).toMutableSet()

		return Manga(
			id = generateUid(item.id),
			title = item.title.takeIf { it.isNotEmpty() } ?: "Web chưa đặt tên",
			altTitles = altTitles,
			url = "/$apiSuffix/info/${item.id}",
			publicUrl = "https://$domain/g/${item.id}",
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.ADULT,
			coverUrl = item.coverUrl,
			tags = item.genres.mapToSet { MangaTag(it.name.toTitleCase(), it.id.toString(), source) } + additionalTags,
			state = null,
			authors = item.authors.mapToSet { it.name },
			description = item.description,
			source = source,
		)
	}
	
	companion object {
		private const val GT = "gt="
	}

	//region API Data Classes
	@Serializable
	private data class ApiMangaListResponse(
		val data: List<ApiMangaItem>
	)
	
	@Serializable
	private data class ApiMangaItem(
		val id: Long,
		val title: String,
		val description: String? = null,
		val coverUrl: String,
		val differentNames: List<String>? = emptyList(),
		val authors: List<ApiAuthor> = emptyList(),
		val genres: List<ApiTag> = emptyList(),
		val parody: List<String>? = emptyList(),
		val characters: List<String>? = emptyList(),
		val chapterCount: Int? = null,
		val lastUpdated: String? = null
	)

	@Serializable
	private data class ApiTag(
		val id: Long,
		val name: String,
		val description: String? = null
	)

	@Serializable
	private data class ApiAuthor(
		val id: Long? = null,
		val name: String
	)
	
	@Serializable
	private data class ApiChapter(
		val id: Long,
		val title: String? = null,
		val order: Float = 0f,
		val createdAt: String
	)
	
	@Serializable
	private data class ApiChapterPagesResponse(
		val pages: List<ApiPage>
	)
	
	@Serializable
	private data class ApiPage(
		val imageUrl: String,
		val drm: String? = null
	)
	
	private data class DrmRect(val x: Int, val y: Int, val width: Int, val height: Int)
	private data class DrmMetadata(
		val sw: Int,
		val sh: Int,
		val dims: Map<String, DrmRect>,
		val pos: Map<String, String>
	)
	//endregion
}
