package eu.kanade.tachiyomi.source

/**
 * A marker interface for sources that provide novels.
 */
interface NovelSource {
    val isNovelSource: Boolean
        get() = true

    suspend fun fetchPageText(page: eu.kanade.tachiyomi.source.model.Page): String
}
