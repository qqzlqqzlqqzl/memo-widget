package dev.aria.memo.data

import android.content.Context
import dev.aria.memo.data.local.AppDatabase
import dev.aria.memo.data.local.NoteDao
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Simple manual DI — intentionally no Hilt/Koin for V1.
 *
 * Lifecycle: [init] is called from [dev.aria.memo.MemoApplication.onCreate].
 * After init, all getters return the same long-lived instances. The DB and
 * Ktor client are both expensive to construct, so we amortize them across
 * activity + widget + WorkManager use.
 */
object ServiceLocator {

    @Volatile private var _settings: SettingsStore? = null
    @Volatile private var _api: GitHubApi? = null
    @Volatile private var _repository: MemoRepository? = null
    @Volatile private var _httpClient: HttpClient? = null
    @Volatile private var _db: AppDatabase? = null

    fun init(context: Context) {
        if (_repository != null) return // idempotent guard
        val appContext = context.applicationContext
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    explicitNulls = false
                })
            }
            expectSuccess = false
        }
        val db = AppDatabase.build(appContext)
        val settings = SettingsStore(appContext)
        val api = GitHubApi(client)
        val repository = MemoRepository(appContext, settings, api, db.noteDao())

        _httpClient = client
        _db = db
        _settings = settings
        _api = api
        _repository = repository
    }

    fun get(): MemoRepository = requireNotNull(_repository) {
        "ServiceLocator.init() not called — add a custom Application class"
    }

    fun settings(): SettingsStore = requireNotNull(_settings) { "ServiceLocator.init() not called" }
    fun api(): GitHubApi = requireNotNull(_api) { "ServiceLocator.init() not called" }
    fun httpClient(): HttpClient = requireNotNull(_httpClient) { "ServiceLocator.init() not called" }
    fun noteDao(): NoteDao = requireNotNull(_db) { "ServiceLocator.init() not called" }.noteDao()

    val repository: MemoRepository get() = get()
    val settingsStore: SettingsStore get() = settings()
}
