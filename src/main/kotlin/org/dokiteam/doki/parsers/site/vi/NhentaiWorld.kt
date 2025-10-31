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
// (Đã xoá import 'toUrlBuilder' không hợp lệ)
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.math.max

@MangaSourceParser("NHENTAICLUB", "Nhentai Club", "vi", ContentType.HENTAI)
internal class NhentaiWorld(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.NHENTAICLUB, 24) {

	override val configKeyDomain = ConfigKey.Domain("nhentaiclub.icu")
	
	// (Đã xoá apiDomain, không còn cần thiết)

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
				contentRating = ContentRating.ADULT, 
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	// *** HÀM GETDETAILS ĐÃ ĐƯỢC VIẾT LẠI (V17) ***
	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url).parseHtml() 
		
		// 1. Lấy thông tin từ HTML
		val title = doc.selectFirst("h1.md\\:text-2xl")?.text() ?: manga.title
		val tags = doc.select("a[href^=/genre/]").mapNotNullToSet { a ->
			val tagName = a.text().toTitleCase(sourceLocale)
			val tagKey = a.attrOrNull("href")?.substringAfterLast('/')
			if (tagKey != null && tagName.isNotEmpty()) {
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
		
		// 2. Lấy Chapters (Logic V17: Generate 1..N)
		val scripts = doc.select("script")
		val regex = Pattern.compile("\\\"data\\\":(\\[.*?\\])")
		var maxChapter = 1 // Mặc định là 1 (cho oneshot)
		
		for (script in scripts) {
			if (script.hasAttr("src")) continue 
			val scriptData = script.data()
			
			if (scriptData.contains("createdAt")) { 
				val matcher = regex.matcher(scriptData)
				
				if (matcher.find()) {
					val viChaptersEscaped = matcher.group(1) ?: "[]"
					val viChaptersStr = viChaptersEscaped.replace("\\\"", "\"")
					val viArray = try { JSONArray(viChaptersStr) } catch (e: Exception) { JSONArray() }
					
					// Tìm chapter lớn nhất
					var maxNum = 0
					for (i in 0 until viArray.length()) {
						val chapObj = viArray.getJSONObject(i)
						val name = chapObj.getStringOrNull("name")
						val num = name?.toIntOrNull() ?: 0
						maxNum = max(maxNum, num)
					}
					
					if (maxNum > 0) {
						maxChapter = maxNum
					}
					break // Đã tìm thấy
				}
			}
		}
		
		// 3. Tạo danh sách chapter từ 1 đến maxChapter
		val mangaId = manga.url.substringAfterLast('/')
		val chapters = (1..maxChapter).map { i ->
			val name = i.toString()
			val url = "https://_domain/read/$mangaId/$name?lang=VI".replace("_domain", domain)
			
			MangaChapter(
				id = generateUid(url),
				title = "Chapter $name",
				number = i.toFloat(),
				url = url, 
				scanlator = null,
				uploadDate = null, // Không thể biết ngày upload
				branch = "Tiếng Việt",
				source = source,
				volume = 0
			)
		}

		return manga.copy(
			title = title,
			tags = tags,
			state = state,
			description = description,
			altTitles = altTitles,
			// Sắp xếp ngược (descending) để chap mới nhất lên đầu
			chapters = chapters.sortedByDescending { it.number }, 
		)
	}

	// *** HÀM GETPAGES (V17) - Quay lại logic RegEx (thay vì API) ***
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url).parseHtml()

		// 1. Thử tìm HTML (Fallback, có thể thất bại)
		val root = doc.select("div.flex.flex-col.items-center > img.m-auto")
		if (root.isNotEmpty()) {
			return root.map { img ->
				val imgUrl = img.requireSrc()
				MangaPage(
					id = generateUid(imgUrl),
					url = imgUrl,
					preview = null,
					source = source,
				)
			}
		}

		// 2. Logic chính: Parse JSON stream (RSC)
		val scripts = doc.select("script")
		val regex = Pattern.compile("\\\"pictures\\\":(\\[.*?\\])") // Tìm mảng "pictures":[...]
		
		for (script in scripts) {
			if (script.hasAttr("src")) continue
			
			val scriptData = script.data() // '...\"pictures\":[\"url1\", \"url2\"]...'
			if (scriptData.contains("pictures")) {
				val matcher = regex.matcher(scriptData)
				
				if (matcher.find()) {
					val picturesEscaped = matcher.group(1) ?: "[]"
					val picturesStr = picturesEscaped.replace("\\\"", "\"")
					val picturesArray = try { JSONArray(picturesStr) } catch (e: Exception) { JSONArray() }
					
					return (0 until picturesArray.length()).map { i ->
						val imgUrl = picturesArray.getString(i)
						MangaPage(
							id = generateUid(imgUrl),
							url = imgUrl,
							preview = null,
							source = source,
						)
					}
				}
			}
		}

		// 3. Nếu cả hai đều thất bại
		throw ParseException("Không tìm thấy ảnh (Selector và RegEx đều thất bại)", chapter.url)
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
