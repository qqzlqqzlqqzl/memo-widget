package dev.aria.memo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoResultTest {

    @Test
    fun `Ok preserves value`() {
        val r: MemoResult<Int> = MemoResult.Ok(42)
        assertTrue(r is MemoResult.Ok)
        assertEquals(42, (r as MemoResult.Ok).value)
    }

    @Test
    fun `Err carries code and message`() {
        val err = MemoResult.Err(ErrorCode.UNAUTHORIZED, "bad token")
        assertEquals(ErrorCode.UNAUTHORIZED, err.code)
        assertEquals("bad token", err.message)
    }

    @Test
    fun `Err can be assigned to MemoResult of any type (Nothing variance)`() {
        val err: MemoResult<Unit> = MemoResult.Err(ErrorCode.NETWORK, "io")
        val strErr: MemoResult<String> = MemoResult.Err(ErrorCode.NETWORK, "io")
        assertTrue(err is MemoResult.Err)
        assertTrue(strErr is MemoResult.Err)
    }

    @Test
    fun `ErrorCode covers all documented cases`() {
        val expected = setOf(
            "NOT_CONFIGURED", "UNAUTHORIZED", "NOT_FOUND",
            "CONFLICT", "NETWORK", "UNKNOWN",
        )
        val actual = ErrorCode.values().map { it.name }.toSet()
        assertEquals(expected, actual)
    }
}
