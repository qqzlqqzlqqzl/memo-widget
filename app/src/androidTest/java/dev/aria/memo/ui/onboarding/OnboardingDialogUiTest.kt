package dev.aria.memo.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.aria.memo.ui.theme.MemoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Verifies the 3-slide first-launch onboarding actually advances and that the
 * '稍后' / '去设置' buttons fire the right callback. Catches the kind of UX bug
 * where a user taps "下一步" and nothing happens, or "稍后" silently goes to
 * settings.
 */
class OnboardingDialogUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun advances_through_three_slides_then_settings() {
        var settingsClicks = 0
        var skipClicks = 0
        compose.setContent {
            MemoTheme {
                OnboardingDialog(
                    onGoToSettings = { settingsClicks++ },
                    onSkip = { skipClicks++ },
                )
            }
        }

        // Slide 1: GitHub-as-storage explainer.
        compose.onNodeWithText("你的笔记，存在你的 GitHub").assertIsDisplayed()
        compose.onNodeWithText("下一步").performClick()

        // Slide 2: OAuth / PAT login (post-2026-05-10 reword: "授权" + 扫码 +
        // 一次性密码 — kills the "OAuth Device Flow" / "Personal Access Token"
        // jargon that confused non-dev users).
        compose.onNodeWithText("用 GitHub 账号授权").assertIsDisplayed()
        compose.onNodeWithText("下一步").performClick()

        // Slide 3: settings hand-off — confirmButton flips to '去设置'.
        // Title reworded to "下一步：去「设置」连仓库" (no more bare
        // "owner / repo" jargon).
        compose.onNodeWithText("下一步：去「设置」连仓库").assertIsDisplayed()
        compose.onNodeWithText("去设置").performClick()

        assertEquals(1, settingsClicks)
        assertEquals(0, skipClicks)
    }

    @Test
    fun skip_button_invokes_onSkip_at_any_step() {
        var settingsClicks = 0
        var skipClicks = 0
        compose.setContent {
            MemoTheme {
                OnboardingDialog(
                    onGoToSettings = { settingsClicks++ },
                    onSkip = { skipClicks++ },
                )
            }
        }

        // First slide: tap "稍后" without advancing.
        compose.onNodeWithText("稍后").performClick()

        assertEquals(1, skipClicks)
        assertEquals(0, settingsClicks)
    }
}
