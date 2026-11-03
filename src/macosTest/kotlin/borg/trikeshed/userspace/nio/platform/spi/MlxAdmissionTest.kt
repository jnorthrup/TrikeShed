package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import mlxc.*
import platform.posix.size_tVar
import kotlin.test.*

class MlxAdmissionTest {
    @Test fun negativeLimitsAreRejectedBeforeChangingMemoryLimit() = memScoped {
        val current = alloc<size_tVar>()
        assertEquals(0, mlx_get_memory_limit(current.ptr))
        val before = current.value
        assertFailsWith<IllegalArgumentException> { MlxMatrixOperations(memoryLimit = -1) }
        assertFailsWith<IllegalArgumentException> { MlxMatrixOperations(memoryLimit = 0, cacheLimit = -1) }
        assertFailsWith<IllegalArgumentException> { MlxMatrixOperations(memoryLimit = 0, wiredLimit = -1) }
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
