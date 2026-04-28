package dev.aria.memo.widget

/**
 * Stable Glance LazyColumn `itemId` synthesis.
 *
 * Fixes #319 (Perf-1 M4): both [MemoWidgetContent] and [TodayWidgetContent]
 * built their itemIds out of `String.hashCode().toLong()` plus a few
 * date/time additions. JDK's String.hashCode is 32-bit and tuned for
 * HashMap dispersion, not full-range collision resistance — and Glance's
 * itemId space is 64-bit, so we were throwing away half the addressable
 * range. Two different note bodies (or two different uids) hashing to the
 * same Int + falling on the same minute would have produced the same
 * itemId, and Glance would have recycled one row's view for another's
 * data — visible as flickering text on the home-screen widget.
 *
 * FNV-1a 64-bit over the UTF-8 bytes gives roughly 2^32 keys before a
 * 50% collision probability, which is enough that no realistic widget
 * row count will ever collide. Mixing a single-byte type tag prevents
 * cross-source collisions (a memo whose key happens to FNV-hash to the
 * same value as an event uid).
 */
internal fun fnv1a64(input: String): Long {
    val bytes = input.toByteArray(Charsets.UTF_8)
    var h = -3750763034362895579L // FNV offset basis 14695981039346656037
    val prime = 1099511628211L
    for (b in bytes) {
        h = h xor (b.toInt() and 0xff).toLong()
        h *= prime
    }
    return h
}

/**
 * Build a stable Long item id from a (type-tag, key-string) pair. The
 * tag is a single ASCII byte that disambiguates row sources, and the
 * key is whatever stable identifier that row exposes (uid, "date|time|
 * label", …). Result is the FNV-1a 64-bit hash of the joined string.
 */
internal fun stableItemId(tag: Char, key: String): Long =
    fnv1a64("$tag|$key")
