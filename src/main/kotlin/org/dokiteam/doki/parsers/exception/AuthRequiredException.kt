package org.dokiteam.doki.parsers.exception

import okio.IOException
import org.dokiteam.doki.parsers.InternalParsersApi
import org.dokiteam.doki.parsers.model.MangaSource

/**
 * Authorization is required for access to the requested content
 */
public class AuthRequiredException @InternalParsersApi @JvmOverloads constructor(
    public val source: MangaSource,
    cause: Throwable? = null,
) : IOException("Authorization required", cause)
