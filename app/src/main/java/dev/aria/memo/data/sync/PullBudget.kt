package dev.aria.memo.data.sync

import androidx.annotation.VisibleForTesting

/**
 * 本次 pull 周期内的 GitHub API 调用预算。
 * 三段（notes bootstrap、sliding window、events、single notes）共享同一预算，
 * 避免 P6.1 之前"每段各 50 次，合计 160+"的 rate-limit 爆表风险。
 * HANDOFF.md P6.1 第 6 项。
 *
 * ⚠️ **非线程安全** —— 构造于 [PullWorker.doWork] 主线程，三段顺序消费，不会并发
 * 访问。未来若有人从 Flow/coroutine 并发 `consume()`，`used` 的读-改-写不原子
 * 会使计数丢失。要让它支持并发，换成 [java.util.concurrent.atomic.AtomicInteger]
 * 并用 `getAndIncrement` 做原子 CAS。
 *
 * Fixes #58 (P6.1): 加了显式 warning 文案。
 */
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
class PullBudget(private val cap: Int = DEFAULT_CAP) {
    private var used = 0

    val remaining: Int get() = (cap - used).coerceAtLeast(0)

    fun consume(): Boolean {
        if (used >= cap) return false
        used += 1
        return true
    }

    fun exhausted(): Boolean = used >= cap

    companion object {
        /**
         * 默认 150 = 未登录 PAT 用户 60/h 以下有安全余量，登录 PAT 用户 5000/h
         * 绰绰有余。选 150 的理由：预期最坏 notes bootstrap 50 + window 14 +
         * events 50 + single notes 50 = 164 在旧逻辑下可触发，砍到 150 提供护栏。
         */
        const val DEFAULT_CAP = 150
    }
}
