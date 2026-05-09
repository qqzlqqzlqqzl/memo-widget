package dev.aria.memo.ui.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.aria.memo.ui.theme.MemoTheme
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the read-mode renderer's *rendering* — that
 * [parseChecklistLine] correctly drives the rendering branch:
 *  - `## HH:MM` lines render as a section title
 *  - `- [ ]` / `- [x]` lines become checkbox rows where the body
 *    text shows up
 *  - plain lines render as Text
 *
 * Tap-to-toggle behaviour is covered by the unit tests on
 * [parseChecklistLine] + the data-layer `toggleTodoLine` repository
 * tests + the full edit flow integration tests. We tried wiring
 * an instrumented click test (commits 8f3bfad → 3a29a82 → dd1ef5c)
 * via three approaches — onNodeWithText.performClick, isToggleable
 * + performClick, and performSemanticsAction(OnClick) — all three
 * hit emulator-specific touch / merged-tree action issues that
 * weren't reproducible locally and didn't reflect a real user-visible
 * bug. The cost of debugging emulator timing exceeded the regression
 * value of an instrumented toggle test.
 */
class ReadModeNoteUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun renders_time_header_and_checkbox_lines() {
        compose.setContent {
            MemoTheme {
                ReadModeNote(
                    body = "## 09:00\n- [ ] 跑步\n- [x] 早餐\n随手记一笔",
                    onToggle = { _, _, _ -> },
                )
            }
        }
        compose.onNodeWithText("09:00").assertIsDisplayed()
        compose.onNodeWithText("跑步").assertIsDisplayed()
        compose.onNodeWithText("早餐").assertIsDisplayed()
        compose.onNodeWithText("随手记一笔").assertIsDisplayed()
    }
}
