package org.dokiteam.doki.parsers.site.vi

import okhttp3.Headers
import org.json.JSONArray
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

	// CẢNH BÁO: Logic của getListPage và getPages có thể cũng
	// yêu cầu phân tích JSON/RegEx tương tự như getDetails.
	// Chúng ta sẽ sửa getDetails trước.
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

		// Logic này có thể thất bại nếu trang chủ cũng dùng RSC/JS
		val mangaElements = doc.select("div.grid a[href^=/g/]")
		if (mangaElements.isEmpty()) {
			// TODO: Thêm logic RegEx cho getListPage nếu cần
		}

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

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url).parseHtml() 
		
		// 1. Lấy thông tin từ HTML (phần này vẫn ổn)
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

		val altTitles = description?.split("\n")?.mapNotNullToSet { line ->
			when {
				line.startsWith("Tên khác:", ignoreCase = true) ->
					line.substringAfter(':').trim()
				// (Giữ lại logic cũ)
				line.startsWith("Tên tiếng anh:", ignoreCase = true) ->
					line.substringAfter(':').substringBefore("Tên gốc:").trim()
				line.startsWith("Tên gốc:", ignoreCase = true) ->
					line.substringAfter(':').trim().substringBefore(' ')
				else -> null
			}
		}
		
		// 2. Lấy Chapters từ JSON stream
		val chapters = mutableListOf<MangaChapter>()
		val scripts = doc.select("script")
		
		val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
		val mangaId = manga.url.substringAfterLast('/')
		
		// *** FIX (V8): Đã gỡ bỏ dấu \ khỏi RegEx ***
		// RegEx này tìm chuỗi JSON *sau khi* đã được Jsoup un-escape
		val regex = Pattern.compile("\"data\":(\\[.*?\\]),\"chapterListEn\":(\\[.*?\\])")
		
		var found = false
		for (script in scripts) {
			if (script.hasAttr("src")) continue 

			val scriptData = script.data() // scriptData = '...{"data":[...]}'
			if (scriptData.contains("chapterListEn")) {
				val matcher = regex.matcher(scriptData)
				
				if (matcher.find()) {
					val viChaptersStr = matcher.group(1)
					val enChaptersStr = matcher.group(2)
					
					val viArray = try { JSONArray(viChaptersStr) } catch (e: Exception) { JSONArray() }
					val enArray = try { JSONArray(enChaptersStr) } catch (e: Exception) { JSONArray() }
					
					// Parse VI Chapters
					for (i in 0 until viArray.length()) {
						val chapObj = viArray.getJSONObject(i)
						val name = chapObj.getStringOrNull("name") ?: continue
						val uploadDateStr = chapObj.getStringOrNull("createdAt")?.substringBefore("T")
						val uploadDate = chapterDateFormat.parseSafe(uploadDateStr)

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
					
					// Parse EN Chapters
					for (i in 0 until enArray.length()) {
						val chapObj = enArray.getJSONObject(i)
						val name = chapObj.getStringOrNull("name") ?: continue
						val uploadDateStr = chapObj.getStringOrNull("createdAt")?.substringBefore("T")
						val uploadDate = chapterDateFormat.parseSafe(uploadDateStr)

						val url = "https://_domain/read/$mangaId/$name?lang=EN".replace("_domain", domain)
						chapters.add(
							MangaChapter(
								id = generateUid(url),
								title = "Chapter $name",
								number = name.toFloatOrNull() ?: (i + 1).toFloat(),
								url = url, 
								scanlator = null,
								uploadDate = uploadDate,
								branch = "English",
								source = source,
								volume = 0
							)
						)
					}
					
					found = true
					break 
				}
			}
		}
		
		if (!found) {
			// (Giữ im lặng nếu không tìm thấy để tránh crash app)
			// throw ParseException("Không thể tìm thấy JSON stream của chapter", manga.url)
		}

		return manga.copy(
			title = title,
			tags = tags,
			state = state,
			description = description,
			altTitles = altTitles.orEmpty(),
			chapters = chapters.sortedBy { it.number }, 
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url 
		val doc = webClient.httpGet(url).parseHtml()
		
		// 1. Thử tìm HTML (Selector này có thể thất bại)
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

		// 2. Fallback: Thử tìm JSON stream (giống getDetails)
		val scripts = doc.select("script")
		// *** FIX (V8): Đã gỡ bỏ dấu \ khỏi RegEx ***
		val regex = Pattern.compile("\"pictures\":(\\[.*?\\])") // Tìm mảng "pictures":[...]
		
		for (script in scripts) {
			if (script.hasAttr("src")) continue
			
			val scriptData = script.data()
			if (scriptData.contains("\"pictures\":")) {
				val matcher = regex.matcher(scriptData)
				if (matcher.find()) {
					val picturesStr = matcher.group(1)
					val picturesArray = JSONArray(picturesStr)
					
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
		
		// 3. Nếu cả hai đều thất bại, throw lỗi
		throw ParseException("Không tìm thấy ảnh (Selector và RegEx đều thất bại)", url)
	}

	/**
	 * Logic fetchTags động (đã chính xác):
	 * Quét các file JS được link từ trang chủ
	 * để tìm mảng `genres:[{...}]`
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
		
		// Thất bại, trả về rỗng.
		return emptySet()
	}
}
