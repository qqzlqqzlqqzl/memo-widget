package dev.aria.memo.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.aria.memo.ui.settings.PatStatus
import dev.aria.memo.ui.settings.SettingsUiState
import dev.aria.memo.ui.theme.MemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * One test per [PatStatus] state plus the unconfigured branch. Catches
 * the kind of regression that originally drove Fix-X1 — the card said
 * "已就绪" while the SyncBanner shouted "GitHub 拒绝访问" because the
 * card only checked "fields non-blank" and ignored the live PAT
 * verification result. Each state below has a unique headline so a
 * mismatch surfaces as an unambiguous test failure.
 */
class PatStatusCardUiTest {

    @get:Rule val compose = createComposeRule()

    private fun configured(status: PatStatus) = SettingsUiState(
        pat = "ghp_test",
        owner = "qqzlqqzlqqzl",
        repo = "memos",
        branch = "main",
        loaded = true,
        patStatus = status,
    )

    @Test
    fun unconfigured_state_lists_missing_fields() {
        compose.setContent {
            MemoTheme {
                PatStatusCard(
                    state = SettingsUiState(loaded = true),
                    onTestConnection = {},
                    onReauth = {},
                )
            }
        }
        compose.onNodeWithText("还缺：", substring = true).assertIsDisplayed()
        // Don't assert exact field list — that's documented in
        // SettingsUiState.missingFields and verified by unit tests.
    }

    @Test
    fun unknown_state_shows_verify_action() {
        var verifyClicks = 0
        compose.setContent {
            MemoTheme {
                PatStatusCard(
                    state = configured(PatStatus.Unknown),
                    onTestConnection = { verifyClicks++ },
                    onReauth = {},
                )
            }
        }
        compose.onNodeWithText("配置已就绪").assertIsDisplayed()
        compose.onNodeWithText("重新验证").assertIsDisplayed().performClick()
        assertEquals(1, verifyClicks)
    }

    @Test
    fun verifying_state_shows_progress_text() {
        compose.setContent {
            MemoTheme {
                PatStatusCard(
                    state = configured(PatStatus.Verifying),
                    onTestConnection = {},
                    onReauth = {},
                )
            }
        }
        // 2026-05-11: 文案重写, headline 从 "正在验证 PAT…" → "正在验证…"
        // (PAT 字眼藏到 onboarding 之后), 用 substring 不再断言 "PAT" 字面。
        compose.onNodeWithText("正在验证…").assertIsDisplayed()
    }

    @Test
    fun valid_state_shows_healthy_message() {
        compose.setContent {
            MemoTheme {
                PatStatusCard(
                    state = configured(PatStatus.Valid(checkedAt = 0L)),
                    onTestConnection = {},
                    onReauth = {},
                )
            }
        }
        // 2026-05-11: "PAT 状态：有效" → "登录信息有效"
        compose.onNodeWithText("登录信息有效").assertIsDisplayed()
    }

    @Test
    fun invalid_state_offers_reauth_then_verify() {
        var reauthClicks = 0
        var verifyClicks = 0
        compose.setContent {
            MemoTheme {
                PatStatusCard(
                    state = configured(PatStatus.Invalid("PAT 已被撤销", checkedAt = 0L)),
                    onTestConnection = { verifyClicks++ },
                    onReauth = { reauthClicks++ },
                )
            }
        }
        // 2026-05-11: "⚠️ PAT 已失效，请更新" → "登录信息已失效"
        compose.onNodeWithText("登录信息已失效", substring = true).assertIsDisplayed()
        compose.onNodeWithText("用 GitHub 重新登录").assertIsDisplayed().performClick()
        assertEquals(1, reauthClicks)
        compose.onNodeWithText("重新验证").performClick()
        assertEquals(1, verifyClicks)
    }

    @Test
    fun check_failed_state_offers_retry() {
        var retryClicks = 0
        compose.setContent {
            MemoTheme {
                PatStatusCard(
                    state = configured(PatStatus.CheckFailed("超时", checkedAt = 0L)),
                    onTestConnection = { retryClicks++ },
                    onReauth = {},
                )
            }
        }
        compose.onNodeWithText("暂时验证不上", substring = true).assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        assertEquals(1, retryClicks)
    }
}
