package dev.aria.memo.data

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Simple manual DI — intentionally no Hilt/Koin for V1.
 *
 * Lifecycle: [init] is called from the custom `Application.onCreate` (owned by
 * Agent C). After init, [settings], [api], and [repository] are available
 * app-wide. The [httpClient] is long-lived: Ktor's CIO engine maintains a
 * connection pool which we want to amortize across widget/activity use.
 *
 * Thread safety: [init] MUST be called exactly once on the main thread from
 * Application.onCreate before any other getter. Getters are lock-free reads
 * of `@Volatile` fields thereafter.
 */
object ServiceLocator {

    @Volatile private var _settings: SettingsStore? = null
    @Volatile private var _api: GitHubApi? = null
    @Volatile private var _repository: MemoRepository? = null
    @Volatile private var _httpClient: HttpClient? = null

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
            expectSuccess = false // we handle non-2xx explicitly
        }
        val settings = SettingsStore(appContext)
        val api = GitHubApi(client)
        val repository = MemoRepository(settings, api)

        _httpClient = client
        _settings = settings
        _api = api
        _repository = repository
    }

    fun get(): MemoRepository = requireNotNull(_repository) {
        "ServiceLocator.init() not called — add a custom Application class"
    }

    fun settings(): SettingsStore = requireNotNull(_settings) {
        "ServiceLocator.init() not called"
    }

    fun api(): GitHubApi = requireNotNull(_api) {
        "ServiceLocator.init() not called"
    }

    fun httpClient(): HttpClient = requireNotNull(_httpClient) {
        "ServiceLocator.init() not called"
    }

    // Property aliases for direct access (used by ViewModel factories).
    val repository: MemoRepository get() = get()
    val settingsStore: SettingsStore get() = settings()
}
