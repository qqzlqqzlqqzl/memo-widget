package dev.aria.memo.data

import android.content.Context
import dev.aria.memo.data.ai.AiClient
import dev.aria.memo.data.ai.AiSettingsStore
import dev.aria.memo.data.local.AppDatabase
import dev.aria.memo.data.local.EventDao
import dev.aria.memo.data.local.NoteDao
import dev.aria.memo.data.local.SingleNoteDao
import dev.aria.memo.data.sync.ConnectivityObserver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Simple manual DI — intentionally no Hilt/Koin for this phase.
 *
 * [init] is called from [dev.aria.memo.MemoApplication.onCreate] *and* as the
 * first line of each Worker, so even a cold-boot WorkManager invocation has
 * a live repository.
 */
object ServiceLocator {

    @Volatile private var _settings: SettingsStore? = null
    @Volatile private var _api: GitHubApi? = null
    @Volatile private var _memoRepo: MemoRepository? = null
    @Volatile private var _eventRepo: EventRepository? = null
    @Volatile private var _singleNoteRepo: SingleNoteRepository? = null
    @Volatile private var _httpClient: HttpClient? = null
    @Volatile private var _db: AppDatabase? = null
    @Volatile private var _aiSettings: AiSettingsStore? = null
    @Volatile private var _aiClient: AiClient? = null
    @Volatile private var _connectivity: ConnectivityObserver? = null

    /**
     * P8：暴露应用级 Context 给数据层 hook（特别是 [dev.aria.memo.data.widget.WidgetRefresher]）。
     *
     * 为什么公开 Context：Repository 按"不持 Context"的原则设计（见各自构造签名），
     * 但 widget 刷新需要一个活着的 Context 去拿 AppWidgetManager。把 appContext
     * 挂到 ServiceLocator 上是最小侵入的做法 —— 所有写方法都能通过
     * `ServiceLocator.appContext` 拿到 application 级 context，不用改 Repository
     * 构造签名。
     *
     * 使用前必须先 [init]；否则读到 `null` 会炸。WorkManager / Application.onCreate
     * 都保证会调 [init]，所以实际运行路径里不会有问题。
     */
    @Volatile private var _appContext: Context? = null
    val appContext: Context
        get() = requireNotNull(_appContext) { "ServiceLocator.init() not called" }

    fun init(context: Context) {
        if (_memoRepo != null) return
        val appContext = context.applicationContext
        _appContext = appContext
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    explicitNulls = false
                })
            }
            // Fixes #20: bound every call so a hung/broken GitHub connection
            // can't keep a Worker (or the UI) pinned indefinitely.
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
            expectSuccess = false
        }
        val db = AppDatabase.build(appContext)
        val settings = SettingsStore(appContext)
        val api = GitHubApi(client)
        val memoRepo = MemoRepository(appContext, settings, api, db.noteDao())
        val eventRepo = EventRepository(appContext, settings, api, db.eventDao())
        val singleNoteRepo = SingleNoteRepository(appContext, settings, db.singleNoteDao())
        val aiSettings = AiSettingsStore(appContext)
        val aiClient = AiClient(http = client, settings = aiSettings)

        _httpClient = client
        _db = db
        _settings = settings
        _api = api
        _memoRepo = memoRepo
        _eventRepo = eventRepo
        _singleNoteRepo = singleNoteRepo
        _aiSettings = aiSettings
        _aiClient = aiClient
        _connectivity = ConnectivityObserver.fromContext(appContext)
    }

    // --------------------------------------------------------------------------------
    // Canonical read-only property API. New callers MUST use these.
    //
    // Arch-1 Fix-8: ServiceLocator previously exposed each service through BOTH a
    // `fun xxx()` method and a `val xxx` property. New code should use the property
    // form (declared below); the method form is kept `@Deprecated` with a
    // `ReplaceWith` hint so existing callers aren't silently broken mid-refactor.
    //
    // For services that had multiple aliases (e.g. `get()` / `repository`), we
    // picked a single canonical name — matching the ViewModel constructor
    // parameter style — and point everything at it:
    //   MemoRepository         -> [repository]  (was: `get()`, `repository`, `repo`)
    //   SettingsStore          -> [settingsStore] (was: `settings()`, `settingsStore`)
    //   EventRepository        -> [eventRepo]   (was: `eventRepository()`, `eventRepo`)
    //   SingleNoteRepository   -> [singleNoteRepo] (was: `singleNoteRepository()`, `singleNoteRepo`)
    //   AiSettingsStore        -> [aiSettings]  (was: `aiSettingsStore()`, `aiSettings`)
    //   AiClient               -> [ai]          (was: `aiClient()`, `ai`)
    // --------------------------------------------------------------------------------

    val repository: MemoRepository get() = requireNotNull(_memoRepo) { "ServiceLocator.init() not called" }
    val settingsStore: SettingsStore get() = requireNotNull(_settings) { "ServiceLocator.init() not called" }
    val eventRepo: EventRepository get() = requireNotNull(_eventRepo) { "ServiceLocator.init() not called" }
    val singleNoteRepo: SingleNoteRepository get() = requireNotNull(_singleNoteRepo) { "ServiceLocator.init() not called" }
    val aiSettings: AiSettingsStore get() = requireNotNull(_aiSettings) { "ServiceLocator.init() not called" }
    val ai: AiClient get() = requireNotNull(_aiClient) { "ServiceLocator.init() not called" }
    val connectivity: ConnectivityObserver
        get() = requireNotNull(_connectivity) { "ServiceLocator.init() not called" }

    // --------------------------------------------------------------------------------
    // Legacy method-style API. Kept so in-flight calls don't break; marked
    // @Deprecated so new code gravitates to the property form above. Target removal:
    // P8.1 (after Fix-8 lands and the rest of the Arch-1 cleanup follows).
    // --------------------------------------------------------------------------------

    @Deprecated(
        message = "Use the property `ServiceLocator.repository` instead (Arch-1 canonical name for MemoRepository).",
        replaceWith = ReplaceWith("repository"),
    )
    fun get(): MemoRepository = repository

    @Deprecated(
        message = "Use the property `ServiceLocator.settingsStore` instead.",
        replaceWith = ReplaceWith("settingsStore"),
    )
    fun settings(): SettingsStore = settingsStore

    @Deprecated(
        message = "Use the property `ServiceLocator.eventRepo` instead.",
        replaceWith = ReplaceWith("eventRepo"),
    )
    fun eventRepository(): EventRepository = eventRepo

    @Deprecated(
        message = "Use the property `ServiceLocator.singleNoteRepo` instead.",
        replaceWith = ReplaceWith("singleNoteRepo"),
    )
    fun singleNoteRepository(): SingleNoteRepository = singleNoteRepo

    @Deprecated(
        message = "Use the property `ServiceLocator.aiSettings` instead.",
        replaceWith = ReplaceWith("aiSettings"),
    )
    fun aiSettingsStore(): AiSettingsStore = aiSettings

    @Deprecated(
        message = "Use the property `ServiceLocator.ai` instead (canonical name for AiClient).",
        replaceWith = ReplaceWith("ai"),
    )
    fun aiClient(): AiClient = ai

    // The following never had a property alias — leaving them as methods (no
    // deprecation) until a future pass either adds properties or the callers
    // migrate to construction-time injection.
    fun api(): GitHubApi = requireNotNull(_api) { "ServiceLocator.init() not called" }
    fun httpClient(): HttpClient = requireNotNull(_httpClient) { "ServiceLocator.init() not called" }
    fun noteDao(): NoteDao = requireNotNull(_db) { "ServiceLocator.init() not called" }.noteDao()
    fun eventDao(): EventDao = requireNotNull(_db) { "ServiceLocator.init() not called" }.eventDao()
    fun singleNoteDao(): SingleNoteDao =
        requireNotNull(_db) { "ServiceLocator.init() not called" }.singleNoteDao()
}
