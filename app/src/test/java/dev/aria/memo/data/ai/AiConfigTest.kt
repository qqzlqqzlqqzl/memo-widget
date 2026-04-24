package dev.aria.memo.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [AiConfig.isConfigured] — the single predicate the AI
 * chat feature uses to decide whether to surface the empty-state
 * "点这里去设置" affordance vs actually dispatching a chat request.
 *
 * Contract (P7):
 *  - [isConfigured] returns true ONLY when all three of providerUrl, model,
 *    apiKey are non-blank (i.e. [String.isBlank] false).
 *  - Whitespace-only strings are treated as unconfigured (this matches the
 *    legacy [dev.aria.memo.data.AppConfig.isConfigured] policy).
 *
 * We keep the tests trivial on purpose — this is a guard rail so that if a
 * future refactor swaps the predicate for `isNotEmpty` (which would quietly
 * let `" "` through) the regression lands loud and early.
 */
class AiConfigTest {

    @Test
    fun `all three fields populated yields isConfigured true`() {
        val config = AiConfig(
            providerUrl = "https://api.openai.com/v1/chat/completions",
            model = "gpt-4o-mini",
            apiKey = "sk-abc",
        )
        assertTrue(config.isConfigured)
    }

    @Test
    fun `blank providerUrl yields false`() {
        val config = AiConfig(
            providerUrl = "",
            model = "gpt-4o-mini",
            apiKey = "sk-abc",
        )
        assertFalse(config.isConfigured)
    }

    @Test
    fun `blank model yields false`() {
        val config = AiConfig(
            providerUrl = "https://api.openai.com/v1/chat/completions",
            model = "",
            apiKey = "sk-abc",
        )
        assertFalse(config.isConfigured)
    }

    @Test
    fun `blank apiKey yields false`() {
        val config = AiConfig(
            providerUrl = "https://api.openai.com/v1/chat/completions",
            model = "gpt-4o-mini",
            apiKey = "",
        )
        assertFalse(config.isConfigured)
    }

    @Test
    fun `all three blank yields false`() {
        val config = AiConfig(providerUrl = "", model = "", apiKey = "")
        assertFalse(config.isConfigured)
    }

    @Test
    fun `whitespace-only fields count as blank`() {
        // Users paste a stray space when copying from a secret manager; treat
        // " " the same as "" so we don't hit the provider with a header like
        // `Authorization: Bearer  ` and get an opaque 401.
        val a = AiConfig(providerUrl = "   ", model = "gpt-4o", apiKey = "sk")
        val b = AiConfig(providerUrl = "https://x", model = "\t\n", apiKey = "sk")
        val c = AiConfig(providerUrl = "https://x", model = "gpt-4o", apiKey = " \t ")
        assertFalse("whitespace providerUrl", a.isConfigured)
        assertFalse("whitespace model", b.isConfigured)
        assertFalse("whitespace apiKey", c.isConfigured)
    }

    @Test
    fun `isConfigured is pure -- no state changes across reads`() {
        val config = AiConfig(
            providerUrl = "https://api.openai.com/v1/chat/completions",
            model = "gpt-4o-mini",
            apiKey = "sk-abc",
        )
        // Sanity: repeated evaluation MUST yield the same answer (rules out
        // a regression where the getter reads from a mutable source).
        assertEquals(config.isConfigured, config.isConfigured)
        assertTrue(config.isConfigured)
    }
}
