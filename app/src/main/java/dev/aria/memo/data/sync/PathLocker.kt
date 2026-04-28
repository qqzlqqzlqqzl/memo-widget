package dev.aria.memo.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixes #6: serialise writes against a single GitHub path. `appendToday` and
 * `PushWorker` can both push the same note at the same time; each reads a SHA,
 * PUTs, and one of them loses with HTTP 409. Holding a per-path mutex around
 * "read SHA + PUT" eliminates the race entirely for the single-process case.
 *
 * **Bug-1 M13 fix (#134): mutex map size 上限保护**.
 * 每条新 path 留一个 long-lived Mutex,长跑 process 会让 map 单调增长。极端用户
 * (每天新 single-note 一条 × 数月) 累积上千 Mutex,潜在内存压力。
 *
 * 修法:Mutex 用 ref-count + 引用归零时从 map 移除。withLock 入口 acquire,
 * exit (finally) release;count 归 0 时 atomic remove key。下次同 path 再来
 * 重建 Mutex (这是干净状态,因为 ref 归零意味着没人在等)。
 */
object PathLocker {

    private data class RefCounted(val mutex: Mutex, val refCount: AtomicInteger)

    private val mutexes = ConcurrentHashMap<String, RefCounted>()

    suspend fun <T> withLock(path: String, block: suspend () -> T): T {
        val entry = mutexes.compute(path) { _, existing ->
            existing?.also { it.refCount.incrementAndGet() }
                ?: RefCounted(Mutex(), AtomicInteger(1))
        }!!
        try {
            return entry.mutex.withLock { block() }
        } finally {
            // ref 归 0 → 此 path 暂时没人持有/排队,清掉防内存累积。
            // 用 compute 确保 release race 不会让我们删掉别人新 acquire 的 Mutex。
            mutexes.compute(path) { _, current ->
                if (current == null || current.refCount.decrementAndGet() <= 0) null else current
            }
        }
    }

    /** Test helper. */
    internal fun mapSizeForTest(): Int = mutexes.size
}
