package org.dokiteam.doki.parsers.core

import org.dokiteam.doki.parsers.InternalParsersApi
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.model.Manga
import org.dokiteam.doki.parsers.model.MangaListFilter
import org.dokiteam.doki.parsers.model.MangaParserSource
import org.dokiteam.doki.parsers.model.SortOrder

@InternalParsersApi
public abstract class LegacySinglePageMangaParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
) : LegacyMangaParser(context, source) {

	final override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (offset > 0) {
			return emptyList()
		}
		return getList(order, filter)
	}

	public abstract suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga>
}
