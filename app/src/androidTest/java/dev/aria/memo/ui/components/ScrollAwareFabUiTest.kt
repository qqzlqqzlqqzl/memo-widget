package dev.aria.memo.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.aria.memo.ui.theme.MemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the extended FAB used on NoteList / Calendar. Two things matter
 * for end users: the click reaches the host (not consumed by the
 * expand/collapse animation), and the text label is reachable for TalkBack
 * even when collapsed (icon contentDescription falls back to the text).
 */
class ScrollAwareFabUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun expanded_fab_renders_text() {
        compose.setContent {
            MemoTheme {
                ScrollAwareFab(
                    expanded = true,
                    onClick = {},
                    icon = Icons.Filled.Add,
                    text = "写一条",
                )
            }
        }
        compose.onNodeWithText("写一条").assertIsDisplayed()
    }

    @Test
    fun click_invokes_callback() {
        var clicks = 0
        compose.setContent {
            MemoTheme {
                ScrollAwareFab(
                    expanded = true,
                    onClick = { clicks++ },
                    icon = Icons.Filled.Add,
                    text = "写一条",
                )
            }
        }
        compose.onNodeWithText("写一条").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun collapsed_fab_keeps_a11y_label_via_icon_contentDescription() {
        compose.setContent {
            MemoTheme {
                ScrollAwareFab(
                    expanded = false,
                    onClick = {},
                    icon = Icons.Filled.Add,
                    text = "加日程",
                )
            }
        }
        // Even when collapsed, TalkBack should still reach the action via
        // the icon's contentDescription (which falls back to `text`).
        compose.onNodeWithContentDescription("加日程").assertIsDisplayed()
    }
}
