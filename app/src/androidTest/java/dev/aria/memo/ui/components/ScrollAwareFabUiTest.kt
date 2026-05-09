package dev.aria.memo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * Verifies the extended FAB used on NoteList / Calendar. The component
 * relies on the host providing a layout container — in production that's
 * the [Scaffold]'s `floatingActionButton` slot. Tests below use the same
 * Scaffold wrapper so the FAB's enter animation and ripple bounds settle
 * before assertions run; mounting it standalone made
 * `assertIsDisplayed` flake under emulator timing.
 */
class ScrollAwareFabUiTest {

    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Hosted(content: @Composable () -> Unit) {
        MemoTheme {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                floatingActionButton = content,
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    // Empty body — we only care about the FAB slot.
                    @Suppress("UNUSED_EXPRESSION") padding
                }
            }
        }
    }

    @Test
    fun expanded_fab_renders_text() {
        compose.setContent {
            Hosted {
                ScrollAwareFab(
                    expanded = true,
                    onClick = {},
                    icon = Icons.Filled.Add,
                    text = "写一条",
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("写一条").assertIsDisplayed()
    }

    @Test
    fun click_invokes_callback() {
        var clicks = 0
        compose.setContent {
            Hosted {
                ScrollAwareFab(
                    expanded = true,
                    onClick = { clicks++ },
                    icon = Icons.Filled.Add,
                    text = "写一条",
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("写一条").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun collapsed_fab_keeps_a11y_label_via_icon_contentDescription() {
        compose.setContent {
            Hosted {
                ScrollAwareFab(
                    expanded = false,
                    onClick = {},
                    icon = Icons.Filled.Add,
                    text = "加日程",
                )
            }
        }
        compose.waitForIdle()
        // Even when collapsed, TalkBack should still reach the action via
        // the icon's contentDescription (which falls back to `text`).
        compose.onNodeWithContentDescription("加日程").assertIsDisplayed()
    }
}
