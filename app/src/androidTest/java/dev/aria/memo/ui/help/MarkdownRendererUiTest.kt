package dev.aria.memo.ui.help

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.aria.memo.ui.theme.MemoTheme
import org.junit.Rule
import org.junit.Test

/**
 * Render tests for the markdown pipeline that powers [HelpScreen]. The user
 * guide ships as a Markdown asset (~20 KB) bundled with the app; if any
 * block type silently drops, the user gets blanks where the guide used to
 * say something. Verify a representative slice — h1 / h2 / paragraph /
 * bold / list / code — actually reaches the screen.
 */
class MarkdownRendererUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun renders_headings_paragraph_and_list() {
        compose.setContent {
            MemoTheme {
                RenderMarkdown(
                    source = """
                        # 大标题
                        ## 二级标题
                        这是正文段落。
                        - 列表项 A
                        - 列表项 B
                    """.trimIndent(),
                )
            }
        }
        compose.onNodeWithText("大标题").assertIsDisplayed()
        compose.onNodeWithText("二级标题").assertIsDisplayed()
        compose.onNodeWithText("这是正文段落。").assertIsDisplayed()
        // List items render with a leading "•" bullet; substring match is
        // robust against the bullet glyph variation.
        compose.onNodeWithText("列表项 A", substring = true).assertIsDisplayed()
        compose.onNodeWithText("列表项 B", substring = true).assertIsDisplayed()
    }

    @Test
    fun renders_inline_bold_inside_paragraph() {
        compose.setContent {
            MemoTheme {
                RenderMarkdown(source = "**重要**：先配置 PAT。")
            }
        }
        // The whole paragraph is one Text node; substring lets us confirm
        // both halves are present even though Compose merges them into one
        // AnnotatedString.
        compose.onNodeWithText("重要", substring = true).assertIsDisplayed()
        compose.onNodeWithText("先配置 PAT", substring = true).assertIsDisplayed()
    }

    @Test
    fun renders_fenced_code_block() {
        compose.setContent {
            MemoTheme {
                RenderMarkdown(
                    source = """
                        正文之前。
                        ```
                        gh pr merge 123 --squash
                        ```
                        正文之后。
                    """.trimIndent(),
                )
            }
        }
        compose.onNodeWithText("正文之前。").assertIsDisplayed()
        compose.onNodeWithText("gh pr merge 123 --squash").assertIsDisplayed()
        compose.onNodeWithText("正文之后。").assertIsDisplayed()
    }
}
