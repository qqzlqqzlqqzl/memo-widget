package dev.aria.memo.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.aria.memo.ui.theme.MemoTheme
import org.junit.Rule
import org.junit.Test

/**
 * MemoSectionHeader is the section label used across NoteList / Calendar /
 * Tags. The `trailing` slot is what shows the per-section count chip — a
 * regression there would silently make "3 条" disappear without breaking
 * anything else, so worth pinning.
 */
class MemoSectionHeaderUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun renders_label_only() {
        compose.setContent {
            MemoTheme {
                MemoSectionHeader(text = "日程")
            }
        }
        compose.onNodeWithText("日程").assertIsDisplayed()
    }

    @Test
    fun trailing_slot_renders_alongside_label() {
        compose.setContent {
            MemoTheme {
                MemoSectionHeader(
                    text = "备忘",
                    trailing = { Text("3 条") },
                )
            }
        }
        compose.onNodeWithText("备忘").assertIsDisplayed()
        compose.onNodeWithText("3 条").assertIsDisplayed()
    }
}
