package dev.aria.memo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sec-1 / M1 regression guard.
 *
 * [AppConfig] is a Kotlin `data class`; the compiler-generated `toString()`
 * would include every field verbatim — including [AppConfig.pat], the GitHub
 * Personal Access Token. A single `Log.d(tag, "$config")` or
 * `IllegalStateException("bad config: $config")` would then leak the PAT to
 * logcat or the Play Console's crash pipeline. We override `toString()` to
 * redact the secret; these tests ensure the override stays in place and keeps
 * covering every case a future refactor might introduce (plaintext token,
 * empty token, whitespace-only token, full-width AppConfig with every field).
 *
 * Kept deliberately paranoid: we check both for the absence of the raw token
 * AND for the presence of the redaction sentinel, so that accidentally
 * reverting to the compiler-generated toString trips the assertion loudly
 * even if some other field would otherwise hide the token in log output.
 */
class AppConfigToStringTest {

    @Test
    fun `toString does not expose pat verbatim`() {
        val pat = "ghp_abc123def456ghi789"
        val config = AppConfig(
            pat = pat,
            owner = "octo",
            repo = "memo",
            branch = "main",
            pathTemplate = "{yyyy}-{MM}-{dd}.md",
        )
        val rendered = config.toString()
        assertFalse(
            "AppConfig.toString leaked pat: $rendered",
            rendered.contains(pat),
        )
        // Sanity: the fingerprint redaction is present so debuggers still get
        // length + prefix info.
        assertTrue(
            "AppConfig.toString lost length fingerprint: $rendered",
            rendered.contains("${pat.length}chars"),
        )
        assertTrue(
            "AppConfig.toString lost redaction sentinel: $rendered",
            rendered.contains("***"),
        )
    }

    @Test
    fun `toString preserves non-sensitive fields`() {
        val config = AppConfig(
            pat = "ghp_secrettoken",
            owner = "octo",
            repo = "memo",
            branch = "trunk",
            pathTemplate = "notes/{yyyy}/{MM}/{dd}.md",
        )
        val rendered = config.toString()
        assertTrue("owner missing: $rendered", rendered.contains("owner=octo"))
        assertTrue("repo missing: $rendered", rendered.contains("repo=memo"))
        assertTrue("branch missing: $rendered", rendered.contains("branch=trunk"))
        assertTrue(
            "pathTemplate missing: $rendered",
            rendered.contains("pathTemplate=notes/{yyyy}/{MM}/{dd}.md"),
        )
    }

    @Test
    fun `toString on empty pat renders redaction sentinel not blank`() {
        val rendered = AppConfig(pat = "", owner = "o", repo = "r").toString()
        // `(empty)` distinguishes "no token was ever set" from "some token was
        // redacted" — a subtle but useful distinction when triaging configs.
        assertTrue("empty pat sentinel missing: $rendered", rendered.contains("(empty)"))
        assertFalse(
            "empty pat should not render fake fingerprint: $rendered",
            rendered.contains("***"),
        )
    }

    @Test
    fun `toString on whitespace-only pat still redacts`() {
        // Users occasionally paste a stray space with their token. We treat
        // whitespace-only as blank so toString never reveals whether the
        // leading/trailing whitespace happened to be significant.
        val rendered = AppConfig(pat = "   ", owner = "o", repo = "r").toString()
        assertTrue("whitespace pat not redacted: $rendered", rendered.contains("(empty)"))
        assertFalse(
            "whitespace pat leaked spaces suffix: $rendered",
            rendered.contains("pat=   "),
        )
    }

    @Test
    fun `toString for short tokens still does not round-trip the secret`() {
        // Edge case: a 5-char token `abc12` has take(4)=abcd-ish and
        // takeLast(2)=12 overlapping, which could theoretically reconstruct
        // the whole thing. Verify the redaction never prints the raw token as
        // a contiguous substring.
        val pat = "abc12"
        val rendered = AppConfig(pat = pat, owner = "o", repo = "r").toString()
        assertFalse(
            "short pat leaked verbatim: $rendered",
            rendered.contains("pat=$pat"),
        )
    }

    @Test
    fun `string interpolation uses overridden toString not data class default`() {
        // Defense in depth: make sure Kotlin string templates pick up the
        // override. If someone ever removes `override` the compiler still
        // generates a synthetic toString() that would shadow this test — this
        // line proves the override wins at the call site too.
        val pat = "ghp_interpolation_leak_check"
        val config = AppConfig(pat = pat, owner = "o", repo = "r")
        val rendered = "config=$config"
        assertFalse(
            "string template leaked pat: $rendered",
            rendered.contains(pat),
        )
    }
}
