package dev.aria.memo.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import dev.aria.memo.ui.theme.MemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Verifies [MemoCard]'s click/long-click contract. The card is the main
 * row component for both notes (NoteListScreen) and events (Calendar
 * sheet); the long-press → "问 AI / 删除" overflow lives on this surface,
 * so a regression here would kill those entry points silently.
 */
class MemoCardUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun renders_content() {
        compose.setContent {
            MemoTheme {
                MemoCard {
                    Text("卡片标题")
                }
            }
        }
        compose.onNodeWithText("卡片标题").assertIsDisplayed()
    }

    @Test
    fun click_invokes_onClick_once() {
        var clicks = 0
        compose.setContent {
            MemoTheme {
                MemoCard(onClick = { clicks++ }) {
                    Text("可点卡片")
                }
            }
        }
        compose.onNodeWithText("可点卡片").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun long_click_invokes_onLongClick() {
        var longClicks = 0
        compose.setContent {
            MemoTheme {
                MemoCard(
                    onClick = {},
                    onLongClick = { longClicks++ },
                ) {
                    Text("长按卡片")
                }
            }
        }
        compose.onNodeWithText("长按卡片").performTouchInput { longClick() }
        assertEquals(1, longClicks)
    }
}
