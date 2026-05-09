package dev.aria.memo.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.aria.memo.ui.theme.MemoTheme
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the three states of [OfflineBanner]:
 *  - online → renders nothing (early return).
 *  - offline + queue → "离线中 · N 条待同步".
 *  - offline + no queue → "离线中 · 笔记将在恢复联网后自动上传".
 *
 * The early return is the kind of bug that's invisible to humans (no text
 * on screen looks the same whether the banner ran or didn't), but breaks
 * dependency-injected tests that count rendered nodes.
 */
class OfflineBannerUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun online_renders_nothing() {
        compose.setContent {
            MemoTheme {
                OfflineBanner(isOnline = true, dirtyCount = 0)
            }
        }
        // Neither offline message variant should show.
        compose.onNodeWithText("离线中", substring = true).assertDoesNotExist()
    }

    @Test
    fun offline_with_queue_shows_count() {
        compose.setContent {
            MemoTheme {
                OfflineBanner(isOnline = false, dirtyCount = 3)
            }
        }
        compose.onNodeWithText("离线中 · 3 条待同步").assertIsDisplayed()
    }

    @Test
    fun offline_no_queue_shows_reassurance() {
        compose.setContent {
            MemoTheme {
                OfflineBanner(isOnline = false, dirtyCount = 0)
            }
        }
        compose.onNodeWithText("离线中 · 笔记将在恢复联网后自动上传").assertIsDisplayed()
    }
}
