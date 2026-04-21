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
import dev.aria.memo.notify.NotificationPermissionBus
import dev.aria.memo.ui.nav.AppNav
import dev.aria.memo.ui.theme.MemoTheme

/**
 * Launcher host. Bottom-nav shell (笔记 / 日历 / 设置). FLAG_SECURE is applied
 * dynamically by [dev.aria.memo.ui.SettingsScreen] only while the user is
 * viewing the PAT in plaintext — keeping other tabs screen-capture-friendly.
 *
 * P4.1: requests POST_NOTIFICATIONS at first open on Android 13+ so event
 * reminders can actually reach the user. (Fixes review finding S2.)
 *
 * Fixes #24: if the user denies (or has previously permanently denied)
 * POST_NOTIFICATIONS we flip [NotificationPermissionBus.denied] so the
 * SettingsScreen can show a guidance card with a deep-link to system settings.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Reflect the runtime choice into the bus so SettingsScreen's warning
        // card appears / disappears without waiting for a resume.
        NotificationPermissionBus.setDenied(!granted)
    }

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

    override fun onResume() {
        super.onResume()
        // Re-read the current permission status on every resume — covers the
        // case where the user bounced to system settings from the warning card
        // and granted (or denied) the permission there.
        syncPermissionBus()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            NotificationPermissionBus.setDenied(false)
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // Seed the "denied" state optimistically before the dialog returns;
            // the launcher callback will flip it back to false on grant.
            NotificationPermissionBus.setDenied(true)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationPermissionBus.setDenied(false)
        }
    }

    private fun syncPermissionBus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            NotificationPermissionBus.setDenied(false)
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        NotificationPermissionBus.setDenied(!granted)
    }
}
