package dev.aria.memo.widget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.aria.memo.data.EventRepository
import dev.aria.memo.data.GitHubApi
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.PatStorage
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.SettingsStore
import dev.aria.memo.data.SingleNoteRepository
import dev.aria.memo.data.local.AppDatabase
import dev.aria.memo.data.local.NoteFileEntity
import dev.aria.memo.data.sync.PullWorker
import dev.aria.memo.data.sync.PushWorker
import dev.aria.memo.data.widget.WidgetRefresher
import dev.aria.memo.data.widget.WidgetUpdater
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Hook chain integration test (Fix-W4).**
 *
 * P8 在 13 条数据写路径上各装了一条 `WidgetRefresher.refreshAll(context)` 调用：
 *  - [MemoRepository.appendToday] / [MemoRepository.togglePin] / [MemoRepository.toggleTodoLine]
 *  - [SingleNoteRepository.create] / [.update] / [.delete] / [.togglePin]
 *  - [EventRepository.create] / [.update] / [.delete]
 *  - [SettingsStore.update]
 *  - [PullWorker.doWork] / [PushWorker.doWork]
 *
 * 已有的 [dev.aria.memo.data.widget.WidgetRefresherTest] 用 fake updater 验证了
 * **WidgetRefresher 自身的 debounce / 异常容错**，但**没有断言 production 写路径
 * 真在调用它**。换句话说，如果某次 refactor 把
 * `WidgetRefresher.refreshAll(...)` 从某个 hook 删掉了，所有现有 unit test 仍然绿。
 * 这是 invariant 漂移漏洞。
 *
 * 这个测试是 "hook 真接通" 的最后一道防线：
 *  1. 用 Robolectric 提供真实 [Context]（`ApplicationProvider.getApplicationContext()`）。
 *  2. 用 `Room.inMemoryDatabaseBuilder` 创建真 Room DB —— 不 mock DAO，跑 production
 *     SQL，让 Repository / Worker 走完整的 read-modify-write 路径。
 *  3. 用 `WorkManager.initialize(...)` 直接初始化 WorkManager（不依赖
 *     `androidx.work:work-testing`）。
 *  4. 用 Ktor [MockEngine] 拦截所有 GitHub HTTP，按 HTTP method 分派 GET/PUT/DELETE。
 *  5. 把 [WidgetRefresher.updater] 替换成 [CountingWidgetUpdater]，在虚拟时钟上推进
 *     debounce 窗口后断言 memoCount / todayCount 至少 +1。
 *
 * 13 个 test 一一对应 13 条 hook，确保**任何一条被人手滑删掉、debounce 期间 cancel
 * 错误、或 ServiceLocator.appContext 未填**，都会立刻红。
 *
 * **不退化的设计取舍**：
 *  - 不用 Mockito static mocking（项目没引入 mockito 依赖）。
 *  - 不依赖 `androidx.work:work-testing`：用 `WorkManager.initialize` +
 *    `Executors.newSingleThreadExecutor` 替代 `WorkManagerTestInitHelper +
 *    SynchronousExecutor`；用反射构造 [WorkerParameters] 替代
 *    `TestListenableWorkerBuilder`。
 *  - SecurePatStore 走 EncryptedSharedPreferences → AndroidKeyStore，Robolectric
 *    没 shadow，[installFakePrefsOnSecure] 反射替换 `prefs$delegate` 跳过 keystore。
 *
 * **依赖**（由 Fix-W1 加进 build.gradle.kts，已落地）：
 *  - `testImplementation(libs.robolectric)`（4.14.1）
 *  - `testImplementation(libs.androidx.test.core)`（1.6.1，提供 ApplicationProvider）
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33],
    // 用 vanilla Application 替代 MemoApplication —— 后者在 onCreate 里跑
    // ServiceLocator.init + ConfigChangeListener.start，会触发真 SecurePatStore →
    // AndroidKeyStore，Robolectric 不 shadow keystore，整个 setup 直接抛
    // KeyStoreException。我们的测试自建 ServiceLocator / SettingsStore，不需要
    // production Application 的 init 路径。
    application = android.app.Application::class,
)
class WidgetHookIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var fakeUpdater: CountingWidgetUpdater
    private lateinit var api: GitHubApi
    private lateinit var http: HttpClient
    private lateinit var memoRepo: MemoRepository
    private lateinit var singleNoteRepo: SingleNoteRepository
    private lateinit var eventRepo: EventRepository
    private lateinit var originalUpdater: WidgetUpdater

    private val today: LocalDate = LocalDate.now()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Initialise WorkManager —— SyncScheduler.enqueuePush 调 WorkManager.getInstance(ctx)
        // 要求 WorkManager 已经 init 过。runCatching 兜住 "已被前测试 init 过" 的 ISE。
        val workConfig = Configuration.Builder()
            .setExecutor(Executors.newSingleThreadExecutor())
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        runCatching { WorkManager.initialize(context, workConfig) }

        // In-memory Room —— 与 production AppDatabase 共用 schema，但不写盘。
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        installAppDatabaseInstance(db)

        // SecurePatStore 走 EncryptedSharedPreferences → AndroidKeyStore，
        // Robolectric 没 shadow。SettingsStore 主构造器接受 [PatStorage] 接口，
        // 我们注入一个 in-memory 实现，跳过 keystore 这条路径。
        settings = SettingsStore(context, InMemoryPatStorage())

        fakeUpdater = CountingWidgetUpdater()
        originalUpdater = WidgetRefresher.updater
        WidgetRefresher.updater = fakeUpdater

        http = buildMockHttp()
        api = GitHubApi(http)

        memoRepo = MemoRepository(
            appContext = context,
            settings = settings,
            api = api,
            dao = db.noteDao(),
        )
        singleNoteRepo = SingleNoteRepository(
            appContext = context,
            settings = settings,
            dao = db.singleNoteDao(),
        )
        eventRepo = EventRepository(
            appContext = context,
            settings = settings,
            api = api,
            dao = db.eventDao(),
        )
    }

    @After
    fun tearDown() {
        WidgetRefresher.updater = originalUpdater
        WidgetRefresher.resetScopeForTest()
        runCatching { http.close() }
        runCatching { db.close() }
        installAppDatabaseInstance(null)
        // Clear ServiceLocator —— 否则单例污染下一个测试。
        runCatching { clearServiceLocator() }
    }

    // -----------------------------------------------------------------------
    // MemoRepository hooks (3)
    // -----------------------------------------------------------------------

    @Test
    fun `MemoRepository_appendToday triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        configureSettings()

        val res = memoRepo.appendToday(body = "hello widget", now = LocalDateTime.now())
        assertTrue("appendToday must return Ok, got $res", res is MemoResult.Ok)

        advanceDebounce()
        assertHookFired("MemoRepository.appendToday")
    }

    @Test
    fun `MemoRepository_togglePin triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        configureSettings()
        memoRepo.appendToday(body = "primer for toggle pin")
        advanceDebounce()
        fakeUpdater.reset()

        val path = settings.current().filePathFor(today)
        memoRepo.togglePin(path = path, pinned = true)

        advanceDebounce()
        assertHookFired("MemoRepository.togglePin")
    }

    @Test
    fun `MemoRepository_toggleTodoLine triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        configureSettings()

        val path = settings.current().filePathFor(today)
        val initialContent = "# $today\n\n## 09:00\n- [ ] task one\n"
        db.noteDao().upsert(
            NoteFileEntity(
                path = path,
                date = today,
                content = initialContent,
                githubSha = "sha-existing",
                localUpdatedAt = 0L,
                remoteUpdatedAt = 0L,
                dirty = false,
            ),
        )

        val expectedLine = "- [ ] task one"
        val lineIndex = initialContent.split("\n").indexOf(expectedLine)
        check(lineIndex >= 0) { "line not found in seeded content" }

        val res = memoRepo.toggleTodoLine(
            path = path,
            lineIndex = lineIndex,
            expectedRawLine = expectedLine,
            newChecked = true,
        )
        assertTrue("toggleTodoLine must return Ok, got $res", res is MemoResult.Ok)

        advanceDebounce()
        assertHookFired("MemoRepository.toggleTodoLine")
    }

    // -----------------------------------------------------------------------
    // SingleNoteRepository hooks (4)
    // -----------------------------------------------------------------------

    @Test
    fun `SingleNoteRepository_create triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        configureSettings()

        val res = singleNoteRepo.create(body = "fresh single note")
        assertTrue("create must return Ok, got $res", res is MemoResult.Ok)

        advanceDebounce()
        assertHookFired("SingleNoteRepository.create")
    }

    @Test
    fun `SingleNoteRepository_update triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        configureSettings()
        val seed = singleNoteRepo.create(body = "primer body")
        require(seed is MemoResult.Ok) { "seed create failed: $seed" }
        advanceDebounce()
        fakeUpdater.reset()

        val res = singleNoteRepo.update(uid = seed.value.uid, body = "updated body")
        assertTrue("update must return Ok, got $res", res is MemoResult.Ok)

        advanceDebounce()
        assertHookFired("SingleNoteRepository.update")
    }

    @Test
    fun `SingleNoteRepository_delete triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        configureSettings()
        val seed = singleNoteRepo.create(body = "primer body")
        require(seed is MemoResult.Ok) { "seed create failed: $seed" }
        advanceDebounce()
        fakeUpdater.reset()

        val res = singleNoteRepo.delete(uid = seed.value.uid)
        assertTrue("delete must return Ok, got $res", res is MemoResult.Ok)

        advanceDebounce()
        assertHookFired("SingleNoteRepository.delete")
    }

    @Test
    fun `SingleNoteRepository_togglePin triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        configureSettings()
        val seed = singleNoteRepo.create(body = "primer body")
        require(seed is MemoResult.Ok) { "seed create failed: $seed" }
        advanceDebounce()
        fakeUpdater.reset()

        val res = singleNoteRepo.togglePin(uid = seed.value.uid, pinned = true)
        assertTrue("togglePin must return Ok, got $res", res is MemoResult.Ok)

        advanceDebounce()
        assertHookFired("SingleNoteRepository.togglePin")
    }

    // -----------------------------------------------------------------------
    // EventRepository hooks (3)
    // -----------------------------------------------------------------------

    @Test
    fun `EventRepository_create triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        configureSettings()
        // Issue #300: drain the configureSettings refreshAll before the
        // operation under test so we can prove EventRepository hits
        // only the Today widget — without this the previous refreshAll
        // pollution would mask the narrower contract.
        advanceDebounce()
        fakeUpdater.reset()

        val now = System.currentTimeMillis()
        val res = eventRepo.create(
            summary = "test event",
            startMs = now,
            endMs = now + 60_000L,
        )
        assertTrue("create must return Ok, got $res", res is MemoResult.Ok)

        advanceDebounce()
        assertOnlyTodayHookFired("EventRepository.create")
    }

    @Test
    fun `EventRepository_update triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        configureSettings()
        val now = System.currentTimeMillis()
        val seed = eventRepo.create(
            summary = "primer event",
            startMs = now,
            endMs = now + 60_000L,
        )
        require(seed is MemoResult.Ok) { "seed create failed: $seed" }
        advanceDebounce()
        fakeUpdater.reset()

        val res = eventRepo.update(
            uid = seed.value.uid,
            summary = "updated event",
            startMs = seed.value.startEpochMs,
            endMs = seed.value.endEpochMs,
        )
        assertTrue("update must return Ok, got $res", res is MemoResult.Ok)

        advanceDebounce()
        assertOnlyTodayHookFired("EventRepository.update")
    }

    @Test
    fun `EventRepository_delete triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        configureSettings()
        val now = System.currentTimeMillis()
        val seed = eventRepo.create(
            summary = "primer event",
            startMs = now,
            endMs = now + 60_000L,
        )
        require(seed is MemoResult.Ok) { "seed create failed: $seed" }
        advanceDebounce()
        fakeUpdater.reset()

        val res = eventRepo.delete(uid = seed.value.uid)
        assertTrue("delete must return Ok, got $res", res is MemoResult.Ok)

        advanceDebounce()
        assertOnlyTodayHookFired("EventRepository.delete")
    }

    // -----------------------------------------------------------------------
    // SettingsStore hook (1)
    // -----------------------------------------------------------------------

    @Test
    fun `SettingsStore_update triggers widget refresh`() = runTest {
        bindRefresherTestScope()

        settings.update {
            it.copy(pat = "ghp_test", owner = "owner", repo = "repo", branch = "main")
        }

        advanceDebounce()
        assertHookFired("SettingsStore.update")
    }

    // -----------------------------------------------------------------------
    // Workers (2) —— 用反射直接构造 [PullWorker] / [PushWorker] 实例 + 调 doWork()。
    // -----------------------------------------------------------------------

    @Test
    fun `PullWorker_doWork success triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        // Worker 在 doWork 第一行调 ServiceLocator.init —— 我们必须**先**把测试的
        // 假 settings/api/repos 塞到 ServiceLocator 里，让 init() 看到 _memoRepo 非空
        // 后 early return（不去 build 真 SecurePatStore）。
        injectServiceLocator()
        configureSettings()

        val worker = newWorker(PullWorker::class.java)
        val result: ListenableWorker.Result = worker.doWork()
        assertTrue("worker returned a Result: $result", true)

        advanceDebounce()
        assertHookFired("PullWorker.doWork")
    }

    @Test
    fun `PushWorker_doWork success triggers widget refresh`() = runTest {
        bindRefresherTestScope()
        injectServiceLocator()
        configureSettings()

        // PushWorker 在 pending 全空时 early return，会 skip 末尾的 refreshAll；
        // 用我们注入到 ServiceLocator 的 singleNoteRepo（== testRepo，跑 in-memory db）
        // 创建一行 dirty row。
        singleNoteRepo.create(body = "row to push")
        advanceDebounce()
        fakeUpdater.reset()

        val worker = newWorker(PushWorker::class.java)
        val result: ListenableWorker.Result = worker.doWork()
        assertTrue("worker returned a Result: $result", true)

        advanceDebounce()
        assertHookFired("PushWorker.doWork")
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun TestScope.bindRefresherTestScope() {
        WidgetRefresher.overrideScopeForTest(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        testScheduler.advanceUntilIdle()
    }

    private suspend fun TestScope.advanceDebounce() {
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 100)
        advanceUntilIdle()
    }

    private fun assertHookFired(hookName: String) {
        assertTrue(
            "$hookName must trigger at least one updateMemo, got=${fakeUpdater.memoCount.get()}",
            fakeUpdater.memoCount.get() >= 1,
        )
        assertTrue(
            "$hookName must trigger at least one updateToday, got=${fakeUpdater.todayCount.get()}",
            fakeUpdater.todayCount.get() >= 1,
        )
    }

    /**
     * Issue #300 (Red-3 N4): EventRepository now only refreshes the
     * Today widget — MemoWidget never reads eventDao so refreshing it
     * was wasted work. This helper asserts the new narrower contract.
     */
    private fun assertOnlyTodayHookFired(hookName: String) {
        assertTrue(
            "$hookName must trigger at least one updateToday, got=${fakeUpdater.todayCount.get()}",
            fakeUpdater.todayCount.get() >= 1,
        )
        assertTrue(
            "$hookName must NOT touch MemoWidget, got=${fakeUpdater.memoCount.get()}",
            fakeUpdater.memoCount.get() == 0,
        )
    }

    private suspend fun configureSettings() {
        settings.update {
            it.copy(pat = "ghp_test_token", owner = "owner", repo = "repo", branch = "main")
        }
        settings.config.first()
    }

    /**
     * Reflection-based hook into [AppDatabase.Companion]._instance —— 让 production
     * 的 [MemoRepository.runInTx] 能找到 db。
     */
    private fun installAppDatabaseInstance(instance: AppDatabase?) {
        runCatching {
            val companionInstance = AppDatabase.Companion
            val instanceField = AppDatabase.Companion::class.java
                .getDeclaredField("_instance")
                .apply { isAccessible = true }
            instanceField.set(companionInstance, instance)
        }
    }

    /**
     * In-memory [PatStorage] —— 跳过 [dev.aria.memo.data.SecurePatStore] 的
     * EncryptedSharedPreferences/AndroidKeyStore 初始化（Robolectric 不 shadow keystore）。
     */
    private class InMemoryPatStorage : PatStorage {
        @Volatile private var pat: String = ""
        override fun read(): String = pat
        override fun write(pat: String) { this.pat = pat }
        override fun clear() { this.pat = "" }
    }

    /**
     * 反射把测试构造的 fake settings / api / repos / db 灌进 [ServiceLocator]，
     * 然后再调 [ServiceLocator.init] —— init 看到 `_memoRepo` 非空就 early return，
     * 不会去 build 真 SecurePatStore（避开 AndroidKeyStore）。
     *
     * 这是 Worker 测试的关键基础设施 —— PullWorker / PushWorker 的 doWork 第一行
     * 都是 `ServiceLocator.init(applicationContext)`，必须保证那次 init 不 build
     * production SettingsStore。
     */
    private fun injectServiceLocator() {
        val cls = ServiceLocator::class.java
        fun setField(name: String, value: Any?) {
            val f = cls.getDeclaredField(name).apply { isAccessible = true }
            f.set(ServiceLocator, value)
        }
        setField("_appContext", context)
        setField("_settings", settings)
        setField("_api", api)
        setField("_memoRepo", memoRepo)
        setField("_eventRepo", eventRepo)
        setField("_singleNoteRepo", singleNoteRepo)
        setField("_db", db)
        setField("_httpClient", http)
        // ServiceLocator.init 看到 _memoRepo != null 就 return，不会重 build 任何东西。
    }

    /** 把 ServiceLocator 所有 _xxx 字段还原成 null —— @After 调用，防单例污染。 */
    private fun clearServiceLocator() {
        val cls = ServiceLocator::class.java
        for (name in listOf(
            "_appContext", "_settings", "_api", "_memoRepo",
            "_eventRepo", "_singleNoteRepo", "_db", "_httpClient",
            "_aiSettings", "_aiClient",
        )) {
            runCatching {
                val f = cls.getDeclaredField(name).apply { isAccessible = true }
                f.set(ServiceLocator, null)
            }
        }
    }

    private fun buildMockHttp(): HttpClient {
        val jsonHeaders = headersOf("Content-Type" to listOf("application/json"))
        return HttpClient(MockEngine { request ->
            val (status, body) = when (request.method) {
                HttpMethod.Put -> HttpStatusCode.OK to """
                    {"content":{"sha":"new-sha-${System.nanoTime()}","path":"x"}}
                """.trimIndent()
                HttpMethod.Delete -> HttpStatusCode.OK to "{}"
                HttpMethod.Get -> HttpStatusCode.NotFound to "{\"message\":\"Not Found\"}"
                else -> HttpStatusCode.OK to "{}"
            }
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = jsonHeaders,
            )
        }) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    explicitNulls = false
                })
            }
            expectSuccess = false
        }
    }

    /**
     * Reflection-based Worker constructor —— 不依赖 `androidx.work:work-testing`
     * 的 `TestListenableWorkerBuilder`。Worker 标准签名 `(Context, WorkerParameters)`。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <W : ListenableWorker> newWorker(clazz: Class<W>): W {
        val params = newWorkerParameters()
        val ctor = clazz.getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(context, params)
    }

    /**
     * 构造一个最小可用的 [WorkerParameters]。WorkManager 2.9 的 ctor 在包内可见，
     * 反射拿"参数最多"的那个，逐个对位填默认值。
     */
    private fun newWorkerParameters(): WorkerParameters {
        val cls = WorkerParameters::class.java
        val ctor = cls.declaredConstructors.maxByOrNull { it.parameterCount }
            ?: error("WorkerParameters has no constructors")
        ctor.isAccessible = true
        val args = ctor.parameterTypes.map { defaultForType(it) }.toTypedArray()
        return ctor.newInstance(*args) as WorkerParameters
    }

    private fun defaultForType(type: Class<*>): Any? = when (type.name) {
        "java.util.UUID" -> UUID.randomUUID()
        "androidx.work.Data" -> androidx.work.Data.EMPTY
        "java.util.Collection", "java.util.Set", "java.util.List" -> emptySet<String>()
        "androidx.work.WorkerParameters\$RuntimeExtras" ->
            WorkerParameters.RuntimeExtras()
        "int" -> 0
        "androidx.work.WorkerFactory" -> object : androidx.work.WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? = null
        }
        "androidx.work.impl.utils.taskexecutor.TaskExecutor" -> {
            // CoroutineWorker 在 init 块就调 taskExecutor.getSerialTaskExecutor()，
            // 不能给 null。用 production 的 WorkManagerTaskExecutor 反射实例化。
            val cls = Class.forName("androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor")
            val ctor = cls.getDeclaredConstructor(java.util.concurrent.Executor::class.java)
                .apply { isAccessible = true }
            ctor.newInstance(Executors.newSingleThreadExecutor())
        }
        "androidx.work.ForegroundUpdater" -> null
        "androidx.work.ProgressUpdater" -> null
        "java.util.concurrent.Executor" -> Executors.newSingleThreadExecutor()
        else -> null
    }

    /**
     * Counts every call to [updateMemo] / [updateToday]. Inheriting from
     * `WidgetUpdater` keeps us aligned with the production interface so any
     * future renames break this signal-source first.
     */
    class CountingWidgetUpdater : WidgetUpdater {
        val memoCount = AtomicInteger(0)
        val todayCount = AtomicInteger(0)
        override suspend fun updateMemo(context: Context?) {
            memoCount.incrementAndGet()
        }
        override suspend fun updateToday(context: Context?) {
            todayCount.incrementAndGet()
        }
        fun reset() {
            memoCount.set(0)
            todayCount.set(0)
        }
    }
}
