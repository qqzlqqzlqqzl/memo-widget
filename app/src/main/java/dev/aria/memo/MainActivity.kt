package dev.aria.memo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aria.memo.ui.nav.AppNav
import dev.aria.memo.ui.theme.MemoTheme

/**
 * Launcher host. Bottom-nav shell (笔记 / 日历 / 设置). FLAG_SECURE is applied
 * dynamically by [dev.aria.memo.ui.SettingsScreen] only while the user is
 * viewing the PAT in plaintext — keeping other tabs screen-capture-friendly.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
