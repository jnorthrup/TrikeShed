<<<<<<< HEAD
package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import kotlin.test.*

class MlxTensorOwnershipTest {
    @Test fun foreignTensorsAreRejectedBeforeAllocationOrDispatch() {
        val first = MlxMatrixOperations().open()
        val second = MlxMatrixOperations().open()
        try {
            val foreign = first.tensor(_a[1f, 2f], s_[1, 2])
            val local = second.tensor(_a[3f, 4f], s_[1, 2])
            fun reject(block: () -> Unit) {
                val mark = second.allocationCount
                val error = assertFailsWith<IllegalArgumentException> { block() }
                assertContains(error.message.orEmpty(), "owned")
                assertEquals(mark, second.allocationCount, "a rejected operation must not allocate")
            }
            reject { second.negate(foreign) }
            reject { second.add(foreign, local) }
            reject { second.add(local, foreign) }
            reject { second.times(local, foreign) }
            reject { second.scale(foreign, 2f) }
            reject { second.sum(foreign, 0) }
            reject { second.repeat(foreign, 1, 0) }
            reject { second.slice(foreign, 0, 0, 1) }
            reject { second.concatenate(_l[local, foreign], 0) }
            reject { second.reshape(foreign, 2) }
            reject { second.swapAxes(foreign, 0, 1) }
            reject { second.split(foreign, 1, 0) }
            reject { second.rope(foreign, 2, 10_000f) }
            reject { second.dense(local, foreign) }
            reject { second.conv1d(local, foreign, 1) }
            reject { second.rmsNorm(local, foreign, 1e-5f) }
            reject { second.attention(local, foreign, local, 1f) }
            reject { second.quantizedWidth(local, foreign, local, 32, 4) }
            reject { second.quantizedLinear(foreign, local, local, local, 32, 4) }
            reject { second.quantizedLinear(local, local, local, foreign, 32, 4) }
            reject { second.embed(_a[0], local, local, foreign, 32, 4) }
            reject { second.softplus(foreign) }
            val localMatrix = MlxQuantized(local, local, local, 32, 4)
            val foreignMatrix = MlxQuantized(local, local, foreign, 32, 4)
            reject { second.fullAttention(local, localMatrix, localMatrix, localMatrix, foreignMatrix,
                local, local, 1, 1, 2, 2, 10_000f, 1e-5f) }
            reject { second.linearAttention(local, localMatrix, localMatrix, local, local, localMatrix,
                local, local, local, foreign, 1, 1, 2, 2, 1, 1e-5f) }
            reject { second.settle(second.mark(), local, foreign) }
            var checks = 0
            reject { second.materialize(_l[local, foreign]) { checks++ } }
            assertEquals(2, checks, "materialize must poll while validating its inputs")
            val valid = second.negate(local)
            assertContentEquals(_a[-3f, -4f], FloatArray(2).also { valid.read(0, it) })
        } finally { second.close(); first.close() }
    }

    @Test fun releasedTensorIsRejectedBeforeReuse() {
        val session = MlxMatrixOperations().open()
        try {
            val released = session.tensor(_a[1f], s_[1])
            session.release(released)
            val mark = session.allocationCount
            assertFailsWith<IllegalStateException> { session.negate(released) }
            assertEquals(mark, session.allocationCount)
        } finally { session.close() }
    }
}
||||||| 777887620
=======
package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import kotlin.test.*

class MlxTensorOwnershipTest {
    @Test fun foreignTensorsAreRejectedBeforeAllocationOrDispatch() {
        val first = MlxMatrixOperations().open()
        val second = MlxMatrixOperations().open()
        try {
            val foreign = first.tensor(_a[1f, 2f], s_[1, 2])
            val local = second.tensor(_a[3f, 4f], s_[1, 2])
            fun reject(block: () -> Unit) {
                val mark = second.mark()
                val error = assertFailsWith<IllegalArgumentException> { block() }
                assertContains(error.message.orEmpty(), "owned")
                assertEquals(mark, second.mark(), "a rejected operation must not allocate")
            }
            reject { second.negate(foreign) }
            reject { second.add(foreign, local) }
            reject { second.add(local, foreign) }
            reject { second.times(local, foreign) }
            reject { second.scale(foreign, 2f) }
            reject { second.sum(foreign, 0) }
            reject { second.repeat(foreign, 1, 0) }
            reject { second.slice(foreign, 0, 0, 1) }
            reject { second.concatenate(_l[local, foreign], 0) }
            reject { second.reshape(foreign, 2) }
            reject { second.swapAxes(foreign, 0, 1) }
            reject { second.split(foreign, 1, 0) }
            reject { second.rope(foreign, 2, 10_000f) }
            reject { second.dense(local, foreign) }
            reject { second.conv1d(local, foreign, 1) }
            reject { second.rmsNorm(local, foreign, 1e-5f) }
            reject { second.attention(local, foreign, local, 1f) }
            reject { second.quantizedWidth(local, foreign, local, 32, 4) }
            reject { second.quantizedLinear(foreign, local, local, local, 32, 4) }
            reject { second.quantizedLinear(local, local, local, foreign, 32, 4) }
            reject { second.embed(_a[0], local, local, foreign, 32, 4) }
            reject { second.softplus(foreign) }
            val localMatrix = MlxQuantized(local, local, local, 32, 4)
            val foreignMatrix = MlxQuantized(local, local, foreign, 32, 4)
            reject { second.fullAttention(local, localMatrix, localMatrix, localMatrix, foreignMatrix,
                local, local, 1, 1, 2, 2, 10_000f, 1e-5f) }
            reject { second.linearAttention(local, localMatrix, localMatrix, local, local, localMatrix,
                local, local, local, foreign, 1, 1, 2, 2, 1, 1e-5f) }
            reject { second.settle(second.mark(), local, foreign) }
            var checks = 0
            reject { second.materialize(_l[local, foreign]) { checks++ } }
            assertEquals(2, checks, "materialize must poll while validating its inputs")
            val valid = second.negate(local)
            assertContentEquals(_a[-3f, -4f], FloatArray(2).also { valid.read(0, it) })
        } finally { second.close(); first.close() }
    }

    @Test fun releasedTensorIsRejectedBeforeReuse() {
        val session = MlxMatrixOperations().open()
        try {
            val released = session.tensor(_a[1f], s_[1])
            session.release(released)
            val mark = session.mark()
            assertFailsWith<IllegalStateException> { session.negate(released) }
            assertEquals(mark, session.mark())
        } finally { session.close() }
    }
}
>>>>>>> 3a7a31d16661bac717ea8d8f3ee80d7db578ce78
