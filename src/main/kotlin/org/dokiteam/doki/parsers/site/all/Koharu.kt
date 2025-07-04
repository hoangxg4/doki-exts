package org.dokiteam.doki.parsers.site.all

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.LegacyPagedMangaParser
import org.dokiteam.doki.parsers.exception.ParseException
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.network.UserAgents
import org.dokiteam.doki.parsers.util.*
import org.dokiteam.doki.parsers.util.json.getIntOrDefault
import org.dokiteam.doki.parsers.util.json.getLongOrDefault
import org.dokiteam.doki.parsers.util.json.getStringOrNull
import org.dokiteam.doki.parsers.util.json.mapJSONNotNullToSet
import org.dokiteam.doki.parsers.util.suspendlazy.getOrDefault
import org.dokiteam.doki.parsers.util.suspendlazy.suspendLazy
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("KOHARU", "Schale.network", type = ContentType.HENTAI)
internal class Koharu(context: MangaLoaderContext) :
	LegacyPagedMangaParser(context, MangaParserSource.KOHARU, 24) {

	override val configKeyDomain = ConfigKey.Domain("niyaniya.moe")
	private val apiSuffix = "api.schale.network"

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOHARU)

	private val authorsIds = suspendLazy { fetchAuthorsIds() }

	private val preferredImageResolutionKey = ConfigKey.PreferredImageServer(
		presetValues = mapOf(
			"0" to "Lowest Quality",
			"780" to "Low Quality (780px)",
			"980" to "Medium Quality (980px)",
			"1280" to "High Quality (1280px)",
			"1600" to "Highest Quality (1600px)",
		),
		defaultValue = "1280",
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(preferredImageResolutionKey)
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("referer", "https://$domain/")
		.add("origin", "https://$domain")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isAuthorSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(namespace = 0),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val baseUrl = "https://$apiSuffix/books"
		val url = buildString {
			append(baseUrl)

			val terms: MutableList<String> = mutableListOf()
			val includedTags: MutableList<String> = mutableListOf()
			val excludedTags: MutableList<String> = mutableListOf()

			if (!filter.query.isNullOrEmpty() && filter.query.startsWith("id:")) {
				val ipk = filter.query.removePrefix("id:")
				val response = webClient.httpGet("$baseUrl/detail/$ipk").parseJson()
				return listOf(parseMangaDetail(response))
			}

			val sortValue = when (order) {
				SortOrder.POPULARITY, SortOrder.POPULARITY_TODAY -> "8"
				SortOrder.POPULARITY_WEEK -> "9"
				SortOrder.ALPHABETICAL -> "2"
				SortOrder.ALPHABETICAL_DESC -> "2"
				SortOrder.RATING -> "3"
				SortOrder.NEWEST -> "4"
				else -> "4"
			}
			append("?sort=").append(sortValue)

			if (!filter.query.isNullOrEmpty()) {
				terms.add("title:\"${filter.query.urlEncoded()}\"")
			}

			if (!filter.author.isNullOrEmpty()) {
				val authors = authorsIds.getOrDefault(emptyMap())
				val authorId = authors[filter.author.lowercase()]

				if (authorId != null) {
					includedTags.add(authorId)
				} else {
					terms.add("artist:\"${filter.author.urlEncoded()}\"")
				}
			}

			filter.tags.forEach { tag ->
				if (tag.key.startsWith("-")) {
					excludedTags.add(tag.key.substring(1))
				} else {
					includedTags.add(tag.key)
				}
			}

			if (excludedTags.isNotEmpty()) {
				append("&exclude=").append(excludedTags.joinToString(","))
				append("&e=1")
			}

			if (includedTags.isNotEmpty()) {
			"1280" -> listOf("1280", "1600", "0", "980", "780")
			"980" -> listOf("980", "1280", "0", "1600", "780")
			"780" -> listOf("780", "980", "0", "1280", "1600")
			else -> listOf("0", "1600", "1280", "980", "780")
		}

		var selectedImageId: Int? = null
		var selectedPublicKey: String? = null
		var selectedQuality = "0"

		for (res in resolutionOrder) {
			if (data.has(res) && !data.isNull(res)) {
				val resData = data.getJSONObject(res)
				if (resData.has("id") && resData.has("key")) {
					selectedImageId = resData.getInt("id")
					selectedPublicKey = resData.getString("key")
					selectedQuality = res
					break
				}
			}
		}

		if (selectedImageId == null || selectedPublicKey == null) {
			throw ParseException("Cant find image data", dataUrl)
		}

		val imagesResponse = webClient.httpGet(
			"https://$apiSuffix/books/data/$id/$key/$selectedImageId/$selectedPublicKey/$selectedQuality?crt=$clearance",
		).parseJson()

		val base = imagesResponse.getString("base")
		val entries = imagesResponse.getJSONArray("entries")

		val pages = ArrayList<MangaPage>(entries.length())
		for (i in 0 until entries.length()) {
			val imagePath = entries.getJSONObject(i).getString("path")
			val fullImageUrl = "$base$imagePath"

			pages.add(
				MangaPage(
					id = generateUid(fullImageUrl),
					url = fullImageUrl,
					preview = null,
					source = source,
				),
			)
		}

		return pages
	}

	private suspend fun fetchTags(namespace: Int): Set<MangaTag> =
		webClient.httpGet("https://$apiSuffix/books/tags/filters").parseJsonArray().mapJSONNotNullToSet {
			if (it.getIntOrDefault("namespace", 0) != namespace) {
				null
			} else {
				MangaTag(
					title = it.getStringOrNull("name")
						?.toTitleCase(sourceLocale) ?: return@mapJSONNotNullToSet null,
					key = it.getStringOrNull("id") ?: return@mapJSONNotNullToSet null,
					source = source,
				)
			}
		}

	private suspend fun fetchAuthorsIds(): Map<String, String> = fetchTags(namespace = 1)
		.associate { it.title.lowercase() to it.key }

	private suspend fun getClearance(chapterUrl: String): String = WebViewHelper(context)
		.getLocalStorageValue(domain, "clearance")?.removeSurrounding('"')?.nullIfEmpty()
		?: context.requestBrowserAction(this, chapterUrl)

	private fun MangaChapter.publicUrl() = "https://$domain/g/$url/read/1"
}
