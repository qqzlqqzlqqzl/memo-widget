package dev.aria.memo.notify

/**
 * Shared utilities for the notify package.
 */

/**
 * Map a UID to a stable, well-dispersed positive Int for [android.app.PendingIntent]
 * requestCode.
 *
 * FNV-1a 32-bit over the UTF-8 bytes disperses much more uniformly across the
 * full 31-bit positive range than JDK [String.hashCode], which clusters
 * UUID-shaped strings in the low 16 bits.
 *
 * Used by both [AlarmScheduler] (alarm PendingIntents) and [EventAlarmReceiver]
 * (tap PendingIntents) so that cancel/update operations in AlarmScheduler always
 * address the exact PendingIntent that EventAlarmReceiver would create with the
 * same UID.
 */
internal fun stableRequestCode(uid: String): Int {
    val bytes = uid.toByteArray(Charsets.UTF_8)
    // FNV-1a 32-bit: offset basis 0x811c9dc5, prime 0x01000193.
    var h = 0x811c9dc5.toInt()
    for (b in bytes) {
        h = h xor (b.toInt() and 0xff)
        h = (h * 0x01000193).toInt()
    }
    return h and 0x7fffffff // strip sign bit so the Int is always positive
}
