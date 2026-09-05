package eu.kanade.tachiyomi.extension.all.tachideskNovel

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Filter.Sort.Selection
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Base64

class SuwayomiNovel :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Suwayomi (Novel)"
    override val lang = "all"
    override val supportsLatest = true
    override val isNovelSource = true

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    private val plainClient by lazy { Injekt.get<NetworkHelper>().client }

    override val client = Injekt.get<NetworkHelper>().client.newBuilder()
        .authenticator { _, response ->
            if (authMode != AUTH_SIMPLE || response.priorResponse != null) return@authenticator null
            cachedToken = null
            val token = fetchToken() ?: return@authenticator null
            response.request.newBuilder().header("Authorization", "Bearer $token").build()
        }
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun requireBaseUrl(): String =
        baseUrl.ifEmpty { throw IllegalStateException("Set the Suwayomi server URL in the source settings") }

    private fun JsonObject.dataOrThrow(): JsonObject {
        this["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?.let { throw Exception("Server error: $it") }
        return this["data"]?.jsonObject ?: throw Exception("Empty response from server")
    }

    override val baseUrl: String
        get() {
            val raw = preferences.getString(PREF_URL, DEFAULT_URL).orEmpty().trim().trimEnd('/')
            return when {
                raw.isEmpty() -> ""
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                else -> "http://$raw"
            }
        }

    private val authMode get() = preferences.getString(PREF_AUTH_MODE, AUTH_NONE).orEmpty()
    private val resultsPerPage get() = preferences.getString(PREF_RESULTS, "40")?.toIntOrNull() ?: 40
    private val fetchFromSource get() = preferences.getBoolean(PREF_FETCH_FROM_SOURCE, false)
    private val mentionSource get() = preferences.getBoolean(PREF_MENTION_SOURCE, false)

    @Volatile
    private var cachedToken: String? = null

    private var categoriesCache: List<Pair<Int, String>> = emptyList()
    private var tagsCache: List<String> = emptyList()

    // Browse state stashed between *Request and *Parse (browse is single-flight per source).
    private var browsePage = 1
    private var browseQuery = ""
    private var browseCategoryId: Int? = null
    private var browseSortIndex = 0
    private var browseSortAscending = true
    private var browseTagInclude = emptyList<String>()
    private var browseTagExclude = emptyList<String>()
    private var browseTagIncludeAnd = true
    private var browseTagExcludeOr = true

    // ------------- Auth -------------

    override fun headersBuilder(): Headers.Builder {
        val builder = Headers.Builder()
        when (authMode) {
            AUTH_BASIC -> {
                val user = preferences.getString(PREF_USER, "").orEmpty()
                if (user.isNotBlank()) {
                    val pass = preferences.getString(PREF_PASS, "").orEmpty()
                    val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
                    builder.add("Authorization", "Basic $token")
                }
            }
            AUTH_SIMPLE -> {
                val token = cachedToken ?: fetchToken()
                if (token != null) builder.add("Authorization", "Bearer $token")
            }
        }
        return builder
    }

    // Logs in with username/password to obtain a JWT. Uses a plain client to avoid recursion.
    private fun fetchToken(): String? {
        val user = preferences.getString(PREF_USER, "").orEmpty()
        val pass = preferences.getString(PREF_PASS, "").orEmpty()
        if (user.isBlank()) return null

        val variables = buildJsonObject {
            put("u", user)
            put("p", pass)
        }
        val request = graphqlRequest(LOGIN_MUTATION, variables, plain = true)
        return runCatching {
            val body = plainClient.newCall(request).execute().body.string()
            val token = json.parseToJsonElement(body)
                .jsonObject["data"]?.jsonObject
                ?.get("login")?.jsonObject
                ?.get("accessToken")?.jsonPrimitive?.contentOrNull
            cachedToken = token
            token
        }.getOrNull()
    }

    private fun graphqlRequest(query: String, variables: JsonObject? = null, plain: Boolean = false): Request {
        val payload = buildJsonObject {
            put("query", query)
            if (variables != null) put("variables", variables)
        }.toString()
        val requestHeaders = if (plain) Headers.headersOf() else headers
        return POST("${requireBaseUrl()}/api/graphql", requestHeaders, payload.toRequestBody(JSON_MEDIA))
    }

    // ------------- Browse / Search -------------

    override fun popularMangaRequest(page: Int): Request {
        browsePage = page
        browseQuery = ""
        browseCategoryId = null
        browseSortIndex = 0
        browseSortAscending = true
        browseTagInclude = emptyList()
        browseTagExclude = emptyList()
        browseTagIncludeAnd = true
        browseTagExcludeOr = true
        return browseRequest()
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        browsePage = page
        browseQuery = query
        browseCategoryId = (filters.firstOrNull { it is CategoryFilter } as? CategoryFilter)
            ?.let { it.categories.getOrNull(it.state)?.first?.takeIf { id -> id >= 0 } }

        (filters.firstOrNull { it is SortFilter } as? SortFilter)?.state?.let {
            browseSortIndex = it.index
            browseSortAscending = it.ascending
        }
        (filters.firstOrNull { it is TagModeGroup } as? TagModeGroup)?.state?.forEach { mode ->
            when (mode.name) {
                "Include" -> browseTagIncludeAnd = mode.state == 0
                "Exclude" -> browseTagExcludeOr = mode.state == 1
            }
        }
        (filters.firstOrNull { it is TagSelector } as? TagSelector)?.state?.let { tags ->
            browseTagInclude = tags.filter { it.isIncluded() }.map { it.name }
            browseTagExclude = tags.filter { it.isExcluded() }.map { it.name }
        }
        return browseRequest()
    }

    // Server returns only novels (isNovel: true); category is filtered client-side.
    private fun browseRequest(): Request = graphqlRequest(LIBRARY_QUERY)

    override fun popularMangaParse(response: Response): MangasPage {
        val root = json.parseToJsonElement(response.body.string()).jsonObject.dataOrThrow()
        val nodes = root["mangas"]?.jsonObject?.get("nodes")?.jsonArray ?: emptyList()

        // Categories arrive lazily so the filter can populate after the first browse.
        if (categoriesCache.isEmpty()) refreshCategories()

        val novels = nodes.map { it.jsonObject }.filter { obj ->
            // isNovel guard kept for servers predating the mangas(isNovel:) argument.
            val isNovel = obj["source"]?.jsonObject?.get("isNovel")?.jsonPrimitive?.boolean ?: true
            if (!isNovel) return@filter false
            val categoryId = browseCategoryId ?: return@filter true
            obj["categories"]?.jsonObject?.get("nodes")?.jsonArray
                ?.any { it.jsonObject["id"]?.jsonPrimitive?.int == categoryId } ?: false
        }

        // Tag list reflects only the currently displayed novels, mirroring the manga source.
        tagsCache = novels.flatMap { it.genreList() }
            .distinctBy { it.lowercase() }
            .filter { it.isNotBlank() }
            .sortedBy { it.lowercase() }

        val filtered = novels.filter { obj ->
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@filter false
            if (browseQuery.isNotBlank() && !title.contains(browseQuery, ignoreCase = true)) return@filter false
            val genres = obj.genreList().map { it.lowercase() }
            val include = browseTagInclude.map { it.lowercase() }
            val exclude = browseTagExclude.map { it.lowercase() }
            val includeOk = include.isEmpty() ||
                if (browseTagIncludeAnd) include.all { it in genres } else include.any { it in genres }
            val excludeOk = exclude.isEmpty() ||
                if (browseTagExcludeOr) exclude.none { it in genres } else !exclude.all { it in genres }
            includeOk && excludeOk
        }

        val sorted = filtered.sortedWith(sortComparator(browseSortIndex))
            .let { if (browseSortAscending) it else it.asReversed() }

        val all = sorted.mapNotNull { obj ->
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SManga.create().apply {
                this.title = title
                url = obj["id"]!!.jsonPrimitive.int.toString()
                thumbnail_url = obj["thumbnailUrl"]?.jsonPrimitive?.contentOrNull?.let(::absUrl)
            }
        }

        val from = (browsePage - 1) * resultsPerPage
        val pageItems = all.drop(from).take(resultsPerPage)
        return MangasPage(pageItems, from + pageItems.size < all.size)
    }

    private fun JsonObject.genreList(): List<String> = this["genre"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    private fun JsonObject.longField(vararg path: String): Long {
        var cur: JsonObject? = this
        for (i in 0 until path.size - 1) cur = cur?.get(path[i]) as? JsonObject
        return (cur?.get(path.last()) as? JsonPrimitive)?.longOrNull ?: 0L
    }

    private fun sortComparator(index: Int): Comparator<JsonObject> = when (SORT_OPTIONS.getOrElse(index) { "Title" }) {
        "Title" -> compareBy { it["title"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty() }
        "Artist" -> compareBy { it["artist"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty() }
        "Author" -> compareBy { it["author"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty() }
        "Date added" -> compareBy { it.longField("inLibraryAt") }
        "Total chapters" -> compareBy { it.longField("chapters", "totalCount") }
        "Latest uploaded chapter" -> compareBy { it.longField("latestUploadedChapter", "uploadDate") }
        "Latest fetched chapter" -> compareBy { it.longField("latestFetchedChapter", "fetchedAt") }
        "Recently read" -> compareBy { it.longField("lastReadChapter", "lastReadAt") }
        "Unread chapters" -> compareBy { it.longField("unreadCount") }
        "Downloaded chapters" -> compareBy { it.longField("downloadCount") }
        else -> compareBy { it["title"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty() }
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private fun refreshCategories() {
        runCatching {
            val body = client.newCall(graphqlRequest(CATEGORIES_QUERY)).execute().body.string()
            categoriesCache = json.parseToJsonElement(body)
                .jsonObject["data"]!!.jsonObject["categories"]!!.jsonObject["nodes"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.int to it.jsonObject["name"]!!.jsonPrimitive.content }
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Tag list shows only the tags of currently displayed novels."),
        CategoryFilter(categoriesCache),
        Filter.Separator(),
        TagModeGroup(),
        TagSelector(tagsCache),
        SortFilter(),
    )

    private class CategoryFilter(rawCategories: List<Pair<Int, String>>) :
        Filter.Select<String>(
            "Category",
            (listOf(-1 to "All") + rawCategories).map { it.second }.toTypedArray(),
        ) {
        val categories: List<Pair<Int, String>> = listOf(-1 to "All") + rawCategories
    }

    private class SortFilter : Filter.Sort("Sort by", SORT_OPTIONS.toTypedArray(), Selection(0, true))

    private class Tag(name: String) : Filter.TriState(name)

    private class TagSelector(tags: List<String>) : Filter.Group<Tag>("Tags", tags.map { Tag(it) })

    private class TagMode(name: String, default: Int) : Filter.Select<String>(name, arrayOf("AND", "OR"), default)

    private class TagModeGroup : Filter.Group<TagMode>("Tag filter modes", listOf(TagMode("Include", 0), TagMode("Exclude", 1)))

    // ------------- Details / Chapters -------------

    override fun mangaDetailsRequest(manga: SManga): Request = graphqlRequest(MANGA_DETAILS_QUERY.replace("\$ID", manga.url))

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject.dataOrThrow()["manga"]?.jsonObject
            ?: throw Exception("Novel not found")
        return SManga.create().apply {
            title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            author = obj["author"]?.jsonPrimitive?.contentOrNull
            artist = obj["artist"]?.jsonPrimitive?.contentOrNull
            genre = obj["genre"]?.jsonArray?.joinToString { it.jsonPrimitive.content }
            thumbnail_url = obj["thumbnailUrl"]?.jsonPrimitive?.contentOrNull?.let(::absUrl)
            status = SManga.UNKNOWN

            val baseDescription = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            description = if (mentionSource) {
                val sourceName = obj["source"]?.jsonObject?.get("displayName")?.jsonPrimitive?.contentOrNull
                if (sourceName != null) "Source: $sourceName\n\n$baseDescription" else baseDescription
            } else {
                baseDescription
            }
        }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val online = if (fetchFromSource) "?onlineFetch=true" else ""
        val body = client.newCall(GET("${requireBaseUrl()}/api/v1/manga/${manga.url}/chapters$online", headers))
            .execute().body.string()
        val array = json.parseToJsonElement(body) as? JsonArray
            ?: throw Exception("Unexpected chapter list response from server")
        array.map { node ->
            val obj = node.jsonObject
            val index = obj["index"]!!.jsonPrimitive.int
            SChapter.create().apply {
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Chapter"
                url = "${manga.url}/$index"
                chapter_number = obj["chapterNumber"]?.jsonPrimitive?.floatOrNull ?: -1f
                date_upload = obj["uploadDate"]?.jsonPrimitive?.longOrNull ?: 0L
            }
        }.sortedByDescending { it.url.substringAfterLast('/').toIntOrNull() ?: Int.MIN_VALUE }
    }

    // ------------- Pages / Text -------------

    override suspend fun fetchPageText(page: Page): String {
        val parts = page.url.split("/")
        val mangaId = parts.getOrNull(0).orEmpty()
        val chapterIndex = parts.getOrNull(1).orEmpty()
        return client
            .newCall(GET("${requireBaseUrl()}/api/v1/manga/$mangaId/chapter/$chapterIndex/text", headers))
            .execute()
            .body
            .string()
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split("/")
        return "$baseUrl/manga/${parts[0]}/chapter/${parts.getOrElse(1) { "" }}"
    }

    private fun absUrl(path: String): String = if (path.startsWith("http")) path else "$baseUrl$path"

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ------------- Preferences -------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context

        EditTextPreference(ctx).apply {
            key = PREF_URL
            title = "Server address"
            summary = "e.g. http://192.168.1.10:4567"
            setDefaultValue(DEFAULT_URL)
            dialogTitle = "Server address"
        }.also(screen::addPreference)

        ListPreference(ctx).apply {
            key = PREF_AUTH_MODE
            title = "Authentication"
            entries = arrayOf("None", "Basic auth", "Simple login (token)")
            entryValues = arrayOf(AUTH_NONE, AUTH_BASIC, AUTH_SIMPLE)
            setDefaultValue(AUTH_NONE)
            summary = "%s"
            setOnPreferenceChangeListener { _, _ ->
                cachedToken = null
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(ctx).apply {
            key = PREF_USER
            title = "Username"
            summary = "For Basic auth or Simple login"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, _ ->
                cachedToken = null
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(ctx).apply {
            key = PREF_PASS
            title = "Password"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, _ ->
                cachedToken = null
                true
            }
        }.also(screen::addPreference)

        ListPreference(ctx).apply {
            key = PREF_RESULTS
            title = "Results per page"
            entries = arrayOf("20", "40", "60", "100")
            entryValues = arrayOf("20", "40", "60", "100")
            setDefaultValue("40")
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_FETCH_FROM_SOURCE
            title = "Fetch chapters from source"
            summary = "Re-fetch the chapter list from the original source instead of the server cache"
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_MENTION_SOURCE
            title = "Mention source in description"
            summary = "Prefix each novel's description with its original source name"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_URL = "SERVER_URL"
        private const val PREF_AUTH_MODE = "AUTH_MODE"
        private const val PREF_USER = "SERVER_USER"
        private const val PREF_PASS = "SERVER_PASS"
        private const val PREF_RESULTS = "RESULTS_PER_PAGE"
        private const val PREF_FETCH_FROM_SOURCE = "FETCH_FROM_SOURCE"
        private const val PREF_MENTION_SOURCE = "MENTION_SOURCE"

        private const val AUTH_NONE = "none"
        private const val AUTH_BASIC = "basic"
        private const val AUTH_SIMPLE = "simple"

        private const val DEFAULT_URL = "http://127.0.0.1:4567"
        private val JSON_MEDIA = "application/json".toMediaType()

        private val SORT_OPTIONS = listOf(
            "Title",
            "Artist",
            "Author",
            "Date added",
            "Total chapters",
            "Latest uploaded chapter",
            "Latest fetched chapter",
            "Recently read",
            "Unread chapters",
            "Downloaded chapters",
        )

        private const val BROWSE_FIELDS =
            "id title thumbnailUrl artist author genre inLibraryAt unreadCount downloadCount " +
                "chapters { totalCount } lastReadChapter { lastReadAt } " +
                "latestUploadedChapter { uploadDate } latestFetchedChapter { fetchedAt } " +
                "categories { nodes { id } } source { isNovel }"

        private val LIBRARY_QUERY =
            "{ mangas(condition: { inLibrary: true }, isNovel: true) { nodes { $BROWSE_FIELDS } } }"
        private const val CATEGORIES_QUERY = "{ categories { nodes { id name } } }"
        private const val MANGA_DETAILS_QUERY =
            "{ manga(id: \$ID) { title author artist description genre thumbnailUrl source { displayName } } }"
        private const val LOGIN_MUTATION =
            "mutation(\$u: String!, \$p: String!) { login(input: { username: \$u, password: \$p }) { accessToken } }"
    }
}
