package dev.aria.memo.ui.ai

import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.SingleNoteRepository
import dev.aria.memo.data.ai.AiClient
import dev.aria.memo.data.ai.AiConfig
import dev.aria.memo.data.ai.AiContextMode
import dev.aria.memo.data.ai.AiMessage
import dev.aria.memo.data.ai.AiSettingsStore
import dev.aria.memo.data.local.NoteFileEntity
import dev.aria.memo.data.local.SingleNoteEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AiChatViewModel]. We wire a real [AiClient] over Ktor's
 * MockEngine so the coroutine seams are exercised end-to-end (request
 * serialisation → response parsing → MemoResult wrapping). For the two
 * repositories we rely on subclass fakes; this assumes Agent B has made
 * [MemoRepository] and [SingleNoteRepository] `open class` OR has extracted
 * minimal interfaces the VM consumes.
 *
 * **Reconciliation point for the main agent**: if A/B keep the repos
 * `final` / only-constructible-with-Context, replace the fakes below with a
 * minimal-interface wrapper in the VM (recommended) or drop the repo-
 * dependent tests (still keep setInput / setContextMode / clearError / empty
 * input / send-while-sending cases, which don't require a live repo).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val jsonHeaders = headersOf("Content-Type" to listOf("application/json"))

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -- harness -----------------------------------------------------------

    private val validConfig = AiConfig(
        providerUrl = "https://api.openai.com/v1/chat/completions",
        model = "gpt-4o-mini",
        apiKey = "sk-test",
    )

    private fun okHttp(assistantReply: String = "hello from AI"): HttpClient =
        HttpClient(MockEngine { _ ->
            respond(
                content = ByteReadChannel(
                    """
                    {
                      "choices":[
                        {"index":0,"message":{"role":"assistant","content":"$assistantReply"},"finish_reason":"stop"}
                      ]
                    }
                    """.trimIndent(),
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

    private fun errHttp(status: HttpStatusCode = HttpStatusCode.Unauthorized): HttpClient =
        HttpClient(MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{"error":{"message":"boom"}}"""),
                status = status,
                headers = jsonHeaders,
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

    private fun vmWith(
        aiClient: AiClient,
        memoRepo: MemoRepository = FakeMemoRepository(),
        singleNoteRepo: SingleNoteRepository = FakeSingleNoteRepository(),
        aiSettings: AiSettingsStore = FakeAiSettingsStore(validConfig),
        initialNoteUid: String? = null,
    ): AiChatViewModel = AiChatViewModel(
        aiClient = aiClient,
        memoRepo = memoRepo,
        singleNoteRepo = singleNoteRepo,
        aiSettings = aiSettings,
        initialNoteUid = initialNoteUid,
    )

    private fun realAiClient(http: HttpClient, config: AiConfig = validConfig): AiClient =
        AiClient(http = http, settings = FakeAiSettingsStore(config))

    // -- tests -------------------------------------------------------------

    @Test
    fun `initial state is empty and not sending`() {
        val vm = vmWith(aiClient = realAiClient(okHttp()))
        val s = vm.state.value
        assertEquals("", s.input)
        assertTrue("messages must start empty, got=${s.messages}", s.messages.isEmpty())
        assertEquals(false, s.isSending)
        assertNull(s.error)
        // Fixes #74 (P7.0.1): lock the negative case — no initialNoteUid means
        // the "当前笔记" FilterChip must be hidden.
        assertEquals(
            "hasCurrentNote must default to false when no note-scoped entry",
            false, s.hasCurrentNote,
        )
    }

    @Test
    fun `initialNoteUid sets hasCurrentNote to true (fixes #74)`() {
        val vm = vmWith(
            aiClient = realAiClient(okHttp()),
            initialNoteUid = "note-abc",
        )
        assertEquals(true, vm.state.value.hasCurrentNote)
    }

    @Test
    fun `initialNoteUid sets contextMode to CURRENT_NOTE`() {
        val vm = vmWith(
            aiClient = realAiClient(okHttp()),
            initialNoteUid = "note-abc",
        )
        assertEquals(
            "when entering from a specific note, default contextMode must be CURRENT_NOTE",
            AiContextMode.CURRENT_NOTE,
            vm.state.value.contextMode,
        )
    }

    @Test
    fun `setInput updates state input`() {
        val vm = vmWith(aiClient = realAiClient(okHttp()))
        vm.setInput("hi")
        assertEquals("hi", vm.state.value.input)
    }

    @Test
    fun `send appends user message then assistant reply on Ok`() = runTest {
        val vm = vmWith(aiClient = realAiClient(okHttp(assistantReply = "你好 from AI")))
        vm.setInput("你好")
        vm.send().join()

        val s = vm.state.value
        assertEquals("after send, isSending must flip back to false", false, s.isSending)
        assertEquals(
            "input must be cleared after dispatch",
            "", s.input,
        )
        assertEquals(
            "expected two messages: user + assistant",
            2, s.messages.size,
        )
        assertEquals("user", s.messages[0].role)
        assertEquals("你好", s.messages[0].content)
        assertEquals("assistant", s.messages[1].role)
        assertTrue(
            "assistant message must carry the model reply, got=${s.messages[1].content}",
            s.messages[1].content.contains("你好 from AI"),
        )
        assertNull(s.error)
    }

    @Test
    fun `send Err surfaces error and clears isSending`() = runTest {
        val vm = vmWith(aiClient = realAiClient(errHttp(HttpStatusCode.Unauthorized)))
        vm.setInput("anything")
        vm.send().join()

        val s = vm.state.value
        assertEquals(false, s.isSending)
        assertNotNull("error must be non-null after failed send", s.error)
        // Bug-1 H8 (#108): on Err the optimistic user turn is rolled back AND
        // the input field is restored, so the user can retry without
        // re-typing. The assistant never received this turn — leaving it in
        // the transcript would conflict with the multi-turn context fed to
        // [AiClient] on retry.
        assertTrue(
            "user turn must be rolled back so the transcript is clean",
            s.messages.none { it.role == "user" && it.content == "anything" },
        )
        assertEquals("input must be restored for retry", "anything", s.input)
    }

    @Test
    fun `clearError resets error to null`() = runTest {
        val vm = vmWith(aiClient = realAiClient(errHttp(HttpStatusCode.Unauthorized)))
        vm.setInput("x")
        vm.send().join()
        assertNotNull("precondition: error set", vm.state.value.error)

        vm.clearError()
        assertNull("clearError must null the error", vm.state.value.error)
    }

    @Test
    fun `setContextMode changes contextMode in state`() {
        val vm = vmWith(aiClient = realAiClient(okHttp()))
        assertEquals(AiContextMode.NONE, vm.state.value.contextMode)
        vm.setContextMode(AiContextMode.ALL_NOTES)
        assertEquals(AiContextMode.ALL_NOTES, vm.state.value.contextMode)
        vm.setContextMode(AiContextMode.CURRENT_NOTE)
        assertEquals(AiContextMode.CURRENT_NOTE, vm.state.value.contextMode)
    }

    @Test
    fun `send with blank input is a no-op`() = runTest {
        var callCount = 0
        val http = HttpClient(MockEngine { _ ->
            callCount++
            respond(
                content = ByteReadChannel(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = vmWith(aiClient = realAiClient(http))
        // Nothing typed.
        vm.send()
        vm.setInput("   \n\t  ")
        vm.send()

        assertEquals("blank/whitespace send must not call the provider", 0, callCount)
        assertTrue("no messages should land", vm.state.value.messages.isEmpty())
    }

    @Test
    fun `send while isSending is guarded`() = runTest {
        // Two rapid-fire sends. The second must be swallowed (or at least
        // must not launch a second provider request) so users tapping twice
        // on a flaky network don't spam the API.
        var callCount = 0
        val http = HttpClient(MockEngine { _ ->
            callCount++
            respond(
                content = ByteReadChannel(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = vmWith(aiClient = realAiClient(http))

        vm.setInput("一次请求")
        // First send launches the network call but hasn't joined yet; second
        // send must be swallowed by either the isSending guard OR the
        // already-cleared input blank-guard. We join only the first job so
        // the assertion sees callCount after exactly one network round-trip.
        val job1 = vm.send()
        vm.send()
        job1.join()

        assertEquals(
            "rapid back-to-back sends must collapse to at most 1 network call",
            1, callCount,
        )
    }

    @Test
    fun `multi-turn conversation passes prior transcript as priorMessages (fixes #68)`() = runTest {
        // Capture every request body the AiClient fires at the provider so we
        // can assert the second turn contains the first turn's user+assistant
        // rows as priorMessages (OpenAI chat format).
        val capturedBodies = mutableListOf<String>()
        var turn = 0
        val http = HttpClient(MockEngine { request ->
            capturedBodies += request.body.toByteArray().decodeToString()
            turn += 1
            val content = if (turn == 1) "hello back" else "second reply"
            respond(
                content = ByteReadChannel(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = vmWith(aiClient = realAiClient(http))

        vm.setInput("hi")
        vm.send().join()
        vm.setInput("how are you")
        vm.send().join()

        assertEquals("two network turns expected", 2, capturedBodies.size)
        val secondBody = capturedBodies[1]
        // The second request's messages list must contain the first user
        // ("hi") AND the first assistant ("hello back") before the new
        // "how are you" — i.e. the transcript accumulates.
        assertTrue(
            "second-turn body must carry first user turn, body=$secondBody",
            secondBody.contains("\"hi\""),
        )
        assertTrue(
            "second-turn body must carry first assistant reply, body=$secondBody",
            secondBody.contains("\"hello back\""),
        )
        assertTrue(
            "second-turn body must carry the new user message, body=$secondBody",
            secondBody.contains("\"how are you\""),
        )
        // Ordering sanity: first user appears before new user.
        val firstUserPos = secondBody.indexOf("\"hi\"")
        val newUserPos = secondBody.indexOf("\"how are you\"")
        assertTrue(
            "first user turn must appear before the new user turn, order=$firstUserPos vs $newUserPos",
            firstUserPos in 0 until newUserPos,
        )
    }
}

/* -------------------------------------------------------------------------
 * Test doubles.
 *
 * These rely on the assumption that Agent A exposes [AiSettingsStore] as
 * either an interface OR an open class with a no-arg (or config-accepting)
 * secondary constructor. Same for [MemoRepository] / [SingleNoteRepository]:
 * both need `open` modifiers on the class declaration + the methods the VM
 * exercises. If they are `final`, the main agent will either:
 *   - introduce an interface façade the VM consumes and route real instances
 *     through it (recommended), OR
 *   - swap these fakes for Mockito/mockk (adds a test dependency, avoid).
 * ------------------------------------------------------------------------- */

private class FakeAiSettingsStore(private val config: AiConfig) : AiSettingsStore() {
    override suspend fun current(): AiConfig = config
    // Fixes #61 (P7.0.1): observe must stay live so VM.init's collect{} keeps
    // the isConfigured flag accurate — one-shot flowOf would complete
    // collection immediately and any future isConfigured gating would rot.
    override fun observe(): kotlinx.coroutines.flow.Flow<AiConfig> =
        kotlinx.coroutines.flow.flow {
            emit(config)
            kotlinx.coroutines.awaitCancellation()
        }
}

private class FakeMemoRepository : MemoRepository() {
    override fun observeNotes(): Flow<List<NoteFileEntity>> = emptyFlow()
    override suspend fun getContentForPath(path: String): String? = null
}

private class FakeSingleNoteRepository : SingleNoteRepository() {
    override fun observeAll(): Flow<List<SingleNoteEntity>> = MutableStateFlow(emptyList())
    override suspend fun get(uid: String): SingleNoteEntity? = null
}
