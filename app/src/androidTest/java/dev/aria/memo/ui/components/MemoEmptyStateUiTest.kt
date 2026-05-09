package dev.aria.memo.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.aria.memo.ui.theme.MemoTheme
import org.junit.Rule
import org.junit.Test

/**
 * First-ever instrumented Compose UI test in the repo. Up to this point all
 * "BDD" coverage was widget-internal mechanics in [WidgetBddTest] / [WidgetSmokeTest];
 * actual screen rendering was never exercised by an automated test.
 *
 * This file is the seed: small, self-contained, exercises [MemoEmptyState] in
 * isolation. Future tests for Settings / Edit / NoteList screens follow the
 * same pattern (createComposeRule + setContent { MemoTheme { ... } } + node
 * assertions).
 */
class MemoEmptyStateUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun renders_title_only() {
        compose.setContent {
            MemoTheme {
                MemoEmptyState(
                    icon = Icons.Outlined.EventAvailable,
                    title = "还没有笔记",
                )
            }
        }

        compose.onNodeWithText("还没有笔记").assertIsDisplayed()
    }

    @Test
    fun renders_title_and_subtitle() {
        compose.setContent {
            MemoTheme {
                MemoEmptyState(
                    icon = Icons.Outlined.EventAvailable,
                    title = "今日无事件",
                    subtitle = "点右下角添加新事件",
                )
            }
        }

        compose.onNodeWithText("今日无事件").assertIsDisplayed()
        compose.onNodeWithText("点右下角添加新事件").assertIsDisplayed()
    }
}
