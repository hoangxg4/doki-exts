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
			val href = a.attrAsRelativeUrl("href")
			val img = a.selectFirst("img")
			val title = img?.attr("alt").orEmpty()
			val coverUrl = img?.attrAsAbsoluteUrlOrNull("src")

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
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
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		
		val title = doc.selectFirst("h1.md\\:text-3xl.text-2xl")?.text() ?: manga.title

		val tags = doc.select("a[href^=/genre/]").mapNotNullToSet { a ->
			val tagName = a.text().toTitleCase(sourceLocale)
			val tagKey = a.attrOrNull("href")?.substringAfterLast('/')
			if (tagKey != null && tagName.isNotEmpty()) {
				MangaTag(title = tagName, key = tagKey, source = source)
			} else {
				null
			}
		}

		val stateText = doc.select("div.flex.items-center.gap-1.text-sm span.font-semibold")
			.lastOrNull()?.text()
		val state = when (stateText) {
			"Đang tiến hành" -> MangaState.ONGOING
			"Hoàn thành" -> MangaState.FINISHED
			else -> null
		}

		val description = doc.selectFirst("div.md\\:text-base.text-sm.leading-relaxed p")?.html()?.nullIfEmpty()

		val altTitles = description?.split("\n")?.mapNotNullToSet { line ->
			when {
				line.startsWith("Tên khác:", ignoreCase = true) ->
					line.substringAfter(':').trim()
				
				line.startsWith("Tên tiếng anh:", ignoreCase = true) ->
					line.substringAfter(':').substringBefore("Tên gốc:").trim()

				line.startsWith("Tên gốc:", ignoreCase = true) ->
					line.substringAfter(':').trim().substringBefore(' ')

				else -> null
			}
		}
		
		val chapterDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

		var chapterElements = doc.select("div[id=chapter-list-link] a[href^=/read/]")

		if (chapterElements.isEmpty()) {
			chapterElements = doc.select("div.grid.grid-cols-1.divide-y a[href^=/read/]")
		}

		val chapters = chapterElements.mapNotNull { a ->
			val url = a.attrAsRelativeUrl("href")
			
			val titleText = a.selectFirst("span.float-left")?.text()
				?: a.selectFirst("span.font-medium")?.text()
				?: return@mapNotNull null
			
			val number = titleText.substringAfter("Chapter ").toFloatOrNull()
				?: titleText.substringAfter("Oneshot").toFloatOrNull()
				?: -1f

			val uploadDateStr = a.selectFirst("span.float-right")?.text()
				?: a.selectFirst("span.text-xs.opacity-70")?.text()
			val uploadDate = chapterDateFormat.parseSafe(uploadDateStr)
			
			val langQuery = url.substringAfter("?lang=", "")
			val branch = when {
				langQuery.startsWith("VI", ignoreCase = true) -> "Tiếng Việt"
				langQuery.startsWith("EN", ignoreCase = true) -> "English"
				else -> null
			}

			MangaChapter(
				id = generateUid(url),
				title = titleText,
				number = number,
				url = url,
				scanlator = null,
				uploadDate = uploadDate,
				branch = branch,
				source = source,
				volume = 0
			)
		}

		return manga.copy(
			title = title,
			tags = tags,
			state = state,
			description = description,
			altTitles = altTitles.orEmpty(),
			chapters = chapters.reversed(),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()
		
		val root = doc.select("div.flex.flex-col.items-center > img.m-auto")

		if (root.isEmpty()) {
			throw ParseException("Không tìm thấy ảnh nào (Root is empty!)", url)
		}

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

	/**
	 * Logic fetchTags động:
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

				// Tìm chuỗi "genres:[{" (đây là key chứa mảng JSON)
				val optionsStart = docJS.indexOf("genres:[{")
				if (optionsStart == -1) continue 

				val optionsEnd = docJS.indexOf("}]", optionsStart)
				if (optionsEnd == -1) continue 

				val optionsStr = docJS.substring(optionsStart + 7, optionsEnd + 2)

				// Parse JSON (Thêm quote cho key không có quote)
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
		
		// Nếu thất bại (do Cloudflare), trả về rỗng.
		return emptySet()
	}
}
