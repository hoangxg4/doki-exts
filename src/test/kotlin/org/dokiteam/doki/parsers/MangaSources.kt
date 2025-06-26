package org.dokiteam.doki.parsers

import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE
import org.dokiteam.doki.parsers.model.MangaParserSource

@EnumSource(MangaParserSource::class, names = ["DUMMY"], mode = EXCLUDE)
internal annotation class MangaSources
