package org.dokiteam.doki.parsers.model

import org.dokiteam.doki.parsers.MangaParser

public data class MangaPage(
	@JvmField public val id: Long,
	@JvmField public val url: String,
	@JvmField public val preview: String?,
	@JvmField public val source: MangaSource,
)
