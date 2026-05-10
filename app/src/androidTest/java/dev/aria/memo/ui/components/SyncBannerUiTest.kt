package dev.aria.memo.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.sync.SyncStatus
import dev.aria.memo.ui.theme.MemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the per-ErrorCode behaviour of [SyncBanner] — specifically the
 * "去设置" shortcut that should appear only for credential-class errors
 * (UNAUTHORIZED / NOT_CONFIGURED), not for transient ones (NETWORK / etc.).
 *
 * This is the kind of regression an inline-only banner would have hidden:
 * the original NoteListScreen inlined a copy of this component without the
 * shortcut, and the unit tests never noticed because they didn't exercise
 * the rendered UI.
 */
class SyncBannerUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun unauthorized_shows_go_to_settings_button() {
        var settingsClicks = 0
        compose.setContent {
            MemoTheme {
                SyncBanner(
                    status = SyncStatus.Error(ErrorCode.UNAUTHORIZED, "PAT expired"),
                    onDismiss = {},
                    onOpenSettings = { settingsClicks++ },
                )
            }
        }

        // 2026-05-11: SyncBanner 文案从术语化重写成大白话, 测试跟着断
        // 言新文案。具体替换 mapping 见 SyncBanner.kt 的 friendlyText。
        compose.onNodeWithText("GitHub 拒绝了", substring = true).assertIsDisplayed()
        compose.onNodeWithText("去设置").assertIsDisplayed().performClick()
        compose.onNodeWithText("知道了").assertIsDisplayed()

        assertEquals(1, settingsClicks)
    }

    @Test
    fun not_configured_also_shows_go_to_settings() {
        compose.setContent {
            MemoTheme {
                SyncBanner(
                    status = SyncStatus.Error(ErrorCode.NOT_CONFIGURED, "no PAT"),
                    onDismiss = {},
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("还没连仓库", substring = true).assertIsDisplayed()
        compose.onNodeWithText("去设置").assertIsDisplayed()
    }

    @Test
    fun network_error_dismiss_only_no_shortcut() {
        compose.setContent {
            MemoTheme {
                SyncBanner(
                    status = SyncStatus.Error(ErrorCode.NETWORK, "request timeout"),
                    onDismiss = {},
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("网络不太行", substring = true).assertIsDisplayed()
        // Network error → user can't act on it from this banner.
        compose.onNodeWithText("去设置").assertDoesNotExist()
        compose.onNodeWithText("知道了").assertIsDisplayed()
    }

    @Test
    fun dismiss_button_invokes_onDismiss() {
        var dismissClicks = 0
        compose.setContent {
            MemoTheme {
                SyncBanner(
                    status = SyncStatus.Error(ErrorCode.UNKNOWN, "?"),
                    onDismiss = { dismissClicks++ },
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("知道了").performClick()
        assertEquals(1, dismissClicks)
    }
}
