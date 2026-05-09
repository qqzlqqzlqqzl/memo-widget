package dev.aria.memo.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatter for the SettingsScreen sync-status card. Extracted from the
 * composable so it can be unit-tested without ComposeRule.
 *
 * Push and pull timestamps both flow through [formatRelative]:
 *  - 0L (never recorded) → null (caller renders "尚未上传" / "尚未检查")
 *  - <60s → "刚刚"
 *  - <60min → "N 分钟前"
 *  - <24h → "N 小时前"
 *  - ≥24h → absolute "yyyy-MM-dd HH:mm" (Locale.US so the format is stable
 *    across locales — the surrounding labels are Chinese already)
 *
 * Negative skew (`epochMs > nowMs`, e.g. NTP correction) is coerced to 0 so
 * the UI never says "−3 分钟前".
 */
object SyncStatusFormatter {
    fun formatRelative(nowMs: Long, epochMs: Long): String? {
        if (epochMs <= 0L) return null
        val deltaMs = (nowMs - epochMs).coerceAtLeast(0L)
        val minutes = deltaMs / 60_000L
        val hours = minutes / 60L
        return when {
            deltaMs < 60_000L -> "刚刚"
            minutes < 60L -> "${minutes} 分钟前"
            hours < 24L -> "${hours} 小时前"
            else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))
        }
    }
}
