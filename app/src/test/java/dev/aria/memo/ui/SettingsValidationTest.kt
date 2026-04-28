package dev.aria.memo.ui

import dev.aria.memo.ui.SettingsViewModel.Companion.validateApiKey
import dev.aria.memo.ui.SettingsViewModel.Companion.validateGithubPat
import dev.aria.memo.ui.SettingsViewModel.Companion.validateModelName
import dev.aria.memo.ui.SettingsViewModel.Companion.validateProviderUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure validator tests for the Settings inline error path (#140 / Bug-1
 * M16). Lives on the companion object so we can exercise the rules
 * without standing up the ViewModel + DataStore + WorkManager graph.
 */
class SettingsValidationTest {

    @Test fun `valid github classic PAT passes`() {
        assertNull(validateGithubPat("ghp_abcDEF123_xyz"))
    }

    @Test fun `valid github fine-grained PAT passes`() {
        assertNull(validateGithubPat("github_pat_11ABC_x9_y8z7"))
    }

    @Test fun `valid OAuth user token passes`() {
        assertNull(validateGithubPat("ghu_someoauthtokenvalue"))
    }

    @Test fun `blank PAT is rejected`() {
        assertEquals("PAT 不能为空", validateGithubPat(""))
    }

    @Test fun `PAT with space is rejected`() {
        assertNotNull(validateGithubPat("ghp_ has space"))
    }

    @Test fun `wrong-prefix PAT is rejected`() {
        assertEquals(
            "PAT 应以 ghp_ / github_pat_ / ghu_ 开头",
            validateGithubPat("notarealtoken_abc123"),
        )
    }

    @Test fun `blank provider URL is rejected`() {
        assertEquals("Provider URL 不能为空", validateProviderUrl(""))
    }

    @Test fun `provider URL without scheme is rejected`() {
        assertEquals("URL 必须以 https:// 开头", validateProviderUrl("api.openai.com/v1"))
    }

    @Test fun `http (non-tls) provider URL is rejected`() {
        assertEquals("URL 必须以 https:// 开头", validateProviderUrl("http://api.openai.com/v1"))
    }

    @Test fun `provider URL with whitespace is rejected`() {
        assertEquals("URL 不应包含空格", validateProviderUrl("https://api.openai .com/v1"))
    }

    @Test fun `well-formed provider URL passes`() {
        assertNull(validateProviderUrl("https://api.openai.com/v1/chat/completions"))
    }

    @Test fun `model name with space is rejected`() {
        assertEquals("Model 名不能含空格", validateModelName("gpt 4o"))
    }

    @Test fun `blank model is rejected`() {
        assertEquals("Model 不能为空", validateModelName(""))
    }

    @Test fun `valid model name passes`() {
        assertNull(validateModelName("gpt-4o-mini"))
    }

    @Test fun `blank API key is rejected`() {
        assertEquals("API Key 不能为空", validateApiKey(""))
    }

    @Test fun `non-blank API key passes`() {
        assertNull(validateApiKey("sk-anything"))
    }
}
