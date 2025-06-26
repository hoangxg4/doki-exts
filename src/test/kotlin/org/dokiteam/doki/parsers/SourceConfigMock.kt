package org.dokiteam.doki.parsers

import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.config.MangaSourceConfig

internal class SourceConfigMock : MangaSourceConfig {

	override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
}
