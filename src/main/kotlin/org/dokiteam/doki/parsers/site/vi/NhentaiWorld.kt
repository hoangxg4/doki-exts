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
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

@MangaSourceParser("NHENTAICLUB", "Nhentai Club", "vi", ContentType.HENTAI)
internal class NhentaiWorld(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.NHENTAICLUB, 24) {

	override val configKeyDomain = ConfigKey.Domain("nhentaiclub.icu")
	
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

	// *** HÀM GETLISTPAGE (V26) ***
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
		val mangaList = mutableListOf<Manga>()

		// 1. Thử tìm bằng HTML (Fallback)
		val mangaElements = doc.select("div.grid a[href^=/g/]")
		if (mangaElements.isNotEmpty()) {
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

		// 2. Logic chính: Parse JSON stream (RSC)
		val scripts = doc.select("script")
		// *** FIX (V26): Tìm chuỗi ĐÃ ESCAPE (\\") ***
		val regex = Pattern.compile("href:\\\"(\\/g\\/\\d+)\\\".*?alt:\\\"(.*?)\\\".*?src:\\\"(https.*?thumbnail\\.jpg)\\\"")
		
		for (script in scripts) {
			if (script.hasAttr("src")) continue
			
			val scriptData = script.data()
			if (scriptData.contains("max-h-[405px]")) { // "mồi"
				val matcher = regex.matcher(scriptData)
				
				while (matcher.find()) {
					val href = "https://$domain${matcher.group(1)}"
					
					val titleEscaped = matcher.group(2) ?: ""
					val title = try { JSONArray("[\"$titleEscaped\"]").getString(0) } catch (e: Exception) { titleEscaped }
					val coverUrl = matcher.group(3).replace("\\\"", "\"")

					mangaList.add(
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
					)
				}
				if (mangaList.isNotEmpty()) {
					return mangaList
				}
			}
		}
		return emptyList()
	}

	// *** HÀM GETDETAILS (V28) - BẬT LẠI LOGIC PARSE ***
	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url).parseHtml() 
		
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
		
		val chapters = mutableListOf<MangaChapter>()
		val scripts = doc.select("script")
		val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
		
		// *** FIX (V28): RegEx ĐÃ UN-ESCAPE (không có \\) ***
		val regexData = Pattern.compile("\"data\":(\\[.*?\\])")
		val regexId = Pattern.compile("\"id\":\"(\\d+)\"") // Chỉ lấy số
		
		var mangaId = ""
		var viChaptersStr = "[]" // Mặc định là mảng rỗng

		for (script in scripts) {
			if (script.hasAttr("src")) continue 
			val scriptData = script.data() 
			
			// 1. Tìm đúng script (theo "mồi" bạn cung cấp)
			if (scriptData.contains("@/components/chapterList")) { 
				
				// 2. Lấy ID
				val idMatcher = regexId.matcher(scriptData)
				if (idMatcher.find()) {
					mangaId = idMatcher.group(1)
				}

				// 3. Lấy Data
				val dataMatcher = regexData.matcher(scriptData)
				if (dataMatcher.find()) {
					viChaptersStr = dataMatcher.group(1) ?: "[]"
				}
				
				break // Đã tìm thấy script, thoát vòng lặp
			}
		}
		
		if (mangaId.isEmpty()) {
			mangaId = manga.url.substringAfterLast('/') // Fallback
		}

		// 4. Parse mảng JSON
		try {
			val viArray = JSONArray(viChaptersStr)
			
			for (i in 0 until viArray.length()) {
				val chapObj = viArray.getJSONObject(i)
				val name = chapObj.getStringOrNull("name") ?: continue
				val uploadDateStr = chapObj.getStringOrNull("createdAt")?.substringBefore("T")
				val uploadDate = chapterDateFormat.parseSafe(uploadDateStr) ?: 0L

				val url = "https://_domain/read/$mangaId/$name?lang=VI".replace("_domain", domain)
				chapters.add(
					MangaChapter(
						id = generateUid(url),
						title = "Chapter $name",
						number = name.toFloatOrNull() ?: (i + 1).toFloat(),
						url = url, 
						scanlator = null,
						uploadDate = uploadDate,
						branch = "Tiếng Việt",
						source = source,
						volume = 0
					)
				)
			}
		} catch (e: Exception) {
			// (Nếu parse lỗi, chapters sẽ rỗng)
			// throw ParseException("Lỗi parse JSON chapter: $viChaptersStr", manga.url)
		}

		return manga.copy(
			title = title,
			tags = tags,
			state = state,
			description = description,
			altTitles = altTitles,
			chapters = chapters.sortedByDescending { it.number }, 
		)
	}

	// *** HÀM GETPAGES (V28) - Logic RegEx (Un-escaped) ***
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url).parseHtml()

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

		val scripts = doc.select("script")
		// *** FIX (V28): Tìm chuỗi ĐÃ UN-ESCAPE (không có \\) ***
		val regex = Pattern.compile("\"pictures\":(\\[.*?\\])")
		
		for (script in scripts) {
			if (script.hasAttr("src")) continue
			
			val scriptData = script.data() 
			if (scriptData.contains("pictures")) {
				val matcher = regex.matcher(scriptData)
				
				if (matcher.find()) {
					val picturesStr = matcher.group(1) ?: "[]"
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
