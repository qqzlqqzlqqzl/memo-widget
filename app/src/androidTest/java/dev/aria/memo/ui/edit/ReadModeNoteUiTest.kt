package dev.aria.memo.ui.edit

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import dev.aria.memo.ui.theme.MemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the read-mode renderer produces the expected interactive
 * surface for a markdown note: time headers as section titles, checkbox
 * lines as toggleable rows, plain lines as Text.
 *
 * Catches the kind of regression where parseChecklistLine drifts away
 * from the rendering branch (a line that parses as checkbox but renders
 * as plain text, or vice versa, would silently take away the user's
 * ability to tick off todos in read mode).
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

    @Test
    fun tapping_unchecked_box_invokes_onToggle_with_true() {
        var lastIdx = -1
        var lastNew: Boolean? = null
        var lastRaw = ""
        compose.setContent {
            MemoTheme {
                ReadModeNote(
                    body = "- [ ] 写测试",
                    onToggle = { idx, raw, new ->
                        lastIdx = idx
                        lastRaw = raw
                        lastNew = new
                    },
                )
            }
        }
        // ChecklistRow 的 Row 用 mergeDescendants + role=Checkbox 提供
        // 语义合并；点 Text 节点不会传到 Checkbox 的 onCheckedChange。
        // 用 isToggleable() 直接定位 Checkbox 的 toggle action 节点。
        compose.onNode(isToggleable() and hasText("写测试"))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(0, lastIdx)
        assertEquals("- [ ] 写测试", lastRaw)
        assertEquals(true, lastNew)
    }

    @Test
    fun tapping_checked_box_invokes_onToggle_with_false() {
        var lastNew: Boolean? = null
        compose.setContent {
            MemoTheme {
                ReadModeNote(
                    body = "- [x] 完成的事",
                    onToggle = { _, _, new -> lastNew = new },
                )
            }
        }
        compose.onNode(isToggleable() and hasText("完成的事"))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(false, lastNew)
    }
}
