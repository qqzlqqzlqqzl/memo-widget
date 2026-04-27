package dev.aria.memo.data.sync

import android.content.Context
import dev.aria.memo.data.AppConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure JVM tests for [ConfigChangeListener] (Fix-D6 / Review-W 7).
 *
 * Coverage:
 *  1. false to true triggers exactly one push.
 *  2. Staying false never triggers.
 *  3. Staying true after the first trigger does not re-trigger.
 *  4. true to false does not trigger (clearing PAT must not re-push).
 *  5. Multiple flips: each false-to-true edge gets its own push.
 *  6. Initial emit when already configured counts as one flip (intentional —
 *     SyncScheduler.PUSH_POLICY = KEEP makes the redundant enqueue a no-op).
 *  7. Non-isConfigured field changes (branch swap, PAT rotation while still
 *     configured) do not retrigger.
 *  8. Finite flow shape: false then true completes cleanly with one trigger.
 *
 * Strategy:
 *  - We do not build a real [dev.aria.memo.data.SettingsStore] (final class,
 *    Context-bound) — go through [ConfigChangeListener.createForTest] with a
 *    plain [Flow] and a lambda push trigger.
 *  - We do not call the real [SyncScheduler.enqueuePush] (would need
 *    WorkManager + Robolectric); a counter lambda stands in.
 *  - [UnconfinedTestDispatcher] inside [runTest] makes `launch { listener.start() }`
 *    eagerly subscribe. Each `configs.value = ...` immediately routes to the
 *    collector. `runCurrent()` is defensive in case any continuation queues.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigChangeListenerTest {

    private val unconfigured = AppConfig(pat = "", owner = "", repo = "")
    private val configured = AppConfig(pat = "ghp_x", owner = "alice", repo = "memo")
    private val configuredAlt = AppConfig(pat = "ghp_y", owner = "alice", repo = "memo")
    private val configuredOnAnotherBranch = configured.copy(branch = "develop")

    private fun listener(
        flow: kotlinx.coroutines.flow.Flow<AppConfig>,
        counter: AtomicInteger,
    ): ConfigChangeListener =
        ConfigChangeListener.createForTest(
            configFlow = flow,
            pushTrigger = { counter.incrementAndGet() },
            context = null as Context?,
        )

    @Test
    fun `false to true triggers exactly one push`() = runTest(UnconfinedTestDispatcher()) {
        val configs = MutableStateFlow(unconfigured)
        val pushes = AtomicInteger(0)

        val job = launch { listener(configs, pushes).start() }
        runCurrent()
        assertEquals(0, pushes.get())

        configs.value = configured
        runCurrent()
        assertEquals("flip should fire push exactly once", 1, pushes.get())

        job.cancel()
    }

    @Test
    fun `staying false never triggers push`() = runTest(UnconfinedTestDispatcher()) {
        val configs = MutableStateFlow(unconfigured)
        val pushes = AtomicInteger(0)

        val job = launch { listener(configs, pushes).start() }
        runCurrent()

        configs.value = unconfigured.copy(branch = "develop")
        runCurrent()
        configs.value = unconfigured.copy(owner = "alice") // pat/repo still empty
        runCurrent()
        assertEquals(0, pushes.get())

        job.cancel()
    }

    @Test
    fun `staying true after first trigger does not re-trigger`() =
        runTest(UnconfinedTestDispatcher()) {
            val configs = MutableStateFlow(unconfigured)
            val pushes = AtomicInteger(0)

            val job = launch { listener(configs, pushes).start() }
            runCurrent()

            configs.value = configured
            runCurrent()
            assertEquals(1, pushes.get())

            // Rotate PAT while still configured — no flip.
            configs.value = configuredAlt
            runCurrent()
            assertEquals("still configured, no re-trigger", 1, pushes.get())

            // Branch change (still configured) — no flip.
            configs.value = configuredOnAnotherBranch
            runCurrent()
            assertEquals(1, pushes.get())

            job.cancel()
        }

    @Test
    fun `true to false does not trigger push`() = runTest(UnconfinedTestDispatcher()) {
        val configs = MutableStateFlow(unconfigured)
        val pushes = AtomicInteger(0)

        val job = launch { listener(configs, pushes).start() }
        runCurrent()

        configs.value = configured
        runCurrent()
        assertEquals(1, pushes.get())

        configs.value = unconfigured
        runCurrent()
        assertEquals("clearing PAT must not re-push", 1, pushes.get())

        job.cancel()
    }

    @Test
    fun `multiple flips trigger push once per false-to-true transition`() =
        runTest(UnconfinedTestDispatcher()) {
            val configs = MutableStateFlow(unconfigured)
            val pushes = AtomicInteger(0)

            val job = launch { listener(configs, pushes).start() }
            runCurrent()

            // Round 1: false -> true.
            configs.value = configured
            runCurrent()
            assertEquals(1, pushes.get())

            // -> false (user wiped PAT).
            configs.value = unconfigured
            runCurrent()
            assertEquals(1, pushes.get())

            // Round 2: false -> true (user re-filled).
            configs.value = configured
            runCurrent()
            assertEquals("each false-to-true edge fires independently", 2, pushes.get())

            job.cancel()
        }

    @Test
    fun `initial emit already configured fires once`() = runTest(UnconfinedTestDispatcher()) {
        // prevConfigured starts false; first emit with isConfigured=true counts
        // as a flip. Documented intentional behavior — SyncScheduler.PUSH_POLICY
        // = KEEP collapses the redundant enqueue against MemoApplication.onCreate's
        // unconditional one.
        val configs = MutableStateFlow(configured)
        val pushes = AtomicInteger(0)

        val job = launch { listener(configs, pushes).start() }
        runCurrent()

        assertEquals("initial-already-configured counts as one flip", 1, pushes.get())

        job.cancel()
    }

    @Test
    fun `finite flow false then true completes with one trigger`() =
        runTest(UnconfinedTestDispatcher()) {
            // Finite flow collects, completes, returns from start() — no manual cancel.
            val configs = flowOf(unconfigured, configured)
            val pushes = AtomicInteger(0)

            listener(configs, pushes).start()

            assertEquals(1, pushes.get())
        }

    @Test
    fun `pushTrigger is invoked exactly once per false-to-true edge across many emits`() =
        runTest(UnconfinedTestDispatcher()) {
            val configs = MutableStateFlow(unconfigured)
            val invocations = mutableListOf<Unit>()

            val l = ConfigChangeListener.createForTest(
                configFlow = configs,
                pushTrigger = { invocations.add(Unit) },
                context = null,
            )
            val job = launch { l.start() }
            runCurrent()

            // emit #1: still unconfigured (branch tweak only)
            configs.value = unconfigured.copy(branch = "develop")
            runCurrent()
            // emit #2: still unconfigured (owner only — pat/repo still empty)
            configs.value = unconfigured.copy(owner = "alice")
            runCurrent()
            // emit #3: flip — pat/owner/repo all set
            configs.value = configured
            runCurrent()
            // emit #4: still configured, branch change only
            configs.value = configuredOnAnotherBranch
            runCurrent()
            // emit #5: still configured, PAT rotated
            configs.value = configuredAlt
            runCurrent()

            assertEquals("only the single flip across 5 emits should push", 1, invocations.size)

            job.cancel()
        }
}
