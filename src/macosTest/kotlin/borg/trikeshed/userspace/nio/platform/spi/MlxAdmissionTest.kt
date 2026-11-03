package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import mlxc.*
import platform.posix.size_tVar
import kotlin.test.*

class MlxAdmissionTest {
    @Test fun checkpointBudgetHasAnExplicitPlatformOverride() {
        assertEquals(8L shl 30, MlxMatrixOperations.configured { null }.checkpointLimitBytes)
        assertEquals(Long.MAX_VALUE, MlxMatrixOperations(memoryLimit = 0).checkpointLimitBytes)
        assertEquals(0L, MlxMatrixOperations(memoryLimit = 0, checkpointLimitBytes = 0).checkpointLimitBytes)
        assertEquals(12L shl 30, MlxMatrixOperations.configured { name ->
            if (name == "TRIKESHED_MLX_CHECKPOINT_LIMIT_BYTES") (12L shl 30).toString() else null
        }.checkpointLimitBytes)
        for (invalid in _l["-1", "abc", "9223372036854775808"]) {
            assertFailsWith<IllegalArgumentException> { MlxMatrixOperations.configured { name ->
                if (name == "TRIKESHED_MLX_CHECKPOINT_LIMIT_BYTES") invalid else null
            } }
        }
    }

    @Test fun tokenLimitIsAnExplicitPlatformOverride() {
        assertNull(MlxMatrixOperations.configured { null }.maxTokens)
        assertEquals(4096, MlxMatrixOperations.configured { name ->
            if (name == "TRIKESHED_MLX_MAX_TOKENS") "4096" else null
        }.maxTokens)
        for (invalid in _l["0", "-1", "abc", "2147483648"]) {
            assertFailsWith<IllegalArgumentException> { MlxMatrixOperations.configured { name ->
                if (name == "TRIKESHED_MLX_MAX_TOKENS") invalid else null
            } }
        }
    }

    @Test fun liveSessionsDoNotReplaceProcessWideLimits() = memScoped {
        val first = MlxMatrixOperations().open()
        try {
            val second = MlxMatrixOperations().open()
            try {
                val current = alloc<size_tVar>()
                assertEquals(0, mlx_get_memory_limit(current.ptr))
                val before = current.value
                assertFailsWith<IllegalStateException> {
                    MlxMatrixOperations(memoryLimit = (8L shl 30) - 1).open().close()
                }
                assertEquals(0, mlx_get_memory_limit(current.ptr))
                assertEquals(before, current.value)
            } finally { second.close() }
        } finally { first.close() }
    }

    @Test fun negativeLimitsAreRejectedBeforeChangingMemoryLimit() = memScoped {
        val current = alloc<size_tVar>()
        assertEquals(0, mlx_get_memory_limit(current.ptr))
        val before = current.value
        assertFailsWith<IllegalArgumentException> { MlxMatrixOperations(memoryLimit = -1) }
        assertFailsWith<IllegalArgumentException> { MlxMatrixOperations(memoryLimit = 0, cacheLimit = -1) }
        assertFailsWith<IllegalArgumentException> { MlxMatrixOperations(memoryLimit = 0, wiredLimit = -1) }
        assertFailsWith<IllegalArgumentException> { MlxMatrixOperations(checkpointLimitBytes = -1) }
        assertEquals(0, mlx_get_memory_limit(current.ptr))
        assertEquals(before, current.value)
    }

    @Test fun tokenAdmission() {
        val oversized: Series<Int> = 5 j { error("oversized tokens must not be read") }
        assertFailsWith<IllegalArgumentException> { mlxTokenIds(oversized, 4, 10) }
        assertFailsWith<IllegalArgumentException> { mlxTokenIds(emptySeriesOf(), 4, 10) }
        assertFailsWith<IllegalArgumentException> { mlxTokenIds(s_[0, 10], 4, 10) }
        assertContentEquals(_a[0, 9], mlxTokenIds(s_[0, 9], 4, 10))
    }
}
