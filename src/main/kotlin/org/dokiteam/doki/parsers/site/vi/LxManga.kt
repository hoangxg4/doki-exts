package org.dokiteam.doki.parsers.site.vi

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.text.Regex

@MangaSourceParser("LXMANGA", "LXManga", "vi", type = ContentType.HENTAI)
// --------------------- SỬA LỖI Ở DÒNG NÀY ---------------------
internal class LxManga(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.LXMANGA, 60) {
// -----------------------------------------------------------------
	override val configKeyDomain = ConfigKey.Domain("lxmanga.my")

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Referer", "https://$domain/")
		.add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
		.build()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val baseUrl = "https://$domain"
		val url = buildString {
			append(baseUrl)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/tim-kiem")
					append("?filter[name]=")
					append(filter.query.urlEncoded())
					if (page > 1) {
						append("&page=")
						append(page)
					}
				}
				filter.tags.isNotEmpty() -> {
					val tag = filter.tags.first()
					append("/the-loai/")
					append(tag.key)
					append("?page=")
					append(page)
				}
				else -> {
					append("/danh-sach")
					append("?page=")
					append(page)
				}
			}
			val sortValue = when (order) {
				SortOrder.POPULARITY -> "-views"
				SortOrder.UPDATED -> "-updated_at"
				SortOrder.NEWEST -> "-created_at"
				SortOrder.ALPHABETICAL -> "name"
				SortOrder.ALPHABETICAL_DESC -> "-name"
				else -> "-updated_at"
			}
			append("&sort=").append(sortValue)
			if (filter.states.isNotEmpty()) {
				append("&filter[status]=")
				append(filter.states.joinToString(separator = ",") {
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "paused"
						else -> ""
					}
				})
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div.manga-vertical").map { item ->
			val titleElement = item.selectFirst("div.p-2 a.text-ellipsis")
				?: item.parseFailed("Không tìm thấy tiêu đề hoặc link manga!")
			val href = titleElement.attr("href")
			val title = titleElement.text()
			val coverUrl = item.selectFirst("div.cover")?.attr("data-bg").orEmpty()
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = coverUrl,
				tags = setOf(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}
	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val root = webClient.httpGet(fullUrl).parseHtml()
		val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("GMT+7")
		}
		val author = root.selectFirst("div.mt-2:contains(Tác giả) span a")?.textOrNull()
		val altTitles = root.selectFirst("div.grow div:contains(Tên khác)")
			?.select("span a")?.mapToSet { it.text() }
			?: emptySet()
		return manga.copy(
			altTitles = altTitles,
			state = when (root.selectFirst("div.mt-2:contains(Tình trạng) span.text-blue-500")?.text()) {
				"Đang tiến hành" -> MangaState.ONGOING
				"Đã hoàn thành" -> MangaState.FINISHED
				else -> null
			},
			tags = root.selectFirst("div.mt-2:contains(Thể loại)")?.select("a.bg-gray-500")?.mapToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
					title = a.text(),
					source = source,
				)
			} ?: emptySet(),
			authors = setOfNotNull(author),
			description = root.selectFirst("meta[name=description]")?.attrOrNull("content"),
			chapters = root.select("div.justify-between ul.overflow-y-auto.overflow-x-hidden a")
				.mapChapters(reversed = true) { i, a ->
					val href = a.attrAsRelativeUrl("href")
					val name = a.selectFirst("span.text-ellipsis")?.text().orEmpty()
					val dateText = a.parent()?.selectFirst("span.timeago")?.attr("datetime").orEmpty()
					val scanlator = root.selectFirst("div.mt-2:has(span:first-child:contains(Thực hiện:)) span:last-child")?.textOrNull()
					MangaChapter(
						id = generateUid(href),
						title = name,
						number = (i + 1).toFloat(),
						volume = 0,
						url = href,
						scanlator = scanlator,
						uploadDate = chapterDateFormat.parseSafe(dateText),
						branch = null,
						source = source,
					)
				},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val response = webClient.httpGet(fullUrl)
		val doc = response.parseHtml()

		// 1. Trích xuất CSRF Token từ HTML của trang chapter
		val csrfToken = doc.selectFirst("meta[name=action_token]")?.attr("content")
			?: throw Exception("Không tìm thấy CSRF Token (action_token).")

		// 2. Build header để gửi request lấy action_token
		val tokenHeaders = Headers.Builder()
			.add("X-CSRF-TOKEN", csrfToken)
			.add("Referer", fullUrl)
			.add("X-Requested-With", "XMLHttpRequest") // Giả lập request từ JS
			.build()

		// 3. Lấy action_token bằng cách gửi request với header đã chuẩn bị
		val tokenUrl = "https://$domain/get_token"
		val tokenResponse = webClient.httpGet(tokenUrl, tokenHeaders).body!!.string()
		val actionToken = Regex("\"action_token\"\\s*:\\s*\"([^\"]+)\"")
			.find(tokenResponse)?.groupValues?.get(1)
			?: throw Exception("Không thể parse action_token từ response: $tokenResponse")

		// 4. Lấy cookie từ response của trang chapter
		val setCookieHeaders = response.headers.values("Set-Cookie")
		val cookieHeader = setCookieHeaders
			.mapNotNull { it.substringBefore(';').trim() }
			.joinToString("; ")

		fun String.escapeJson(): String = this.replace("\\", "\\\\").replace("\"", "\\\"")

		// 5. Build map chứa tất cả các header cho request ảnh
		val headersMap = mutableMapOf<String, String>()
		headersMap["Referer"] = fullUrl
		headersMap["User-Agent"] = getRequestHeaders()["User-Agent"]!!
		headersMap["Token"] = actionToken // Header quan trọng nhất

		if (cookieHeader.isNotBlank()) {
			headersMap["Cookie"] = cookieHeader
		}

		val headersJson = headersMap.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
			"\"${key.escapeJson()}\":\"${value.escapeJson()}\""
		}

		// 6. Lấy URL ảnh và gán header đã chuẩn bị
		return doc.select("div.text-center div.lazy")
			.mapNotNull { div ->
				val url = div.attr("data-src")
				if (url.isNotBlank()) {
					val urlWithHeaders = "$url#Doki-Headers=$headersJson"
					MangaPage(
						id = generateUid(url),
						url = urlWithHeaders,
						preview = null,
						source = source,
					)
				} else {
					null
				}
			}
			.ifEmpty {
				if (doc.body().text().contains("LXCoin", ignoreCase = true)) {
					throw Exception("Bạn cần phải nạp LXCoin mua code VIP để xem nội dung này trên trang Web!")
				}
				emptyList()
			}
	}

	private suspend fun availableTags(): Set<MangaTag> {
		val url = "https://$domain/the-loai"
		val doc = webClient.httpGet(url).parseHtml()

		return doc.select("nav.grid.grid-cols-3.md\\:grid-cols-8 button").map { button ->
			val key = button.attr("wire:click").substringAfterLast(", '").substringBeforeLast("')")
			MangaTag(
				key = key,
				title = button.select("span.text-ellipsis").text(),
				source = source,
			)
		}.toSet()
	}
}
