package eu.kanade.tachiyomi.extension.all.tachidesk

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.apollographql.apollo3.network.okHttpClient
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.GetCategoriesQuery
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.GetChapterIdQuery
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.GetChaptersMutation
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.GetMangaMutation
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.GetPagesMutation
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.SearchMangaQuery
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.fragment.CategoryFragment
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.fragment.ChapterFragment
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.fragment.MangaFragment
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.type.IntFilterInput
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.type.MangaFilterInput
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.type.MangaStatus
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.type.StringFilterInput
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Filter.Sort.Selection
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit
import kotlin.CharSequence
import kotlin.collections.any
import kotlin.math.min

class Tachidesk : ConfigurableSource, UnmeteredSource, HttpSource() {
    override val name = "Suwayomi"
    override val id = 3100117499901280806L

    private val json: Json by lazy { Json { ignoreUnknownKeys = true } }

    private inner class OkAuthorizationInterceptor(private val tokenManager: Lazy<TokenManager>) : Interceptor {
        private inner class UnauthorizedException(val err: String) : Exception(err)
        private val authMutex = Mutex()

        override fun intercept(chain: Interceptor.Chain): Response {
            val oldToken = tokenManager.value.token()
            return try {
                val response = chain.proceed(with(tokenManager.value) { chain.request().newBuilder().addToken() }.build())
                if (response.isUnauthorized()) {
                    response.close()
                    runBlocking {
                        authMutex.withLock { tokenManager.value.refresh(oldToken) }
                    }
                    throw UnauthorizedException("Unauthorized")
                } else {
                    response
                }
            } catch (_: UnauthorizedException) {
                Log.i(TAG, "Was Unauthorizied, re-running with new token")
                chain.proceed(with(tokenManager.value) { chain.request().newBuilder().addToken() }.build())
            }
        }

        private fun Response.isUnauthorized(): Boolean = this.code == 401 || this.isGraphQLUnauthorized()
        private fun Response.isGraphQLUnauthorized(): Boolean {
            @Serializable
            data class Error(val message: String)

            @Serializable
            data class Outer(val errors: List<Error>)

            return try {
                val body = json.decodeFromStream<Outer>(this.peekBody(Long.MAX_VALUE).byteStream())
                body.errors.any { it.message.contains("suwayomi.tachidesk.server.user.UnauthorizedException") || it.message == "Unauthorized" }
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun createApolloClient(serverUrl: String): ApolloClient {
        return ApolloClient.Builder()
            .serverUrl("$serverUrl/api/graphql")
            .okHttpClient(client)
            .build()
    }

    override val baseUrl by lazy { getPrefBaseUrl() }
    private val apolloClient = lazy { createApolloClient(checkedBaseUrl) }
    private val baseAuthMode by lazy { getPrefBaseAuthMode() }
    private val baseLogin by lazy { getPrefBaseLogin() }
    private val basePassword by lazy { getPrefBasePassword() }

    override val lang = "all"
    override val supportsLatest = true

    private val tokenManager = lazy {
        TokenManager(
            baseAuthMode,
            baseLogin,
            basePassword,
            checkedBaseUrl,
            network.client.newBuilder()
                .dns(Dns.SYSTEM) // don't use DNS over HTTPS as it breaks IP addressing
                .callTimeout(2, TimeUnit.MINUTES)
                .build(),
        )
    }

    override val client: OkHttpClient =
        network.client.newBuilder()
            .dns(Dns.SYSTEM) // don't use DNS over HTTPS as it breaks IP addressing
            .callTimeout(2, TimeUnit.MINUTES)
            .addInterceptor(OkAuthorizationInterceptor(tokenManager))
            .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        tokenManager.value.getHeaders().forEach {
            add(it.name, it.value)
        }
    }

    // ------------- Popular Manga -------------

    // Route the popular manga view through search to avoid duplicate code path
    override fun popularMangaRequest(page: Int): Request =
        throw Exception("Not used")

    override fun popularMangaParse(response: Response): MangasPage =
        throw Exception("Not used")

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return fetchSearchManga(page, "", FilterList())
    }

    // ------------- Latest Manga -------------

    override fun latestUpdatesRequest(page: Int): Request =
        throw Exception("Not used")

    override fun latestUpdatesParse(response: Response): MangasPage =
        throw Exception("Not used")

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return fetchSearchManga(
            page,
            "",
            FilterList(SortBy(sortByOptions).apply { state = Selection(3, false) }),
        )
    }

    // ------------- Manga Details -------------

    override fun getMangaUrl(manga: SManga): String {
        return "$checkedBaseUrl/manga/${manga.url}"
    }

    override fun mangaDetailsRequest(manga: SManga) =
        throw Exception("Not used")

    override fun mangaDetailsParse(response: Response): SManga =
        throw Exception("Not used")

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return runCatching {
            apolloClient.value
                .mutation(
                    GetMangaMutation(manga.url.toInt()),
                )
                .toFlow()
                .map {
                    it.dataAssertNoErrors
                        .fetchManga!!
                        .manga
                        .mangaFragment
                        .toSManga()
                }
                .asObservable()
        }.getOrElse {
            Observable.error(it)
        }
    }

    // ------------- Chapter -------------

    override fun getChapterUrl(chapter: SChapter): String {
        val mangaId = chapter.url.substringBefore(' ', "")
        val chapterSourceOrder = chapter.url.substringAfter(' ', "")
        return "$checkedBaseUrl/manga/$mangaId/chapter/$chapterSourceOrder"
    }

    override fun chapterListRequest(manga: SManga): Request =
        throw Exception("Not used")

    override fun chapterListParse(response: Response): List<SChapter> =
        throw Exception("Not used")

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return runCatching {
            apolloClient.value
                .mutation(
                    GetChaptersMutation(manga.url.toInt()),
                )
                .toFlow()
                .map { response ->
                    response.dataAssertNoErrors
                        .fetchChapters!!
                        .chapters
                        .sortedByDescending { it.chapterFragment.sourceOrder }
                        .map {
                            it.chapterFragment.toSChapter()
                        }
                }
                .asObservable()
        }.getOrElse {
            Observable.error(it)
        }
    }

    // ------------- Page List -------------

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return runCatching {
            val mangaId = chapter.url.substringBefore(' ', "").toInt()
            val chapterSourceOrder = chapter.url.substringAfter(' ', "").toInt()
            apolloClient.value
                .query(
                    GetChapterIdQuery(mangaId, chapterSourceOrder),
                )
                .toFlow()
                .map {
                    it.dataAssertNoErrors
                        .chapters
                        .nodes
                        .single()
                        .id
                }
                .flatMapLatest { chapterId ->
                    apolloClient.value
                        .mutation(
                            GetPagesMutation(chapterId),
                        )
                        .toFlow()
                        .map {
                            it.dataAssertNoErrors
                                .fetchChapterPages!!
                                .pages
                                .mapIndexed { index, url ->
                                    Page(
                                        index + 1,
                                        "",
                                        "$checkedBaseUrl$url",
                                    )
                                }
                        }
                }
                .asObservable()
        }.getOrElse {
            Observable.error(it)
        }
    }

    override fun pageListRequest(chapter: SChapter) =
        throw Exception("Not used")

    // ------------- Filters & Search -------------

    private var categoryList: List<CategoryFragment> = emptyList()
    private val defaultCategoryId: Int
        get() = categoryList.firstOrNull()?.id ?: 0

    private val resultsPerPageOptions = listOf(10, 15, 20, 25)
    private val defaultResultsPerPage = resultsPerPageOptions.first()

    private val sortByOptions = listOf(
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
    private val defaultSortByIndex = 0

    private var tagList: List<String> = emptyList()
    private val tagModeAndString = "AND"
    private val tagModeOrString = "OR"
    private val tagModes = listOf(tagModeAndString, tagModeOrString)
    private val defaultIncludeTagModeIndex = tagModes.indexOf(tagModeAndString)
    private val defaultExcludeTagModeIndex = tagModes.indexOf(tagModeOrString)
    private val tagFilterModeIncludeString = "Include"
    private val tagFilterModeExcludeString = "Exclude"

    class CategorySelect(categoryList: List<CategoryFragment>) :
        Filter.Select<String>("Category", categoryList.map { it.name }.toTypedArray())

    class ResultsPerPageSelect(options: List<Int>) :
        Filter.Select<Int>("Results per page", options.toTypedArray())

    class SortBy(options: List<String>) :
        Filter.Sort(
            "Sort by",
            options.toTypedArray(),
            Selection(0, true),
        )

    class Tag(name: String, state: Int) :
        Filter.TriState(name, state)

    class TagFilterMode(type: String, tagModes: List<String>, defaultIndex: Int = 0) :
        Filter.Select<String>(type, tagModes.toTypedArray(), defaultIndex)

    class TagSelector(tagList: List<String>) :
        Filter.Group<Tag>(
            "Tags",
            tagList.map { tag -> Tag(tag, 0) },
        )

    class TagFilterModeGroup(
        tagModes: List<String>,
        includeString: String,
        excludeString: String,
        includeDefaultIndex: Int = 0,
        excludeDefaultIndex: Int = 0,
    ) :
        Filter.Group<TagFilterMode>(
            "Tag Filter Modes",
            listOf(
                TagFilterMode(includeString, tagModes, includeDefaultIndex),
                TagFilterMode(excludeString, tagModes, excludeDefaultIndex),
            ),
        )

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Press reset to refresh tag list and attempt to fetch categories."),
        Filter.Header("Tag list shows only the tags of currently displayed manga."),
        Filter.Header("\"All\" shows all manga regardless of category."),
        CategorySelect(refreshCategoryList().let { categoryList }),
        Filter.Separator(),
        TagFilterModeGroup(
            tagModes,
            tagFilterModeIncludeString,
            tagFilterModeExcludeString,
            defaultIncludeTagModeIndex,
            defaultExcludeTagModeIndex,
        ),
        TagSelector(tagList),
        SortBy(sortByOptions),
        ResultsPerPageSelect(resultsPerPageOptions),
    )

    @OptIn(DelicateCoroutinesApi::class)
    private fun refreshCategoryList() {
        runCatching {
            apolloClient.value
                .query(
                    GetCategoriesQuery(),
                )
                .toFlow()
                .onEach { response ->
                    categoryList = listOf(CategoryFragment(-1, "All")) +
                        response.dataAssertNoErrors
                            .categories
                            .nodes
                            .map { it.categoryFragment }
                }
                .catch {
                    categoryList = emptyList()
                }
                .launchIn(GlobalScope)
        }
    }

    private fun refreshTagList(mangaList: List<MangaFragment>) {
        val newTagList = mutableListOf<String>()
        for (mangaDetails in mangaList) {
            newTagList.addAll(mangaDetails.genre)
        }
        tagList = newTagList
            .distinctBy { tag -> tag.lowercase() }
            .sortedBy { tag -> tag.lowercase() }
            .filter { tag -> tag.trim() != "" }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        throw Exception("Not used")

    override fun searchMangaParse(response: Response) =
        throw Exception("Not used")

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        return runCatching {
            // Embed search query and scope into URL params for processing in searchMangaParse
            var currentCategoryId = defaultCategoryId
            var resultsPerPage = defaultResultsPerPage
            var sortByIndex = defaultSortByIndex
            var sortByAscending = true
            val tagIncludeList = mutableListOf<String>()
            val tagExcludeList = mutableListOf<String>()
            var tagFilterIncludeModeIndex = defaultIncludeTagModeIndex
            var tagFilterExcludeModeIndex = defaultExcludeTagModeIndex

            filters.forEach { filter ->
                when (filter) {
                    is CategorySelect -> currentCategoryId = categoryList[filter.state].id
                    is ResultsPerPageSelect -> resultsPerPage = resultsPerPageOptions[filter.state]
                    is SortBy -> {
                        sortByIndex = filter.state?.index ?: sortByIndex
                        sortByAscending = filter.state?.ascending ?: sortByAscending
                    }
                    is TagFilterModeGroup -> {
                        filter.state.forEach { tagFilterMode ->
                            when (tagFilterMode.name) {
                                tagFilterModeIncludeString ->
                                    tagFilterIncludeModeIndex =
                                        tagFilterMode.state

                                tagFilterModeExcludeString ->
                                    tagFilterExcludeModeIndex =
                                        tagFilterMode.state
                            }
                        }
                    }

                    is TagSelector -> {
                        filter.state.forEach { tagFilter ->
                            when {
                                tagFilter.isIncluded() -> tagIncludeList.add(tagFilter.name)
                                tagFilter.isExcluded() -> tagExcludeList.add(tagFilter.name)
                            }
                        }
                    }

                    else -> {}
                }
            }
            val sortByProperty = sortByOptions[sortByIndex]
            val tagFilterIncludeMode = tagModes[tagFilterIncludeModeIndex]
            val tagFilterExcludeMode = tagModes[tagFilterExcludeModeIndex]

            val filterInput = mutableListOf<MangaFilterInput>()
            // Get URLs of categories to search
            if (currentCategoryId >= 0) {
                filterInput.add(
                    MangaFilterInput(
                        categoryId = Optional.present(
                            if (currentCategoryId == 0) {
                                IntFilterInput(
                                    isNull = Optional.present(true),
                                )
                            } else {
                                IntFilterInput(
                                    equalTo = Optional.present(currentCategoryId),
                                )
                            },
                        ),
                    ),
                )
            }

            val filterConfigs = mutableListOf<Triple<Boolean, String, List<String>>>()
            if (tagExcludeList.isNotEmpty()) {
                filterConfigs.add(
                    Triple(
                        false,
                        tagFilterExcludeMode,
                        tagExcludeList,
                    ),
                )
            }
            if (tagIncludeList.isNotEmpty()) {
                filterConfigs.add(
                    Triple(
                        true,
                        tagFilterIncludeMode,
                        tagIncludeList,
                    ),
                )
            }

            filterConfigs.mapNotNullTo(filterInput) { config ->
                val isInclude = config.first
                val filterMode = config.second
                val filteredTagList = config.third
                val filterTagList = filteredTagList.map {
                    MangaFilterInput(
                        genre = Optional.present(
                            if (isInclude) {
                                StringFilterInput(
                                    includesInsensitive = Optional.present(it),
                                )
                            } else {
                                StringFilterInput(
                                    notIncludesInsensitive = Optional.present(it),
                                )
                            },
                        ),
                    )
                }
                when (filterMode) {
                    tagModeAndString -> MangaFilterInput(
                        and = Optional.present(filterTagList),
                    )
                    tagModeOrString -> MangaFilterInput(
                        or = Optional.present(filterTagList),
                    )
                    else -> null
                }
            }

            // Filter according to search terms.
            if (query.isNotEmpty()) {
                val queryFilters = listOf(
                    MangaFilterInput(
                        title = Optional.present(
                            StringFilterInput(includesInsensitive = Optional.present(query)),
                        ),
                    ),
                    MangaFilterInput(
                        url = Optional.present(
                            StringFilterInput(includesInsensitive = Optional.present(query)),
                        ),
                    ),
                    MangaFilterInput(
                        artist = Optional.present(
                            StringFilterInput(includesInsensitive = Optional.present(query)),
                        ),
                    ),
                    MangaFilterInput(
                        author = Optional.present(
                            StringFilterInput(includesInsensitive = Optional.present(query)),
                        ),
                    ),
                    MangaFilterInput(
                        description = Optional.present(
                            StringFilterInput(includesInsensitive = Optional.present(query)),
                        ),
                    ),
                )
                filterInput.add(
                    MangaFilterInput(
                        or = Optional.present(queryFilters),
                    ),
                )
            }

            val optionalFilterInput = if (filterInput.isNotEmpty()) {
                Optional.present(filterInput)
            } else {
                Optional.absent()
            }

            // Construct a list of all manga in the required categories by querying each one
            return apolloClient.value.query(
                SearchMangaQuery(optionalFilterInput),
            )
                .toFlow()
                .map { response ->
                    response.dataAssertNoErrors.mangas.nodes.map { it.mangaFragment }
                        .distinctBy { it.id }
                }
                .map { mangaList ->
                    // Filter by tags
                    var searchResults = mangaList.toList()

                    // Sort results
                    searchResults = when (sortByProperty) {
                        "Title" -> searchResults.sortedBy { it.title }
                        "Artist" -> searchResults.sortedBy { it.artist }
                        "Author" -> searchResults.sortedBy { it.author }
                        "Date added" -> searchResults.sortedBy { it.inLibraryAt }
                        "Total chapters" -> searchResults.sortedBy { it.chapters.totalCount }
                        "Latest uploaded chapter" -> searchResults.sortedBy { it.latestUploadedChapter?.uploadDate ?: 0 }
                        "Latest fetched chapter" -> searchResults.sortedBy { it.latestFetchedChapter?.fetchedAt ?: 0 }
                        "Recently read" -> searchResults.sortedBy { it.latestReadChapter?.lastReadAt ?: 0 }
                        "Unread chapters" -> searchResults.sortedBy { it.unreadCount }
                        "Downloaded chapters" -> searchResults.sortedBy { it.downloadCount }
                        else -> searchResults
                    }
                    if (!sortByAscending) {
                        searchResults = searchResults.asReversed()
                    }

                    // Get new list of tags from the search results
                    refreshTagList(searchResults)

                    // Paginate results
                    val hasNextPage: Boolean
                    with(paginateResults(searchResults, page, resultsPerPage)) {
                        searchResults = first
                        hasNextPage = second
                    }

                    MangasPage(searchResults.map { mangaData -> mangaData.toSManga() }, hasNextPage)
                }
                .asObservable()
        }.getOrElse {
            Observable.error(it)
        }
    }

    // ------------- Images -------------
    override fun imageRequest(page: Page) = GET(page.imageUrl!!, headers)

    // ------------- Settings -------------

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    init {
        val preferencesMap = mapOf(
            ADDRESS_TITLE to ADDRESS_DEFAULT,
            LOGIN_TITLE to LOGIN_DEFAULT,
            PASSWORD_TITLE to PASSWORD_DEFAULT,
        )

        preferencesMap.forEach { (key, defaultValue) ->
            val initBase = preferences.getString(key, defaultValue)!!

            if (initBase.isNotBlank()) {
                refreshCategoryList()
            }
        }
    }

    // ------------- Preferences -------------
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(screen.editTextPreference(ADDRESS_TITLE, ADDRESS_DEFAULT, baseUrl, false, "i.e. http://192.168.1.115:4567", ADDRESS_TITLE))
        screen.addPreference(screen.editListPreference(MODE_TITLE, MODE_DEFAULT, baseAuthMode.title, AuthMode.entries.map { it.title }, AuthMode.entries.map { it.toString() }, "Must match Suwayomi's auth_mode setting", MODE_TITLE))
        screen.addPreference(screen.editTextPreference(LOGIN_TITLE, LOGIN_DEFAULT, baseLogin, false, "", LOGIN_KEY))
        screen.addPreference(screen.editTextPreference(PASSWORD_TITLE, PASSWORD_DEFAULT, basePassword, true, "", PASSWORD_KEY))
    }

    /** boilerplate for [EditTextPreference] */
    private fun PreferenceScreen.editTextPreference(title: String, default: String, value: String, isPassword: Boolean = false, placeholder: String, key: String): EditTextPreference {
        return EditTextPreference(context).apply {
            this.key = title
            this.title = title
            summary = value.ifEmpty { placeholder }
            this.setDefaultValue(default)
            dialogTitle = title

            if (isPassword) {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(key, newValue as String).commit()
                    Toast.makeText(context, "Restart Tachiyomi to apply new setting.", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    Log.e(TAG, "Exception while setting text preference", e)
                    false
                }
            }
        }
    }

    private fun PreferenceScreen.editListPreference(title: String, default: String, value: String, entries: List<CharSequence>, entryValues: List<CharSequence>, placeholder: String, key: String): ListPreference {
        return ListPreference(context).apply {
            this.key = title
            this.title = title
            this.entries = entries.toTypedArray()
            this.entryValues = entryValues.toTypedArray()
            summary = value.ifEmpty { placeholder }
            this.setDefaultValue(default)

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(key, newValue as String).commit()
                    Toast.makeText(context, "Restart Tachiyomi to apply new setting.", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    Log.e(TAG, "Exception while setting text preference", e)
                    false
                }
            }
        }
    }

    public enum class AuthMode(val title: String) {
        NONE("None"),
        BASIC_AUTH("Basic Authentication"),
        SIMPLE_LOGIN("Simple Login"),
        UI_LOGIN("UI Login"),
    }

    private fun getPrefBaseUrl(): String = preferences.getString(ADDRESS_TITLE, ADDRESS_DEFAULT)!!
    private fun getPrefBaseAuthMode(): AuthMode {
        if (!preferences.contains(MODE_TITLE) && basePassword.isNotEmpty() && baseLogin.isNotEmpty()) {
            return AuthMode.BASIC_AUTH
        }
        return AuthMode.valueOf(preferences.getString(MODE_TITLE, MODE_DEFAULT)!!)
    }
    private fun getPrefBaseLogin(): String = preferences.getString(LOGIN_KEY, LOGIN_DEFAULT)!!
    private fun getPrefBasePassword(): String = preferences.getString(PASSWORD_KEY, PASSWORD_DEFAULT)!!

    companion object {
        private const val ADDRESS_TITLE = "Server URL Address"
        private const val ADDRESS_DEFAULT = ""
        private const val MODE_TITLE = "Login Mode"
        private const val MODE_DEFAULT = "NONE"
        private const val LOGIN_KEY = "Login (Basic Auth)"
        private const val LOGIN_TITLE = "Login"
        private const val LOGIN_DEFAULT = ""
        private const val PASSWORD_KEY = "Password (Basic Auth)"
        private const val PASSWORD_TITLE = "Password"
        private const val PASSWORD_DEFAULT = ""

        private const val TAG = "Tachidesk"
    }

    // ------------- Not Used -------------

    override fun pageListParse(response: Response): List<Page> = throw Exception("Not used")

    override fun imageUrlParse(response: Response): String = throw Exception("Not used")

    // ------------- Util -------------

    private fun MangaFragment.toSManga() = SManga.create().also {
        it.url = id.toString()
        it.title = title
        it.thumbnail_url = "$cleanUrl$thumbnailUrl"
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre.joinToString(", ")
        it.status = when (status) {
            MangaStatus.ONGOING -> SManga.ONGOING
            MangaStatus.COMPLETED -> SManga.COMPLETED
            MangaStatus.LICENSED -> SManga.LICENSED
            MangaStatus.PUBLISHING_FINISHED -> SManga.PUBLISHING_FINISHED
            MangaStatus.CANCELLED -> SManga.CANCELLED
            MangaStatus.ON_HIATUS -> SManga.ON_HIATUS
            MangaStatus.UNKNOWN, MangaStatus.UNKNOWN__ -> SManga.UNKNOWN
        }
    }

    private fun ChapterFragment.toSChapter() = SChapter.create().also {
        it.url = "$mangaId $sourceOrder"
        it.name = name
        it.date_upload = uploadDate
        it.scanlator = scanlator
        it.chapter_number = chapterNumber.toString().toFloat()
    }

    private val cleanUrl: String
        get(): String = baseUrl.trimEnd('/')

    private val checkedBaseUrl: String
        get(): String = cleanUrl.ifEmpty { throw RuntimeException("Set Tachidesk server url in extension settings") }

    private fun paginateResults(mangaList: List<MangaFragment>, page: Int, itemsPerPage: Int): Pair<List<MangaFragment>, Boolean> {
        var hasNextPage = false
        val pageItems = if (mangaList.isNotEmpty()) {
            val fromIndex = (page - 1) * itemsPerPage
            val toIndex = min(fromIndex + itemsPerPage, mangaList.size)
            hasNextPage = toIndex < mangaList.size
            mangaList.subList(fromIndex, toIndex)
        } else {
            mangaList
        }
        return Pair(pageItems, hasNextPage)
    }
}
