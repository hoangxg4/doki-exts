package org.dokiteam.doki.parsers.site.vi

import org.dokiteam.doki.parsers.model.RATING_UNKNOWN
import org.dokiteam.doki.parsers.util.generateUid
import org.json.JSONObject
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.LegacyPagedMangaParser
import org.dokiteam.doki.parsers.model.ContentRating
import org.dokiteam.doki.parsers.model.ContentType
import org.dokiteam.doki.parsers.model.Manga
import org.dokiteam.doki.parsers.model.MangaChapter
import org.dokiteam.doki.parsers.model.MangaListFilter
import org.dokiteam.doki.parsers.model.MangaListFilterCapabilities
import org.dokiteam.doki.parsers.model.MangaListFilterOptions
import org.dokiteam.doki.parsers.model.MangaPage
import org.dokiteam.doki.parsers.model.MangaState
import org.dokiteam.doki.parsers.model.MangaTag
import org.dokiteam.doki.parsers.model.SortOrder
import org.dokiteam.doki.parsers.util.mapChapters
import org.dokiteam.doki.parsers.util.parseHtml
import org.dokiteam.doki.parsers.util.parseJson
import org.dokiteam.doki.parsers.util.parseJsonArray
import org.dokiteam.doki.parsers.util.requireElementById
import org.dokiteam.doki.parsers.util.toAbsoluteUrl
import org.dokiteam.doki.parsers.util.tryParse
import org.dokiteam.doki.parsers.util.urlEncoded
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import org.dokiteam.doki.parsers.util.json.asTypedList
import org.dokiteam.doki.parsers.util.json.getStringOrNull
import org.dokiteam.doki.parsers.util.json.mapJSON
import org.dokiteam.doki.parsers.util.json.mapJSONToSet
import java.text.SimpleDateFormat
import java.util.*
import org.dokiteam.doki.parsers.Broken

@Broken("Original site closed")
@MangaSourceParser("YURINEKO", "YuriNeko", "vi", ContentType.HENTAI)
internal class YurinekoParser(context: MangaLoaderContext) :
	LegacyPagedMangaParser(context, MangaParserSource.YURINEKO, 20) {
	override val configKeyDomain: ConfigKey.Domain
		get() = ConfigKey.Domain("yurineko.site")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(SortOrder.UPDATED)

	private val apiDomain
		get() = "api.$domain"

	private val storageDomain
		get() = "storage.$domain"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val listUrl = when {
			!filter.query.isNullOrEmpty() -> {
				"/search?query=${filter.query.urlEncoded()}&page=$page"
			}

			else -> {
				if (filter.tags.isNotEmpty()) {
					val tagKeys = filter.tags.joinToString(separator = ",") { it.key }
					"/advancedSearch?genre=$tagKeys&notGenre=&sort=7&minChapter=1&status=0&page=$page"
				} else {
					"/lastest2?page=$page"
				}
			}
		}
		val jsonResponse = webClient.httpGet(listUrl.toAbsoluteUrl(apiDomain)).parseJson()
		return jsonResponse.getJSONArray("result")
			.mapJSON { jo ->
				val id = jo.getLong("id")
				val relativeUrl = "/manga/$id"
				val author = jo.getJSONArray("author")
					.mapJSON { author -> author.getString("name") }
					.joinToString { it }
				Manga(
					id = generateUid(id),
					title = jo.getString("originalName"),
					altTitles = setOfNotNull(jo.getStringOrNull("otherName")),
					url = relativeUrl,
					publicUrl = relativeUrl.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = ContentRating.ADULT,
					coverUrl = "https://$storageDomain/${jo.getString("thumbnail")}",
					tags = jo.getJSONArray("tag").mapJSONToSet { tag ->
						MangaTag(
							title = tag.getString("name"),
							key = tag.getInt("id").toString(),
							source = source,
						)
					},
					state = when (jo.getInt("status")) {
						2 -> MangaState.FINISHED
						1, 3, 4 -> MangaState.ONGOING
						5, 6, 7 -> MangaState.ABANDONED
						else -> null
					},
					authors = setOf(author),
					description = jo.getStringOrNull("description"),
					source = source,
				)
			}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val response = webClient.httpGet(manga.url.toAbsoluteUrl(apiDomain)).parseJson()
		val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
		return manga.copy(
			chapters = response.getJSONArray("chapters")
				.mapChapters(true) { i, jo ->
					val mangaId = jo.getInt("mangaID")
					val chapterId = jo.getInt("id")
					MangaChapter(
						id = generateUid(chapterId.toLong()),
						title = jo.getStringOrNull("name"),
						number = i + 1f,
						volume = 0,
						scanlator = null,
						url = "/read/$mangaId/$chapterId",
						uploadDate = df.tryParse(jo.getString("date")),
						branch = null,
						source = source,
					)
				},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val jsonData = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
			.requireElementById("__NEXT_DATA__")
			.data()
		return JSONObject(jsonData).getJSONObject("props")
			.getJSONObject("pageProps")
			.getJSONObject("chapterData")
			.getJSONArray("url")
			.asTypedList<String>()
			.map { url ->
				MangaPage(
					id = generateUid(url),
					url = "https://$storageDomain/$url",
					preview = null,
					source = source,
				)
			}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		return webClient.httpGet("https://$apiDomain/tag/find?query=")
			.parseJsonArray()
			.mapJSONToSet { jo ->
				MangaTag(
					key = jo.getInt("id").toString(),
					title = jo.getString("name"),
					source = source,
				)
			}
	}
}
