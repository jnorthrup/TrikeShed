package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import kotlin.test.*

class MlxLayerRetentionTest {
    @Test fun settlementTracksAllocationsAfterOlderTensorsAreReleased() {
        val session = MlxMatrixOperations().open()
        try {
            val old = session.tensor(_a[1f], shapeOf(1))
            val before = session.mark()
            val stale = session.tensor(_a[3f], shapeOf(1))
            val keep = session.tensor(_a[2f], shapeOf(1))
            session.release(old)
            session.settle(before, keep)
            assertContentEquals(_a[2f], FloatArray(1).also { keep.read(0, it) })
            assertFailsWith<IllegalStateException> { stale.read(0, FloatArray(1)) }
        } finally { session.close() }
    }

    @Test fun scopeFreesItsAllocationsAfterEarlierTensorIsReleased() {
        val session = MlxMatrixOperations().open()
        try {
            val old = session.tensor(_a[1f], shapeOf(1))
            lateinit var stale: MlxTensor
            session.scoped {
                session.release(old)
                stale = session.tensor(_a[2f], shapeOf(1))
            }
            assertFailsWith<IllegalStateException> { stale.read(0, FloatArray(1)) }
        } finally { session.close() }
    }

    @Test fun aMarkCannotBeUsedByAnotherSession() {
        val first = MlxMatrixOperations().open()
        val second = MlxMatrixOperations().open()
        try {
            val mark = first.mark()
            val tensor = second.tensor(_a[2f], shapeOf(1))
            assertFailsWith<IllegalArgumentException> { second.settle(mark) }
            assertContentEquals(_a[2f], FloatArray(1).also { tensor.read(0, it) })
        } finally { second.close(); first.close() }
    }

    @Test fun nestedMarksKeepOnlyExplicitSurvivors() {
        val session = MlxMatrixOperations().open()
        try {
            val outer = session.mark()
            val old = session.tensor(_a[1f], shapeOf(1))
            val inner = session.mark()
            val stale = session.tensor(_a[2f], shapeOf(1))
            val keep = session.tensor(_a[3f], shapeOf(1))
            session.release(old)
            session.settle(inner, keep)
            assertFailsWith<IllegalStateException> { stale.read(0, FloatArray(1)) }
            session.settle(outer, keep)
            assertContentEquals(_a[3f], FloatArray(1).also { keep.read(0, it) })
        } finally { session.close() }
    }

    @Test fun failedScopeStillFreesAllocationsAfterEarlierRelease() {
        val session = MlxMatrixOperations().open()
        try {
            val old = session.tensor(_a[1f], shapeOf(1))
            lateinit var stale: MlxTensor
            class Stop : RuntimeException()
            assertFailsWith<Stop> {
                session.scoped {
                    session.release(old)
                    stale = session.tensor(_a[2f], shapeOf(1))
                    throw Stop()
                }
            }
            assertFailsWith<IllegalStateException> { stale.read(0, FloatArray(1)) }
        } finally { session.close() }
    }

    @Test fun releasedTensorsRefuseAccessAndRetainedTensorsRematerializeHostData() {
        val session = MlxMatrixOperations().open()
        try {
            lateinit var stale: MlxTensor
            session.scoped {
                stale = session.tensor(_a[1f, 2f, 3f], shapeOf(3))
                assertContentEquals(_a[1f, 2f, 3f], FloatArray(3).also { stale.read(0, it) })
            }
            // Exercise allocation reuse; a stale wrapper must never regain ownership.
            repeat(32) { session.scoped { session.tensor(_a[9f], shapeOf(1)) } }
            assertFailsWith<IllegalStateException> { stale.read(0, FloatArray(3)) }
            assertFailsWith<IllegalStateException> { stale.read(0, FloatArray(0), 0, 0) }
            assertFailsWith<IllegalStateException> { session.add(stale, stale) }

            val mark = session.mark()
            val count = session.allocationCount
            val retained = session.tensor(_a[4f, 5f, 6f], shapeOf(3))
            assertContentEquals(_a[4f, 5f, 6f], FloatArray(3).also { retained.read(0, it) })
            session.settle(mark, retained)
            assertEquals(count + 1, session.allocationCount)
            assertContentEquals(_a[4f, 5f, 6f], FloatArray(3).also { retained.read(0, it) })
            assertEquals(count + 2, session.allocationCount, "host data must be rematerialized after settlement")
            session.release(retained)
            assertFailsWith<IllegalStateException> { retained.read(0, FloatArray(3)) }
        } finally { session.close() }
    }

    @Test fun evaluatedLayersReleasePriorHiddenAndPreserveBorrowedTensor() {
        val session = MlxMatrixOperations().open()
        try {
            val parameter = session.tensor(_a[1f, 2f, 3f], shapeOf(3))
            val retained = session.allocationCount
            val output = session.scoped {
                var hidden = session.tensor(_a[0f, 0f, 0f], shapeOf(3))
                repeat(32) {
                    val previous = hidden
                    val mark = session.mark()
                    hidden = session.add(previous, parameter)
                    session.settleLayer(mark, previous, hidden)
                    assertEquals(retained + 1, session.allocationCount, "only the current hidden state should remain")
                }
                FloatArray(hidden.size).also { hidden.read(0, it) }
            }
            assertContentEquals(_a[32f, 64f, 96f], output)
            assertEquals(retained, session.allocationCount, "scope must preserve externally owned tensors")
            val stillOwned = FloatArray(parameter.size).also { parameter.read(0, it) }
            assertContentEquals(_a[1f, 2f, 3f], stillOwned)
        } finally { session.close() }
    }
}
