package org.dokiteam.doki.parsers.site.vi

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element // Đảm bảo import Element
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.exception.ParseException
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import org.dokiteam.doki.parsers.util.suspendlazy.getOrNull
import org.dokiteam.doki.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DAMCONUONG", "Dâm Cô Nương", "vi", type = ContentType.HENTAI)
internal class DamCoNuong(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DAMCONUONG, 30) {

	// --- Các thuộc tính và hàm khởi tạo ---
	override val configKeyDomain = ConfigKey.Domain("damconuong.co")
	private val availableTags = suspendLazy(initializer = ::fetchTags)
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)
	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("referer", "https://$domain")
		.build()

	// --- Các hàm lấy danh sách và chi tiết truyện ---
	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// --- (Phần xây dựng URL giữ nguyên như code gốc) ---
		val url = buildString {
			append("https://")
			append(domain)
			append("/tim-kiem")

			append("?sort=")
			append(
				when (order) {
					SortOrder.UPDATED -> "-updated_at"
					SortOrder.NEWEST -> "-created_at"
					SortOrder.POPULARITY -> "-views"
					SortOrder.ALPHABETICAL -> "name"
					SortOrder.ALPHABETICAL_DESC -> "-name"
					else -> "-updated_at" // Mặc định là cập nhật mới nhất
				},
			)

			if (filter.states.isNotEmpty()) {
				append("&filter[status]=")
				// Nối các trạng thái được chọn, phân tách bằng dấu phẩy
				append(filter.states.joinToString(",") {
					when (it) {
						MangaState.ONGOING -> "2"
						MangaState.FINISHED -> "1"
						else -> "" // Bỏ qua các trạng thái không hỗ trợ
					}
				}.trimEnd(',')) // Xóa dấu phẩy cuối nếu có
			}

			if (filter.tags.isNotEmpty()) {
				append("&filter[accept_genres]=")
				append(filter.tags.joinToString(",") { it.key })
			}

			if (!filter.query.isNullOrEmpty()) {
				append("&filter[name]=")
				append(filter.query.urlEncoded())
			}

			if (filter.tagsExclude.isNotEmpty()) {
				append("&filter[reject_genres]=")
				append(filter.tagsExclude.joinToString(",") { it.key })
			}

			append("&page=$page")
		}
		// --- (Kết thúc phần xây dựng URL) ---

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc) // Gọi hàm parseMangaList đã chỉnh sửa
	}

	/**
	 * Phân tích danh sách truyện từ trang HTML.
	 * Hàm này đã được cập nhật để lấy ảnh bìa (poster) chính xác hơn.
	 */
	private fun parseMangaList(doc: Document): List<Manga> {
    // Chọn thẻ div chứa từng mục truyện bằng selector 'div.manga-vertical'
    return doc.select("div.manga-vertical").mapNotNull { element ->
        try {
            // Tìm thẻ 'a' bao quanh ảnh bìa, bắt đầu bằng "/truyen/"
            val coverLinkElement = element.selectFirst("a[href^=\"/truyen/\"]")
                ?: return@mapNotNull null // Bỏ qua nếu không tìm thấy link hợp lệ

            val href = coverLinkElement.attrAsRelativeUrl("href") // Lấy đường dẫn tương đối

            // Tìm thẻ 'img' bên trong thẻ 'a' của ảnh bìa
            val imgElement = coverLinkElement.selectFirst("div.cover-frame img.cover")
                ?: return@mapNotNull null // Bỏ qua nếu không tìm thấy thẻ img

            // Ưu tiên lấy 'data-src' (cho lazy load), nếu không có thì lấy 'src'
            val rawCoverUrl = imgElement.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:image") }
                ?: imgElement.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:image") }
                ?: return@mapNotNull null // Bỏ qua nếu không có URL ảnh bìa hợp lệ

            // --- ✨ Bổ sung logic chuẩn hóa URL ảnh bìa ---
            var finalCoverUrl = rawCoverUrl.trim() // Loại bỏ khoảng trắng thừa

            if (finalCoverUrl.startsWith("//")) {
                // Xử lý URL bắt đầu bằng // (protocol-relative)
                finalCoverUrl = "https:$finalCoverUrl"
            } else if (finalCoverUrl.startsWith("/") && finalCoverUrl.contains("mgcdnxyz.cfd")) {
                 // Xử lý URL sai định dạng: bắt đầu bằng / nhưng chứa domain CDN
                 // Chỉ cần thêm https: vào đầu (trình duyệt tự xử lý //)
                 finalCoverUrl = "https:$finalCoverUrl"
                 // Hoặc cách khác: finalCoverUrl = "https://" + finalCoverUrl.drop(1) // Bỏ dấu / đầu tiên
            } else if (finalCoverUrl.startsWith("/")) {
                 // Xử lý URL tương đối bắt đầu bằng / (không chứa domain CDN)
                 finalCoverUrl = "https://$domain$finalCoverUrl" // Ghép với domain chính
            } else if (!finalCoverUrl.startsWith("http:") && !finalCoverUrl.startsWith("https:")) {
                 // Trường hợp URL không có http/https và không bắt đầu bằng / hoặc //
                 // Ghi log cảnh báo và coi như không hợp lệ
                 System.err.println("Định dạng URL ảnh bìa không xác định: $finalCoverUrl cho truyện $href")
                 return@mapNotNull null // Coi như không hợp lệ
            }
            // --- Kết thúc logic chuẩn hóa ---

            // Lấy tiêu đề: Ưu tiên thuộc tính 'alt' của ảnh, nếu không có thì lấy text của link tiêu đề bên dưới
            val title = imgElement.attr("alt").takeIf { it.isNotBlank() }
                ?: element.selectFirst("div.p-3 h3 a")?.text()?.takeIf { it.isNotBlank() }
                ?: "Không có tiêu đề" // Tiêu đề mặc định nếu không tìm thấy

            Manga(
                id = generateUid(href),
                title = title.trim(), // Loại bỏ khoảng trắng thừa ở tiêu đề
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = finalCoverUrl, // ✨ Sử dụng URL đã được chuẩn hóa
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        } catch (e: Exception) {
            // Ghi log lỗi hoặc xử lý theo cách phù hợp
            System.err.println("Lỗi khi phân tích mục truyện: ${e.message} - Element HTML: ${element.outerHtml().take(200)}") // Log thêm HTML để dễ debug
            null // Trả về null để bỏ qua mục này nếu có lỗi xảy ra
        }
    }


	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		// --- (Phần lấy thông tin chi tiết: altTitles, tags, state giữ nguyên như code gốc) ---
		val altTitles = doc.select("div.mt-2:contains(Tên khác:) span").mapNotNullToSet { it.textOrNull() }
		val allTags = availableTags.getOrNull().orEmpty()
		val tags = doc.select("div.mt-2:contains(Thể loại:) a").mapNotNullToSet { a ->
			val title = a.text().toTitleCase()
			allTags.find { x -> x.title == title }
		}

		val stateText = doc.selectFirst("div.mt-2:contains(Tình trạng:) span")?.text()
		val state = when (stateText) {
			"Đang tiến hành" -> MangaState.ONGOING
			"Đã hoàn thành" -> MangaState.FINISHED // Thêm trường hợp này nếu có
			else -> null // Hoặc MangaState.UNKNOWN tùy logic của bạn
		}
		// --- (Kết thúc phần lấy thông tin chi tiết) ---


		val chapterListDiv = doc.selectFirst("ul#chapterList")
			?: throw ParseException("Không tìm thấy danh sách chapter!", url)

		val chapterLinks = chapterListDiv.select("a.block")
		val chapters = chapterLinks.mapChapters(reversed = true) { index, a ->
			val title = a.selectFirst("span.text-ellipsis")?.textOrNull()
			val href = a.attrAsRelativeUrl("href")
			val uploadDateText = a.selectFirst("span.ml-2.whitespace-nowrap")?.text()

			MangaChapter(
				id = generateUid(href),
				title = title,
				number = index + 1f, // Hoặc logic lấy số chapter khác nếu có
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseChapterDate(uploadDateText), // Gọi hàm parseChapterDate
				branch = null,
				source = source,
			)
		}

		// Lấy mô tả truyện (nếu có)
		val description = doc.selectFirst("div.manga-info p.description")?.text() // Thay selector nếu cần

		return manga.copy(
			altTitles = altTitles,
			tags = tags,
			state = state,
			chapters = chapters,
			description = description // Thêm mô tả vào đối tượng Manga
			// Thêm các trường khác nếu cần: authors, artists...
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url).parseHtml()

        // Ưu tiên 1: Tìm script chứa fallbackUrls
        doc.selectFirst("script:containsData(window.encryptionConfig)")?.data()?.let { scriptContent ->
            // Regex để trích xuất mảng JSON của fallbackUrls
            val fallbackUrlsRegex = Regex(""""fallbackUrls"\s*:\s*(\[.*?])""")
            val arrayString = fallbackUrlsRegex.find(scriptContent)?.groupValues?.getOrNull(1)

            if (arrayString != null) {
                // Regex để trích xuất các URL ảnh từ chuỗi JSON (xử lý cả \/ )
                val urlRegex = Regex("""["'](https?:\\?/\\?[^"']+\.(?:jpg|jpeg|png|webp|gif))["']""")
                val scriptImages = urlRegex.findAll(arrayString).map {
                    it.groupValues[1].replace("\\/", "/") // Thay thế \/ thành /
                }.toList()

                if (scriptImages.isNotEmpty()) {
                    return scriptImages.mapIndexed { index, imgUrl -> // Thêm index để tạo ID duy nhất hơn
                        MangaPage(id = generateUid("${chapter.id}_page_${index + 1}"), url = imgUrl, preview = null, source = source)
                    }
                }
            }
        }

        // Ưu tiên 2: Tìm các thẻ img trong div#chapter-content
        val tagImagePages = doc.select("div#chapter-content img").mapNotNull { img ->
            // Thử lấy 'src' hoặc 'data-src', ưu tiên 'abs:' để có URL tuyệt đối
            val imageUrl = (img.attr("abs:src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img.attr("abs:data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") })
                ?.trim() // Loại bỏ khoảng trắng thừa

            imageUrl?.let {
                MangaPage(id = generateUid(it), url = it, preview = null, source = source)
            }
        }

        if (tagImagePages.isNotEmpty()) {
            return tagImagePages
        }

        // Nếu cả hai cách đều không thành công
        throw ParseException("Không tìm thấy ảnh chapter nào cho: ${chapter.url}", url)
    }

	// --- Các hàm tiện ích ---

	/**
	 * Phân tích chuỗi ngày tháng tương đối hoặc tuyệt đối thành timestamp (Long).
	 */
	private fun parseChapterDate(date: String?): Long {
		if (date.isNullOrBlank()) return 0L // Trả về 0 nếu chuỗi rỗng hoặc null

		// Sử dụng Calendar để xử lý ngày tháng tương đối chính xác hơn
		val calendar = Calendar.getInstance()

		return try {
			when {
				date.contains("giây trước") -> {
					val seconds = date.substringBefore(" giây trước").toLongOrNull() ?: 0
					calendar.add(Calendar.SECOND, -seconds.toInt())
					calendar.timeInMillis
				}
				date.contains("phút trước") -> {
					val minutes = date.substringBefore(" phút trước").toLongOrNull() ?: 0
					calendar.add(Calendar.MINUTE, -minutes.toInt())
					calendar.timeInMillis
				}
				date.contains("giờ trước") -> {
					val hours = date.substringBefore(" giờ trước").toLongOrNull() ?: 0
					calendar.add(Calendar.HOUR_OF_DAY, -hours.toInt())
					calendar.timeInMillis
				}
				date.contains("ngày trước") -> {
					val days = date.substringBefore(" ngày trước").toLongOrNull() ?: 0
					calendar.add(Calendar.DAY_OF_YEAR, -days.toInt())
					calendar.timeInMillis
				}
				date.contains("tuần trước") -> {
					val weeks = date.substringBefore(" tuần trước").toLongOrNull() ?: 0
					calendar.add(Calendar.WEEK_OF_YEAR, -weeks.toInt())
					calendar.timeInMillis
				}
				date.contains("tháng trước") -> {
					val months = date.substringBefore(" tháng trước").toLongOrNull() ?: 0
					calendar.add(Calendar.MONTH, -months.toInt())
					calendar.timeInMillis
				}
				date.contains("năm trước") -> {
					val years = date.substringBefore(" năm trước").toLongOrNull() ?: 0
					calendar.add(Calendar.YEAR, -years.toInt())
					calendar.timeInMillis
				}
				// Thử phân tích định dạng dd/MM/yyyy
				else -> SimpleDateFormat("dd/MM/yyyy", Locale.US).parse(date)?.time ?: 0L
			}
		} catch (e: Exception) {
			// Ghi log lỗi nếu cần
			System.err.println("Lỗi parse ngày: '$date' - ${e.message}")
			0L // Trả về 0 nếu có lỗi xảy ra
		}
	}


	/**
	 * Lấy danh sách các thể loại có sẵn từ trang tìm kiếm.
	 */
	private suspend fun fetchTags(): Set<MangaTag> {
		val url = "https://$domain/tim-kiem"
		return try {
			val doc = webClient.httpGet(url).parseHtml()
			// Tìm các thẻ 'a' trong phần danh sách thể loại (cần kiểm tra selector chính xác)
			// Ví dụ: dựa trên HTML bạn cung cấp, có thể là các thẻ 'a' bên trong 'ul' có nhiều 'li'
			val genreLinks = doc.select("ul.grid.grid-cols-2 a[href^='/the-loai/'], ul[x-show='open'] a[href^='/the-loai/']") // Kết hợp selector cho cả mobile và desktop

			genreLinks.mapNotNullToSet { a ->
				val href = a.attr("href") // Ví dụ: /the-loai/16
				val key = href.substringAfterLast('/') // Lấy phần số hoặc slug sau dấu '/' cuối cùng
				val title = a.text()?.toTitleCase(sourceLocale) // Lấy text bên trong thẻ 'a'
				if (key.isNotBlank() && !title.isNullOrBlank()) {
					MangaTag(
						key = key,
						title = title,
						source = source,
					)
				} else {
					null
				}
			}
		} catch (e: Exception) {
			// Ghi log lỗi và trả về set rỗng nếu không fetch được tags
			System.err.println("Lỗi khi fetch tags từ $url: ${e.message}")
			emptySet()
		}
	}
}
