package dev.aria.memo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.aria.memo.ui.SettingsScreen
import dev.aria.memo.ui.SettingsViewModel
import dev.aria.memo.ui.theme.MemoTheme

/**
 * Settings host: 4 fields (PAT / Owner / Repo / Branch) + save. Also exposes
 * a "立即写一条" shortcut that launches [EditActivity] so users can sanity-test
 * their config without going through the home-screen widget.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels { SettingsViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MemoTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenEditor = {
                        startActivity(Intent(this, EditActivity::class.java))
                    },
                )
            }
        }
    }
}
