package org.dokiteam.doki.parsers.core

import org.dokiteam.doki.parsers.InternalParsersApi
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.model.Manga
import org.dokiteam.doki.parsers.model.MangaParserSource
import org.dokiteam.doki.parsers.model.search.MangaSearchQuery

@InternalParsersApi
public abstract class SinglePageMangaParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
) : AbstractMangaParser(context, source) {

	final override suspend fun getList(query: MangaSearchQuery): List<Manga> {
		if (query.offset > 0) {
			return emptyList()
		}
		return getSinglePageList(query)
	}

	public abstract suspend fun getSinglePageList(searchQuery: MangaSearchQuery): List<Manga>
}
