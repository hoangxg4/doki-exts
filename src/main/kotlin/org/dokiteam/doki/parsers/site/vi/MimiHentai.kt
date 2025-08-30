package org.dokiteam.doki.parsers.site.vi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.network.UserAgents
import org.dokiteam.doki.parsers.util.*
import org.dokiteam.doki.parsers.util.json.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

object ServerManager {
    private var server: MimiHentaiImageServer? = null
    const val PORT = 60158

    @Synchronized
    fun start() {
        if (server == null) {
            try {
                server = MimiHentaiImageServer(PORT)
                server!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            } catch (e: IOException) {
                e.printStackTrace()
                server = null
            }
        }
    }
}

private class MimiHentaiImageServer(port: Int) : NanoHTTPD(port) {
    private val imageClient = OkHttpClient()
    private val secretKey = "DEA55A4327223B999CE141CE3BA5D"

    private val imageProcessingExecutor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    )

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val imageCache = object : LruCache<String, ByteArray>(cacheSize) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size / 1024
    }

    private val MIME_WEBP = "image/webp"

    override fun serve(session: IHTTPSession): Response {
        val params = session.parameters
        val imageUrl = params["url"]?.first()
        val drm = params["drm"]?.first()

        if (imageUrl == null || drm == null) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "Missing 'url' or 'drm' parameter"
            )
        }

        val cacheKey = drm
        imageCache.get(cacheKey)?.let { cachedBytes ->
            return newFixedLengthResponse(
                Response.Status.OK, MIME_WEBP,
                ByteArrayInputStream(cachedBytes), cachedBytes.size.toLong()
            )
        }

        val future = imageProcessingExecutor.submit<Response> {
            try {
                imageCache.get(cacheKey)?.let { cachedBytes ->
                    return@submit newFixedLengthResponse(
                        Response.Status.OK, MIME_WEBP,
                        ByteArrayInputStream(cachedBytes), cachedBytes.size.toLong()
                    )
                }

                val imageRequest = Request.Builder().url(imageUrl).build()
                val imageResponse = imageClient.newCall(imageRequest).execute()
                val scrambledBytes = imageResponse.body?.bytes() ?: throw IOException("Empty response body")
                val originalBitmap = BitmapFactory.decodeByteArray(scrambledBytes, 0, scrambledBytes.size)
                    ?: throw IOException("Failed to decode bitmap")

                val unscrambledBitmap = unscrambleImage(originalBitmap, drm, secretKey)

                val outputStream = ByteArrayOutputStream()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    unscrambledBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, outputStream)
                } else {
                    @Suppress("DEPRECATION")
                    unscrambledBitmap.compress(Bitmap.CompressFormat.WEBP, 90, outputStream)
                }
                val descrambledBytes = outputStream.toByteArray()
                imageCache.put(cacheKey, descrambledBytes)

                newFixedLengthResponse(
                    Response.Status.OK, MIME_WEBP,
                    ByteArrayInputStream(descrambledBytes), descrambledBytes.size.toLong()
                )
            } catch (e: Exception) {
                e.printStackTrace()
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "text/plain", "Processing failed inside task: ${e.message}"
                )
            }
        }
        
        return try {
            future.get()
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "Failed to get future result: ${e.message}"
            )
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
		val cleanHex = hex.removePrefix("0x")
		return ByteArray(cleanHex.length / 2) { i ->
			cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
		}
	}

	private fun xorDecryptHexWithKey(hexCipher: String, keyStr: String): String {
		val ct = hexToBytes(hexCipher)
		val key = keyStr.encodeToByteArray()
		val result = ByteArray(ct.size) { i -> (ct[i].toInt() xor key[i % key.size].toInt()).toByte() }
		return result.toString(Charsets.UTF_8)
	}

	private fun parseMetadata(meta: String): JSONObject {
		val out = JSONObject()
		val pos = JSONObject()
		val dims = JSONObject()
		var sw = 0
		var sh = 0
		for (t in meta.split("|")) {
			when {
				t.startsWith("sw:") -> sw = t.substring(3).toInt()
				t.startsWith("sh:") -> sh = t.substring(3).toInt()
				t.contains("@") && t.contains(">") -> {
					val (L, R) = t.split(">")
					val (n, r) = L.split("@")
					val (x, y, w, h) = r.split(",").map { it.toInt() }
					dims.put(n, JSONObject().apply {
						put("x", x); put("y", y); put("width", w); put("height", h)
					})
					pos.put(n, R)
				}
			}
		}
		out.put("sw", sw)
		out.put("sh", sh)
		out.put("pos", pos)
		out.put("dims", dims)
		return out
	}

	private fun unscrambleImage(bitmap: Bitmap, drm: String, key: String): Bitmap {
		val decrypted = xorDecryptHexWithKey(drm, key)
		val metadata = parseMetadata(decrypted)
		val sw = metadata.optInt("sw")
		val sh = metadata.optInt("sh")
		if (sw <= 0 || sh <= 0) return bitmap

		val fullW = bitmap.width
		val fullH = bitmap.height

		val working = Bitmap.createBitmap(bitmap, 0, 0, sw, sh)

		val keys = arrayOf("00", "01", "02", "10", "11", "12", "20", "21", "22")
		val W = sw / 3
		val H = sh / 3
		val rw = sw % 3
		val rh = sh % 3
		val defaultDims = HashMap<String, IntArray>().apply {
			for (k in keys) {
				val i = k[0].digitToInt()
				val j = k[1].digitToInt()
				val w = W + if (j == 2) rw else 0
				val h = H + if (i == 2) rh else 0
				put(k, intArrayOf(j * W, i * H, w, h))
			}
		}
		val dimsJson = metadata.optJSONObject("dims") ?: JSONObject()
		val dims = HashMap<String, IntArray>().apply {
			for (k in keys) {
				val jo = dimsJson.optJSONObject(k)
				if (jo != null) {
					put(k, intArrayOf(jo.getInt("x"), jo.getInt("y"), jo.getInt("width"), jo.getInt("height")))
				} else {
					put(k, defaultDims.getValue(k))
				}
			}
		}
		val pos = metadata.optJSONObject("pos") ?: JSONObject()
		val inv = HashMap<String, String>().apply {
			val it = pos.keys()
			while (it.hasNext()) {
				val a = it.next()
				val b = pos.getString(a)
				put(b, a)
			}
		}

		val result = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(result)

		for (k in keys) {
			val srcKey = inv[k] ?: continue
			val s = dims.getValue(k)
			val d = dims.getValue(srcKey)
			canvas.drawBitmap(
				working,
				Rect(s[0], s[1], s[0] + s[2], s[1] + s[3]),
				Rect(d[0], d[1], d[0] + d[2], d[1] + d[3]),
				null
			)
		}

		if (sh < fullH) {
			canvas.drawBitmap(bitmap, Rect(0, sh, fullW, fullH), Rect(0, sh, fullW, fullH), null)
		}
		if (sw < fullW) {
			canvas.drawBitmap(bitmap, Rect(sw, 0, fullW, sh), Rect(sw, 0, fullW, sh), null)
		}

		return result
	}
}

@MangaSourceParser("MIMIHENTAI", "MimiHentai", "vi", type = ContentType.HENTAI)
internal class MimiHentai(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MIMIHENTAI, 18) {

	private val apiSuffix = "api/v1/manga"
	override val configKeyDomain = ConfigKey.Domain("mimihentai.com", "hentaihvn.com")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)
	override val sourceLocale = Locale("vi_VN")

	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon(
					"https://raw.githubusercontent.com/dragonx943/plugin-sdk/refs/heads/sources/mimihentai/app/src/main/ic_launcher-playstore.png",
					512,
					null
				),
			),
			domain,
		)
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
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isAuthorSearchSupported = true,
			isTagsExclusionSupported = true,
		)

	init {
		setFirstPage(0)
		ServerManager.start()
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(availableTags = fetchTags())

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append("$domain/$apiSuffix")

			if (!filter.query.isNullOrEmpty() ||
				!filter.author.isNullOrEmpty() ||
				filter.tags.isNotEmpty()
			) {
				append("/advance-search?page=")
				append(page)
				append("&max=18")

				if (!filter.query.isNullOrEmpty()) {
					append("&name=")
					append(filter.query.urlEncoded())
				}

				if (!filter.author.isNullOrEmpty()) {
					append("&author=")
					append(filter.author.urlEncoded())
				}

				if (filter.tags.isNotEmpty()) {
					append("&genre=")
					append(filter.tags.joinToString(",") { it.key })
				}

				if (filter.tagsExclude.isNotEmpty()) {
					append("&ex=")
					append(filter.tagsExclude.joinToString(",") { it.key })
				}

				append("&sort=")
				append(
					when (order) {
						SortOrder.UPDATED -> "updated_at"
						SortOrder.ALPHABETICAL -> "title"
						SortOrder.POPULARITY -> "follows"
						SortOrder.POPULARITY_TODAY,
						SortOrder.POPULARITY_WEEK,
						SortOrder.POPULARITY_MONTH -> "views"
						SortOrder.RATING -> "likes"
						else -> ""
					}
				)
			} else {
				append(
					when (order) {
						SortOrder.UPDATED -> "/tatcatruyen?page=$page&sort=updated_at"
						SortOrder.ALPHABETICAL -> "/tatcatruyen?page=$page&sort=title"
						SortOrder.POPULARITY -> "/tatcatruyen?page=$page&sort=follows"
						SortOrder.POPULARITY_TODAY -> "/tatcatruyen?page=$page&sort=views"
						SortOrder.POPULARITY_WEEK -> "/top-manga?page=$page&timeType=1&limit=18"
						SortOrder.POPULARITY_MONTH -> "/top-manga?page=$page&timeType=2&limit=18"
						SortOrder.RATING -> "/tatcatruyen?page=$page&sort=likes"
						else -> "/tatcatruyen?page=$page&sort=updated_at" // default
					}
				)

				if (filter.tagsExclude.isNotEmpty()) {
					append("&ex=")
					append(filter.tagsExclude.joinToString(",") { it.key })
				}
			}
		}

		val raw = webClient.httpGet(url)
		return if (url.contains("/top-manga")) {
			val data = raw.parseJsonArray()
			parseTopMangaList(data)
		} else {
			val data = raw.parseJson().getJSONArray("data")
			parseMangaList(data)
		}
	}

	private fun parseTopMangaList(data: JSONArray): List<Manga> {
		return data.mapJSON { jo ->
			val id = jo.getLong("id")
			val title = jo.getString("title").takeIf { it.isNotEmpty() } ?: "Web chưa đặt tên"
			val description = jo.getStringOrNull("description")

			val differentNames = mutableSetOf<String>().apply {
				jo.optJSONArray("differentNames")?.let { namesArray ->
					for (i in 0 until namesArray.length()) {
						namesArray.optString(i)?.takeIf { it.isNotEmpty() }?.let { name ->
							add(name)
						}
					}
				}
			}

			val authors = jo.optJSONArray("authors")?.mapJSON {
				it.getString("name")
			}?.toSet() ?: emptySet()

			val tags = jo.optJSONArray("genres")?.mapJSON { genre ->
				MangaTag(
					key = genre.getLong("id").toString(),
					title = genre.getString("name"),
					source = source
				)
			}?.toSet() ?: emptySet()

			Manga(
				id = generateUid(id),
				title = title,
				altTitles = differentNames,
				url = "/$apiSuffix/info/$id",
				publicUrl = "https://$domain/g/$id",
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = jo.getString("coverUrl"),
				state = null,
				description = description,
				tags = tags,
				authors = authors,
				source = source,
			)
		}
	}

	private fun parseMangaList(data: JSONArray): List<Manga> {
		return data.mapJSON { jo ->
			val id = jo.getLong("id")
			val title = jo.getString("title").takeIf { it.isNotEmpty() } ?: "Web chưa đặt tên"
			val description = jo.getStringOrNull("description")

			val differentNames = mutableSetOf<String>().apply {
				jo.optJSONArray("differentNames")?.let { namesArray ->
					for (i in 0 until namesArray.length()) {
						namesArray.optString(i)?.takeIf { it.isNotEmpty() }?.let { name ->
							add(name)
						}
					}
				}
			}

			val authors = jo.getJSONArray("authors").mapJSON {
				it.getString("name")
			}.toSet()

			val tags = jo.getJSONArray("genres").mapJSON { genre ->
				MangaTag(
					key = genre.getLong("id").toString(),
					title = genre.getString("name"),
					source = source
				)
			}.toSet()

			Manga(
				id = generateUid(id),
				title = title,
				altTitles = differentNames,
				url = "/$apiSuffix/info/$id",
				publicUrl = "https://$domain/g/$id",
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = jo.getString("coverUrl"),
				state = null,
				tags = tags,
				description = description,
				authors = authors,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val json = webClient.httpGet(url).parseJson()
		val id = json.getLong("id")
		val description = json.getStringOrNull("description")
		val uploaderName = json.getJSONObject("uploader").getString("displayName")

		val tags = json.getJSONArray("genres").mapJSONToSet { jo ->
			MangaTag(
				title = jo.getString("name").toTitleCase(sourceLocale),
				key = jo.getLong("id").toString(),
				source = source,
			)
		}

		val urlChaps = "https://$domain/$apiSuffix/gallery/$id"
		val parsedChapters = webClient.httpGet(urlChaps).parseJsonArray()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.US)
		val chapters = parsedChapters.mapJSON { jo ->
			MangaChapter(
				id = generateUid(jo.getLong("id")),
				title = jo.getStringOrNull("title"),
				number = jo.getFloatOrDefault("order", 0f),
				url = "/$apiSuffix/chapter?id=${jo.getLong("id")}",
				uploadDate = dateFormat.parse(jo.getString("createdAt"))?.time ?: 0L,
				source = source,
				scanlator = uploaderName,
				branch = null,
				volume = 0,
			)
		}.reversed()

		return manga.copy(
			tags = tags,
			description = description,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val json = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseJson()
		return json.getJSONArray("pages").mapJSON { jo ->
			val imageUrl = jo.getString("imageUrl")
			val drm = jo.getStringOrNull("drm")

			val finalUrl = if (drm.isNullOrBlank()) {
				imageUrl
			} else {
				"http://127.0.0.1:${ServerManager.PORT}?url=${imageUrl.urlEncoded()}&drm=$drm"
			}
			MangaPage(
				id = generateUid(imageUrl),
				url = finalUrl,
				preview = null,
				source = source
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val url = "https://$domain/$apiSuffix/genres"
		val response = webClient.httpGet(url).parseJsonArray()
		return response.mapJSONToSet { jo ->
			MangaTag(
				title = jo.getString("name").toTitleCase(sourceLocale),
				key = jo.getLong("id").toString(),
				source = source,
			)
		}
	}
}
