package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import kotlin.test.*

class MlxSliceTest {
    @Test fun axesSelectTheRequestedDimensionAndRejectInvalidAxes() {
        val session = MlxMatrixOperations().open()
        try {
            val input = session.tensor(_a[0f, 1f, 2f, 3f, 4f, 5f], s_[2, 3])
            val positive = session.slice(input, 1, 1, 3)
            val negative = session.slice(input, -1, 1, 3)
            assertEquals(2, negative.a[0])
            assertEquals(2, negative.a[1])
            val expected = _a[1f, 2f, 4f, 5f]
            assertContentEquals(expected, FloatArray(4).also { positive.read(0, it) })
            assertContentEquals(expected, FloatArray(4).also { negative.read(0, it) })
            val first = session.slice(input, -2, 1, 2)
            assertEquals(1, first.a[0])
            assertEquals(3, first.a[1])
            assertContentEquals(_a[3f, 4f, 5f], FloatArray(3).also { first.read(0, it) })
            assertFailsWith<IllegalArgumentException> { session.slice(input, 2, 0, 1) }
            assertFailsWith<IllegalArgumentException> { session.slice(input, -3, 0, 1) }
        } finally { session.close() }
    }
}
