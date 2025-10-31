package org.dokiteam.doki.parsers.site.vi

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.exception.ParseException
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import org.dokiteam.doki.parsers.util.json.getStringOrNull
import org.dokiteam.doki.parsers.util.json.toJsonObject // Giả định import này
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

@MangaSourceParser("NHENTAICLUB", "Nhentai Club", "vi", ContentType.HENTAI)
internal class NhentaiWorld(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.NHENTAICLUB, 24) {

	override val configKeyDomain = ConfigKey.Domain("nhentaiclub.icu")
	
	private val apiDomain = "nhentaiclub.cyou"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("origin", "https://$domain")
		.add("referer", "https://$domain")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val urlBuilder = urlBuilder()
		
		val tagKey = filter.tags.oneOrThrowIfMany()?.key
		val query = filter.query?.nullIfEmpty()

		if (tagKey != null) {
			urlBuilder.addPathSegment("genre").addPathSegment(tagKey)
		} else if (query != null) {
			urlBuilder.addPathSegment("genre").addPathSegment("all")
		}

		urlBuilder.addQueryParameter(
			"sort",
			when (order) {
				SortOrder.UPDATED -> "recent-update"
				SortOrder.POPULARITY -> "view"
				else -> "recent-update"
			},
		)
		
		query?.let {
			urlBuilder.addQueryParameter("search", it)
		}

		filter.states.oneOrThrowIfMany()?.let {
			urlBuilder.addQueryParameter(
				"status",
				when (it) {
					MangaState.ONGOING -> "progress"
					MangaState.FINISHED -> "completed"
					else -> ""
				},
			)
		}

		urlBuilder.addQueryParameter("page", page.toString())

		val doc = webClient.httpGet(urlBuilder.build()).parseHtml()

		val mangaElements = doc.select("div.grid a[href^=/g/]")

		return mangaElements.map { a ->
			val href = a.attrAsAbsoluteUrl("href") 
			val img = a.selectFirst("img")
			val title = img?.attr("alt").orEmpty()
			val coverUrl = img?.attrAsAbsoluteUrlOrNull("src")

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href, 
				publicUrl = href, 
				rating = RATING_UNKNOWN,
				// *** FIX 1: Unresolved reference 'ADULT' ***
				contentRating = ContentRating.ADULT, 
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	// *** HÀM GETDETAILS (V13 - DEBUG THROW) ***
	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url).parseHtml() 
		
		val title = doc.selectFirst("h1.md\\:text-2xl")?.text() ?: manga.title
		val tags = doc.select("a[href^=/genre/]").mapNotNullToSet { a ->
			val tagName = a.text().toTitleCase(sourceLocale)
			val tagKey = a.attrOrNull("href")?.substringAfterLast('/')
			if (tagKey != null && tagName.isNotEmpty()) {
				// *** FIX 2: Unresolved reference 'key' ***
				MangaTag(title = tagName, key = tagKey, source = source)
			} else {
				null
			}
		}
		val stateText = doc.select("a[href*=?status=]")?.text()
		val state = when (stateText) {
			"Đang tiến hành" -> MangaState.ONGOING
			"Hoàn thành" -> MangaState.FINISHED
			else -> null
		}
		val description = doc.selectFirst("div#introduction-wrap p.font-light")?.html()?.nullIfEmpty()
		val altTitles = emptySet<String>() 
		
		val scripts = doc.select("script")
		val regex = Pattern.compile("\\\"data\\\":(\\[.*?\\])")
		var foundData = "DEBUG: Không tìm thấy script chứa 'createdAt'"

		for (script in scripts) {
			if (script.hasAttr("src")) continue 

			val scriptData = script.data()
			
			if (scriptData.contains("createdAt")) { 
				val matcher = regex.matcher(scriptData)
				
				if (matcher.find()) {
					val viChaptersEscaped = matcher.group(1) ?: "[]"
					throw ParseException(viChaptersEscaped, manga.url) // Ném lỗi debug
				} else {
					foundData = "DEBUG: Đã tìm thấy script 'createdAt' NHƯNG RegEx thất bại"
				}
			}
		}

		throw ParseException(foundData, manga.url)
	}

	// *** HÀM GETPAGES (V9) ***
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val urlParts = chapter.url.split("/")
		val mangaId = urlParts.getOrNull(4)
		val chapterName = urlParts.getOrNull(5)?.substringBefore("?")
		val lang = chapter.url.substringAfter("?lang=", "VI")
		
		if (mangaId == null || chapterName == null) {
			throw ParseException("URL chapter không hợp lệ: ${chapter.url}", chapter.url)
		}

		val apiUrl = "https://$apiDomain/comic/read/$mangaId"
		
		// *** FIX 3: Unresolved reference 'toUrlBuilder' ***
		val urlBuilder = webClient.newUrlBuilder(apiUrl)
			.addQueryParameter("name", chapterName)
			.addQueryParameter("lang", lang)
			.build()

		try {
			// *** FIX 4: Inapplicable candidate 'parseJson' ***
			val response = webClient.httpGet(urlBuilder).parseRaw().toJsonObject()
			val picturesArray = response.optJSONArray("pictures") ?: JSONArray()
			
			return (0 until picturesArray.length()).map { i ->
				val imgUrl = picturesArray.getString(i)
				MangaPage(
					id = generateUid(imgUrl),
					url = imgUrl,
					preview = null,
					source = source,
				)
			}
		} catch (e: Exception) {
			throw ParseException("Lỗi khi gọi API getPages: ${e.message}", urlBuilder.toString())
		}
	}

	/**
	 * Logic fetchTags động (đã chính xác):
	 */
	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		val scriptUrls = doc.select("script[src]").map { it.attrAsAbsoluteUrl("src") }

		for (scriptUrl in scriptUrls) {
			if (!scriptUrl.contains("/_next/static/")) continue

			try {
				val docJS = webClient.httpGet(scriptUrl).parseRaw()

				val optionsStart = docJS.indexOf("genres:[{")
				if (optionsStart == -1) continue 

				val optionsEnd = docJS.indexOf("}]", optionsStart)
				if (optionsEnd == -1) continue 

				val optionsStr = docJS.substring(optionsStart + 7, optionsEnd + 2)

				val optionsArray = JSONArray(
					optionsStr
						.replace(Regex(",description:\\s*\"[^\"]*\"(,?)"), "$1") 
						.replace(Regex("(\\w+):"), "\"$1\":")
				)

				return buildSet {
					for (i in 0 until optionsArray.length()) {
						val option = optionsArray.getJSONObject(i)
						val title = option.getStringOrNull("label")!!.toTitleCase(sourceLocale)
						val key = option.getStringOrNull("href")!!.split("/")[2]
						if (title.isNotEmpty() && key.isNotEmpty()) {
							if (title != "Tất cả" || key != "all") { 
								add(MangaTag(title = title, key = key, source = source))
							}
						}
					}
				}

			} catch (e: Exception) {
				continue
			}
		}
		
		return emptySet()
	}
}
