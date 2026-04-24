package dev.aria.memo.ext

import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8 扩展测试（Agent 8，Fix-5 精简版）：SyncStatus sealed hierarchy 业务契约。
 *
 * Red-1 指出：
 *  - 原 `SyncStatusExtEqualsTest` 的 `ErrorCode × ErrorCode = 36` 笛卡尔积 + `msgs × msgs × codes = 96`
 *    共 ~130 case 在测 Kotlin data class 编译器生成的 equals —— 没意义
 *  - 原 `SyncStatusExtErrorTest` 的 ErrorCode × messages 7 × 6 = 42 case × 3 test 同理
 *
 * 精简：删两个 parameterized class，保留 Smoke（25 条） —— 每条测的都是 sealed
 * class 分支或字段访问这种真实 production 里 when-branches 会依赖的行为。
 */
class SyncStatusExtSmokeTest {

    @Test
    fun `Idle is data object`() {
        assertNotNull(SyncStatus.Idle)
    }

    @Test
    fun `Syncing is data object`() {
        assertNotNull(SyncStatus.Syncing)
    }

    @Test
    fun `Ok is data object`() {
        assertNotNull(SyncStatus.Ok)
    }

    @Test
    fun `Error has code`() {
        val e = SyncStatus.Error(ErrorCode.NETWORK, "offline")
        assertEquals(ErrorCode.NETWORK, e.code)
    }

    @Test
    fun `Error has message`() {
        val e = SyncStatus.Error(ErrorCode.NETWORK, "offline")
        assertEquals("offline", e.message)
    }

    @Test
    fun `Error equals clone`() {
        val a = SyncStatus.Error(ErrorCode.UNAUTHORIZED, "401")
        val b = SyncStatus.Error(ErrorCode.UNAUTHORIZED, "401")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Error diff code not equal`() {
        assertNotEquals(
            SyncStatus.Error(ErrorCode.UNAUTHORIZED, "x"),
            SyncStatus.Error(ErrorCode.NETWORK, "x"),
        )
    }

    @Test
    fun `Error diff msg not equal`() {
        assertNotEquals(
            SyncStatus.Error(ErrorCode.UNAUTHORIZED, "a"),
            SyncStatus.Error(ErrorCode.UNAUTHORIZED, "b"),
        )
    }

    @Test
    fun `Idle and Syncing not equal`() {
        assertFalse((SyncStatus.Idle as Any) == (SyncStatus.Syncing as Any))
    }

    @Test
    fun `Idle and Ok not equal`() {
        assertFalse((SyncStatus.Idle as Any) == (SyncStatus.Ok as Any))
    }

    @Test
    fun `Syncing and Ok not equal`() {
        assertFalse((SyncStatus.Syncing as Any) == (SyncStatus.Ok as Any))
    }

    @Test
    fun `Idle is self-referential singleton`() {
        assertTrue(SyncStatus.Idle === SyncStatus.Idle)
    }

    @Test
    fun `Syncing is self-referential singleton`() {
        assertTrue(SyncStatus.Syncing === SyncStatus.Syncing)
    }

    @Test
    fun `Ok is self-referential singleton`() {
        assertTrue(SyncStatus.Ok === SyncStatus.Ok)
    }

    @Test
    fun `Error is not singleton across messages`() {
        val a = SyncStatus.Error(ErrorCode.NETWORK, "a")
        val b = SyncStatus.Error(ErrorCode.NETWORK, "b")
        assertFalse(a === b)
    }

    @Test
    fun `is check for Idle`() {
        val s: SyncStatus = SyncStatus.Idle
        assertTrue(s is SyncStatus.Idle)
        assertFalse(s is SyncStatus.Syncing)
    }

    @Test
    fun `is check for Error`() {
        val s: SyncStatus = SyncStatus.Error(ErrorCode.UNKNOWN, "x")
        assertTrue(s is SyncStatus.Error)
        assertFalse(s is SyncStatus.Idle)
    }

    @Test
    fun `smart cast to Error accesses code`() {
        val s: SyncStatus = SyncStatus.Error(ErrorCode.CONFLICT, "409")
        if (s is SyncStatus.Error) {
            assertEquals(ErrorCode.CONFLICT, s.code)
        }
    }

    @Test
    fun `Error copy preserves code and changes message`() {
        val e = SyncStatus.Error(ErrorCode.NETWORK, "offline")
        val c = e.copy(message = "timeout")
        assertEquals(ErrorCode.NETWORK, c.code)
        assertEquals("timeout", c.message)
    }

    @Test
    fun `Error copy changes code preserves message`() {
        val e = SyncStatus.Error(ErrorCode.NETWORK, "m")
        val c = e.copy(code = ErrorCode.UNAUTHORIZED)
        assertEquals(ErrorCode.UNAUTHORIZED, c.code)
        assertEquals("m", c.message)
    }

    @Test
    fun `Error with empty message allowed`() {
        val e = SyncStatus.Error(ErrorCode.NETWORK, "")
        assertEquals("", e.message)
    }

    @Test
    fun `Error with long message preserved`() {
        val msg = "x".repeat(1000)
        assertEquals(msg, SyncStatus.Error(ErrorCode.UNKNOWN, msg).message)
    }

    @Test
    fun `Idle toString non-null`() {
        assertNotNull(SyncStatus.Idle.toString())
    }

    @Test
    fun `Error toString contains code name`() {
        val e = SyncStatus.Error(ErrorCode.NETWORK, "x")
        assertTrue(e.toString().contains("NETWORK"))
    }

    @Test
    fun `all four SyncStatus subtypes exercisable in when`() {
        // This is the real invariant: production when(status) branches in 4 places.
        // 枚举所有 4 个 subtype，确保每条分支都能 hit。
        val statuses: List<SyncStatus> = listOf(
            SyncStatus.Idle,
            SyncStatus.Syncing,
            SyncStatus.Ok,
            SyncStatus.Error(ErrorCode.UNKNOWN, "x"),
        )
        for (s in statuses) {
            val kind = when (s) {
                is SyncStatus.Idle -> "idle"
                is SyncStatus.Syncing -> "syncing"
                is SyncStatus.Ok -> "ok"
                is SyncStatus.Error -> "error"
            }
            assertNotNull("kind for $s", kind)
        }
    }
}
