package dev.aria.memo.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixes #6: serialise writes against a single GitHub path. `appendToday` and
 * `PushWorker` can both push the same note at the same time; each reads a SHA,
 * PUTs, and one of them loses with HTTP 409. Holding a per-path mutex around
 * "read SHA + PUT" eliminates the race entirely for the single-process case
 * (both writers live in the app process by default).
 *
 * The mutex map is intentionally never pruned — the overhead of a few dozen
 * long-lived `Mutex` objects is negligible compared with the correctness win,
 * and pruning would need another lock.
 */
object PathLocker {

    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(path: String): Mutex = mutexes.getOrPut(path) { Mutex() }

    suspend fun <T> withLock(path: String, block: suspend () -> T): T =
        lockFor(path).withLock { block() }
}
