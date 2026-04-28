package dev.aria.memo.data.sync

import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本次 pull 周期内的 GitHub API 调用预算。
 * 三段（notes bootstrap、sliding window、events、single notes）共享同一预算，
 * 避免 P6.1 之前"每段各 50 次，合计 160+"的 rate-limit 爆表风险。
 * HANDOFF.md P6.1 第 6 项。
 *
 * Fixes #314 (Perf-1 M3): switched [used] from a plain Int to
 * [AtomicInteger] so any future caller that runs the three pull stages
 * concurrently on a `Dispatchers.IO` parallel coroutine doesn't lose
 * decrements via a non-atomic read-modify-write. Also added
 * [tightenFromHeader] so callers that observe `X-RateLimit-Remaining`
 * on a real GitHub response can ratchet the cap downward mid-cycle —
 * unauthenticated users with 60/h leave essentially no headroom and
 * the static 150 default would cheerfully exhaust the quota in one
 * pull cycle.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
class PullBudget(cap: Int = DEFAULT_CAP) {
    /**
     * Mutable cap — see [tightenFromHeader]. Atomic so a concurrent reader
     * sees the same value the writer just installed (otherwise [consume]
     * could spin past a cap that *should* be lower).
     */
    private val capRef = AtomicInteger(cap)

    private val used = AtomicInteger(0)

    val remaining: Int
        get() = (capRef.get() - used.get()).coerceAtLeast(0)

    /**
     * Atomically reserve a slot. Returns true when the call is allowed,
     * false when the cap is already exhausted. Implemented as a CAS loop
     * so concurrent calls can't both read N, both write N+1, and end up
     * with one slot consumed but two API calls in flight.
     */
    fun consume(): Boolean {
        while (true) {
            val current = used.get()
            if (current >= capRef.get()) return false
            if (used.compareAndSet(current, current + 1)) return true
        }
    }

    fun exhausted(): Boolean = used.get() >= capRef.get()

    /**
     * Lower the cap to whatever GitHub last advertised. Reads the
     * `X-RateLimit-Remaining` response header; if it's lower than our
     * current effective cap (cap - used), shrinks the cap so the next
     * [consume] mirrors GitHub's view. Never raises the cap — this is
     * purely a backstop against an unauthenticated 60/h client trying
     * to use the full 150 default.
     *
     * Atomic: CAS loop on [capRef] so a concurrent caller reducing the
     * cap further can't be lost.
     */
    fun tightenFromHeader(remainingHeader: String?) {
        val advertised = remainingHeader?.toIntOrNull() ?: return
        if (advertised < 0) return
        val targetCap = used.get() + advertised
        while (true) {
            val current = capRef.get()
            if (targetCap >= current) return
            if (capRef.compareAndSet(current, targetCap)) return
        }
    }

    companion object {
        /**
         * 默认 150 = 未登录 PAT 用户 60/h 以下有安全余量，登录 PAT 用户 5000/h
         * 绰绰有余。选 150 的理由：预期最坏 notes bootstrap 50 + window 14 +
         * events 50 + single notes 50 = 164 在旧逻辑下可触发，砍到 150 提供护栏。
         *
         * 实际生产路径会通过 [tightenFromHeader] 进一步收紧到 GitHub 当下的余量。
         */
        const val DEFAULT_CAP = 150
    }
}
