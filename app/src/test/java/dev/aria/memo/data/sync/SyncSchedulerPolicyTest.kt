package dev.aria.memo.data.sync

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Fix-WP (Review-Q) — pure-JVM regression guard for [SyncScheduler.enqueuePush]'s
 * unique-work policy.
 *
 * **Why this exists:**
 *
 * The first version of `enqueuePush` shipped with [ExistingWorkPolicy.APPEND_OR_REPLACE],
 * which serialised every retry-then-save burst onto a single linear chain. A
 * single failing push (transient NETWORK held by WorkManager's exponential
 * backoff) blocked **all subsequent saves** from running until the head of
 * the chain finished its 6-hour retry curve — visually indistinguishable
 * from data loss.
 *
 * The fix flipped to [ExistingWorkPolicy.KEEP] because [PushWorker.doWork]
 * already scans every `dirty = true` row across notes / events / single-notes
 * when it runs. A second `enqueuePush` while one is in flight is therefore
 * redundant — the running worker picks up whatever new dirty rows landed
 * before it queries `pending()`. KEEP collapses the redundant enqueue into
 * a no-op instead of stretching the queue.
 *
 * **Why a unit test (not an instrumented WorkManager test):**
 *
 * - `WorkManager.getInstance(ctx)` requires a live `WorkManagerInitializer`,
 *   which means Robolectric + `androidx.work:work-testing` deps. Those deps
 *   are NOT yet on the test classpath in this module — they're being added
 *   under Fix-W1 (see [dev.aria.memo.widget.WidgetHookIntegrationTest] KDoc).
 * - All this test really wants to assert is "the policy SyncScheduler hands
 *   to WorkManager is KEEP, not APPEND_OR_REPLACE / REPLACE". That's a pure
 *   constant; we expose it via [SyncScheduler.PUSH_POLICY] (an `internal val`
 *   reachable from same-module tests) and assert the value directly.
 * - This decouples the regression guard from any future WorkManager test
 *   infra work — the test runs in milliseconds with zero Android runtime.
 *
 * **What it locks in:**
 *
 *  1. The policy is exactly [ExistingWorkPolicy.KEEP].
 *  2. It's NOT the old [ExistingWorkPolicy.APPEND_OR_REPLACE] — the regression.
 *  3. It's NOT [ExistingWorkPolicy.REPLACE] either — REPLACE would cancel an
 *     in-flight push, abandoning whatever HTTP work was in progress and
 *     forcing PushWorker to re-enter from scratch. That's strictly worse than
 *     KEEP for the "two saves landed back-to-back" scenario this fix targets.
 */
class SyncSchedulerPolicyTest {

    @Test
    fun `enqueuePush uses KEEP policy`() {
        // The constant is the seam: SyncScheduler.enqueuePush passes
        // PUSH_POLICY straight into WorkManager.enqueueUniqueWork, so
        // asserting on it is functionally equivalent to inspecting the
        // WorkManager request.
        assertEquals(
            "enqueuePush MUST use KEEP — APPEND_OR_REPLACE serialises retries " +
                "and stalls subsequent saves; REPLACE cancels in-flight pushes.",
            ExistingWorkPolicy.KEEP,
            SyncScheduler.PUSH_POLICY,
        )
    }

    @Test
    fun `enqueuePush policy is not the old APPEND_OR_REPLACE regression`() {
        // Explicit guard so that if someone "fixes" KEEP back to APPEND_OR_REPLACE
        // thinking it preserves more saves, this test fails with a message that
        // names the original bug. The other test would also fail, but this one
        // makes the intent of the guard obvious in CI output.
        assertNotEquals(
            "Reverted to APPEND_OR_REPLACE? That's the Fix-WP regression — " +
                "see SyncScheduler.PUSH_POLICY KDoc for why this can't come back.",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            SyncScheduler.PUSH_POLICY,
        )
    }

    @Test
    fun `enqueuePush policy is not REPLACE either`() {
        // REPLACE = cancel the running PushWorker and start a new one. This
        // sounds appealing ("always run with the freshest dirty set!") but
        // the running worker has already issued in-flight HTTP PUTs to
        // GitHub — cancelling it mid-flight either succeeds (no harm done)
        // or leaves us in an undefined state where the local Room thinks the
        // row is still dirty but GitHub already accepted the PUT. KEEP +
        // PushWorker's "re-scan dirty on entry" gives us the same convergence
        // without the cancel-mid-PUT risk.
        assertNotEquals(
            "REPLACE cancels the in-flight PushWorker — it can interrupt a " +
                "PUT mid-HTTP and leave Room out of sync with GitHub. KEEP is " +
                "the right policy because PushWorker.pending() re-scans dirty " +
                "rows on entry; redundant enqueues are no-ops by design.",
            ExistingWorkPolicy.REPLACE,
            SyncScheduler.PUSH_POLICY,
        )
    }
}
