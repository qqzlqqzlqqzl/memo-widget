package dev.aria.memo

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aria.memo.ui.nav.AppNav
import dev.aria.memo.ui.theme.MemoTheme

/**
 * Launcher host. Bottom-nav shell (笔记 / 日历 / 设置) plus a route to the
 * canonical [EditActivity]. The PAT is visible on the settings tab, so the
 * window is marked SECURE to keep it out of screenshots / Recents previews.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MemoTheme {
                AppNav(
                    onOpenEditor = {
                        startActivity(Intent(this, EditActivity::class.java))
                    },
                )
            }
        }
    }
}
