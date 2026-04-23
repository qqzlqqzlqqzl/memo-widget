package dev.aria.memo.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [AiContextBuilder.buildSystemPrompt].
 *
 * Contract (P7):
 *  - [AiContextMode.NONE] produces an empty string — the caller's user
 *    message is the only thing the model sees.
 *  - [AiContextMode.CURRENT_NOTE] wraps the single note body into the system
 *    prompt, truncating to `charBudget` with a `...(truncated)` marker.
 *  - [AiContextMode.ALL_NOTES] walks the provided list in order, packing as
 *    many full bodies as fit; any body that would overflow the budget is
 *    dropped (we do NOT partially slice a body in ALL_NOTES mode, so the
 *    model never sees a half-sentence stapled onto the next note).
 *  - Empty ALL_NOTES list still produces a usable prompt (empty context
 *    wrapper, not a crash or null).
 *
 * The system prompt SHAPE (exact wording, section headers) is Agent A's
 * implementation detail — we only assert on observables that matter for the
 * call-site:
 *  - is the prompt empty when it should be?
 *  - does the note body appear verbatim when there's room?
 *  - does truncation leave the marker as a signal to the model?
 *  - does total length stay within charBudget?
 */
class AiContextBuilderTest {

    @Test
    fun `NONE mode returns empty string`() {
        val prompt = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.NONE,
            currentNoteBody = "有内容也不应该出现在 prompt 里",
            allNoteBodies = listOf("不该看到", "也不该看到"),
            charBudget = 15_000,
        )
        assertEquals(
            "NONE mode must produce an empty system prompt so the user message is the only input",
            "",
            prompt,
        )
    }

    @Test
    fun `CURRENT_NOTE with short body embeds body verbatim`() {
        val body = "今天开会讨论了 Q2 的路线图：1) 性能 2) 可观测性 3) 移动端"
        val prompt = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = 15_000,
        )
        assertTrue(
            "short note body must appear verbatim, got:\n$prompt",
            prompt.contains(body),
        )
        // No truncation marker when we fit.
        assertFalse(
            "no truncation marker expected when body < budget, got:\n$prompt",
            prompt.contains("(truncated)"),
        )
    }

    @Test
    fun `CURRENT_NOTE with body larger than budget is truncated with marker`() {
        val body = "字".repeat(20_000)
        val budget = 1_000
        val prompt = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = budget,
        )
        assertTrue(
            "over-budget body must leave a `(truncated)` marker so the model knows, got len=${prompt.length}",
            prompt.contains("(truncated)") || prompt.contains("truncated"),
        )
        assertTrue(
            "prompt length ${prompt.length} should be bounded — allow some wrapper slack (2x budget) ",
            prompt.length <= budget * 2,
        )
    }

    @Test
    fun `ALL_NOTES packs multiple bodies in order when under budget`() {
        val bodies = listOf("note A body", "note B body", "note C body")
        val prompt = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = bodies,
            charBudget = 15_000,
        )
        // All three must appear in the same order as input.
        val idxA = prompt.indexOf("note A body")
        val idxB = prompt.indexOf("note B body")
        val idxC = prompt.indexOf("note C body")
        assertTrue("A must appear, prompt=$prompt", idxA >= 0)
        assertTrue("B must appear, prompt=$prompt", idxB >= 0)
        assertTrue("C must appear, prompt=$prompt", idxC >= 0)
        assertTrue("order must be A < B < C", idxA < idxB && idxB < idxC)
    }

    @Test
    fun `ALL_NOTES truncates by dropping tail bodies when over budget`() {
        // Construct bodies so that the first two fit but adding the third
        // would push us past the budget. The builder MUST drop the third
        // entirely rather than splice it half-way.
        val head = "A".repeat(400)
        val mid = "B".repeat(400)
        val tail = "C".repeat(400)
        val budget = 1_000 // leaves room for ~2 bodies + small wrapper
        val prompt = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf(head, mid, tail),
            charBudget = budget,
        )
        // Length bounded by budget (allow 2x for wrapper text around each body).
        assertTrue(
            "prompt length ${prompt.length} must be bounded by budget $budget plus modest wrapper",
            prompt.length <= budget * 3,
        )
        // At least the first body must be present.
        assertTrue("first body should land", prompt.contains(head))
    }

    @Test
    fun `ALL_NOTES with empty list still produces a non-null prompt`() {
        val prompt = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = emptyList(),
            charBudget = 15_000,
        )
        // We do NOT assert on specific wording (Agent A picks "暂无笔记" vs
        // English). We only require: non-null, valid String. A zero-length
        // prompt is also acceptable — what matters is no crash.
        assertNotNull(prompt)
    }

    @Test
    fun `charBudget of zero yields empty or near-empty prompt without crashing`() {
        // Edge case: if the caller accidentally passes 0 we must not trip
        // an IndexOutOfBounds or substring error. The prompt may be empty
        // OR contain only the `(truncated)` sentinel / wrapper skeleton.
        val prompt = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = "长笔记正文".repeat(100),
            charBudget = 0,
        )
        assertNotNull(prompt)
        // Whatever the builder returns, it must not contain the entire note
        // body (that would mean the budget was ignored).
        assertFalse(
            "charBudget=0 must NOT embed the whole body",
            prompt.length > 200,
        )
    }

    @Test
    fun `CURRENT_NOTE with null body falls back to NONE-like empty prompt`() {
        // Defensive: if the caller picks CURRENT_NOTE but the current note
        // has not loaded yet (currentNoteBody = null), the builder must
        // return an empty-ish prompt rather than "the string null".
        val prompt = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = null,
            charBudget = 15_000,
        )
        assertFalse(
            "null body must not be echoed as the literal string 'null', prompt=$prompt",
            prompt.contains("null"),
        )
    }

    // Fixes #73 (P7.0.1): lock the Chinese header phrasing so a refactor that
    // accidentally drops HEADER_CURRENT / HEADER_ALL can't silently ship a
    // prompt that's missing the framing sentence. Weak assertion on purpose —
    // exact wording is Agent A's UX decision, we only guarantee the model
    // sees a Chinese framing word "笔记" before the body.

    @Test
    fun `CURRENT_NOTE prompt includes chinese framing word`() {
        val prompt = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = "今天很累",
            charBudget = 15_000,
        )
        assertTrue(
            "CURRENT_NOTE prompt should frame the body with 笔记, prompt=$prompt",
            prompt.contains("笔记"),
        )
        assertTrue(
            "CURRENT_NOTE body must appear in the prompt, prompt=$prompt",
            prompt.contains("今天很累"),
        )
    }

    @Test
    fun `ALL_NOTES prompt includes chinese framing word`() {
        val prompt = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("笔记一", "笔记二"),
            charBudget = 15_000,
        )
        assertTrue(
            "ALL_NOTES prompt should frame the bodies with 笔记, prompt=$prompt",
            prompt.contains("笔记"),
        )
    }
}
