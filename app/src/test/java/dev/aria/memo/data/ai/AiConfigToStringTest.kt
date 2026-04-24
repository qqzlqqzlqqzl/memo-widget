package dev.aria.memo.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sec-1 / M1 regression guard for [AiConfig.toString].
 *
 * [AiConfig] is a Kotlin `data class` whose compiler-generated `toString()`
 * would include [AiConfig.apiKey] — the bearer token sent to the OpenAI-
 * compatible provider. The class-level KDoc says the apiKey "MUST NOT log,
 * print or round-trip this value through toString()", but that was only a
 * verbal contract until now; any `Log.d(tag, "$config")` would still leak.
 *
 * These tests pin the override in place and document what it preserves for
 * debugging (providerUrl, model, redacted fingerprint of apiKey).
 */
class AiConfigToStringTest {

    @Test
    fun `toString does not expose apiKey verbatim`() {
        val apiKey = "sk-abcdef1234567890verysecret"
        val config = AiConfig(
            providerUrl = "https://api.openai.com/v1/chat/completions",
            model = "gpt-4o-mini",
            apiKey = apiKey,
        )
        val rendered = config.toString()
        assertFalse(
            "AiConfig.toString leaked apiKey: $rendered",
            rendered.contains(apiKey),
        )
        assertTrue(
            "AiConfig.toString lost length fingerprint: $rendered",
            rendered.contains("${apiKey.length}chars"),
        )
        assertTrue(
            "AiConfig.toString lost redaction sentinel: $rendered",
            rendered.contains("***"),
        )
    }

    @Test
    fun `toString preserves providerUrl and model for debugging`() {
        val config = AiConfig(
            providerUrl = "https://api.deepseek.com/v1/chat/completions",
            model = "deepseek-chat",
            apiKey = "sk-leakme",
        )
        val rendered = config.toString()
        assertTrue(
            "providerUrl missing: $rendered",
            rendered.contains("providerUrl=https://api.deepseek.com/v1/chat/completions"),
        )
        assertTrue("model missing: $rendered", rendered.contains("model=deepseek-chat"))
    }

    @Test
    fun `toString on empty apiKey renders empty sentinel`() {
        val rendered = AiConfig(
            providerUrl = "https://x",
            model = "m",
            apiKey = "",
        ).toString()
        assertTrue("empty sentinel missing: $rendered", rendered.contains("(empty)"))
        assertFalse(
            "empty apiKey should not render fingerprint: $rendered",
            rendered.contains("***"),
        )
    }

    @Test
    fun `toString on whitespace apiKey still redacts`() {
        val rendered = AiConfig(
            providerUrl = "https://x",
            model = "m",
            apiKey = "   \t",
        ).toString()
        assertTrue("whitespace not redacted: $rendered", rendered.contains("(empty)"))
        assertFalse(
            "whitespace apiKey leaked: $rendered",
            rendered.contains("apiKey=   "),
        )
    }

    @Test
    fun `string interpolation uses overridden toString not data class default`() {
        val apiKey = "sk-interpolation-check"
        val config = AiConfig(
            providerUrl = "https://x",
            model = "m",
            apiKey = apiKey,
        )
        val rendered = "cfg=$config"
        assertFalse(
            "string template leaked apiKey: $rendered",
            rendered.contains(apiKey),
        )
    }

    @Test
    fun `redaction keeps only first 4 and last 2 characters visible`() {
        val apiKey = "sk-1234567890abcdef"
        val config = AiConfig(
            providerUrl = "https://x",
            model = "m",
            apiKey = apiKey,
        )
        val rendered = config.toString()
        // The "first 4" + "last 2" policy lets us distinguish a key that
        // starts with `sk-` from one that starts with `xai-` without letting
        // anyone reconstruct the key from the logged value.
        assertTrue("prefix fingerprint missing: $rendered", rendered.contains("sk-1"))
        assertTrue("suffix fingerprint missing: $rendered", rendered.contains("ef"))
        // The middle section must be elided — check that nothing between
        // prefix and suffix survives.
        assertFalse(
            "middle of key leaked: $rendered",
            rendered.contains("234567890abcd"),
        )
    }
}
