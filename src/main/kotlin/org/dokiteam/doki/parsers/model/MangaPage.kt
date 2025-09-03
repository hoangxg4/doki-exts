package org.dokiteam.doki.parsers.model

import okhttp3.Headers // QUAN TRỌNG: Thêm dòng import này
import org.dokiteam.doki.parsers.MangaParser

public data class MangaPage(
	/**
	 * Unique identifier for page
	 */
	@JvmField public val id: Long,
	/**
	 * Relative url to page (**without** a domain) or any other uri.
	 * Used principally in parsers.
	 * May contain link to image or html page.
	 * @see MangaParser.getPageUrl
	 */
	@JvmField public val url: String,
	/**
	 * Absolute url of the small page image if exists, null otherwise
	 */
	@JvmField public val preview: String?,
	@JvmField public val source: MangaSource,
	/**
	 * Custom headers for this page request.
	 * Added for parsers that need specific headers (e.g., Referer).
	 */
	@JvmField public val headers: Headers? = null
)
