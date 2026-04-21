package dev.aria.memo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.aria.memo.ui.nav.AppNav
import dev.aria.memo.ui.theme.MemoTheme

/**
 * Launcher host. Bottom-nav shell (笔记 / 日历 / 设置). FLAG_SECURE is applied
 * dynamically by [dev.aria.memo.ui.SettingsScreen] only while the user is
 * viewing the PAT in plaintext — keeping other tabs screen-capture-friendly.
 *
 * P4.1: requests POST_NOTIFICATIONS at first open on Android 13+ so event
 * reminders can actually reach the user. (Fixes review finding S2.)
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user's choice — nothing to do here; quiet fallback handles denial */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
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

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
