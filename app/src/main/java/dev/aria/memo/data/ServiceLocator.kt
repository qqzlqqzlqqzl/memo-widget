package dev.aria.memo.data

import android.content.Context
import dev.aria.memo.data.ai.AiClient
import dev.aria.memo.data.ai.AiSettingsStore
import dev.aria.memo.data.local.AppDatabase
import dev.aria.memo.data.local.EventDao
import dev.aria.memo.data.local.NoteDao
import dev.aria.memo.data.local.SingleNoteDao
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

    fun init(context: Context) {
        if (_memoRepo != null) return
        val appContext = context.applicationContext
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
    }

    fun get(): MemoRepository = requireNotNull(_memoRepo) { "ServiceLocator.init() not called" }
    fun settings(): SettingsStore = requireNotNull(_settings) { "ServiceLocator.init() not called" }
    fun api(): GitHubApi = requireNotNull(_api) { "ServiceLocator.init() not called" }
    fun httpClient(): HttpClient = requireNotNull(_httpClient) { "ServiceLocator.init() not called" }
    fun noteDao(): NoteDao = requireNotNull(_db) { "ServiceLocator.init() not called" }.noteDao()
    fun eventDao(): EventDao = requireNotNull(_db) { "ServiceLocator.init() not called" }.eventDao()
    fun singleNoteDao(): SingleNoteDao =
        requireNotNull(_db) { "ServiceLocator.init() not called" }.singleNoteDao()
    fun eventRepository(): EventRepository = requireNotNull(_eventRepo) { "ServiceLocator.init() not called" }
    fun singleNoteRepository(): SingleNoteRepository =
        requireNotNull(_singleNoteRepo) { "ServiceLocator.init() not called" }
    fun aiSettingsStore(): AiSettingsStore = requireNotNull(_aiSettings) { "ServiceLocator.init() not called" }
    fun aiClient(): AiClient = requireNotNull(_aiClient) { "ServiceLocator.init() not called" }

    val repository: MemoRepository get() = get()
    val settingsStore: SettingsStore get() = settings()
    val eventRepo: EventRepository get() = eventRepository()
    val singleNoteRepo: SingleNoteRepository get() = singleNoteRepository()
    val aiSettings: AiSettingsStore get() = aiSettingsStore()
    val ai: AiClient get() = aiClient()
}
