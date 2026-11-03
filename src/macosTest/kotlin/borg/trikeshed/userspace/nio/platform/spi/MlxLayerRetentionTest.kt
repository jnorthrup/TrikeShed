package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import kotlin.test.*

class MlxLayerRetentionTest {
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
            val retained = session.tensor(_a[4f, 5f, 6f], shapeOf(3))
            assertContentEquals(_a[4f, 5f, 6f], FloatArray(3).also { retained.read(0, it) })
            session.settle(mark, retained)
            assertEquals(mark + 1, session.mark())
            assertContentEquals(_a[4f, 5f, 6f], FloatArray(3).also { retained.read(0, it) })
            assertEquals(mark + 2, session.mark(), "host data must be rematerialized after settlement")
            session.release(retained)
            assertFailsWith<IllegalStateException> { retained.read(0, FloatArray(3)) }
        } finally { session.close() }
    }

    @Test fun evaluatedLayersReleasePriorHiddenAndPreserveBorrowedTensor() {
        val session = MlxMatrixOperations().open()
        try {
            val parameter = session.tensor(_a[1f, 2f, 3f], shapeOf(3))
            val retained = session.mark()
            val output = session.scoped {
                var hidden = session.tensor(_a[0f, 0f, 0f], shapeOf(3))
                repeat(32) {
                    val previous = hidden
                    val mark = session.mark()
                    hidden = session.add(previous, parameter)
                    session.settleLayer(mark, previous, hidden)
                    assertEquals(retained + 1, session.mark(), "only the current hidden state should remain")
                }
                FloatArray(hidden.size).also { hidden.read(0, it) }
            }
            assertContentEquals(_a[32f, 64f, 96f], output)
            assertEquals(retained, session.mark(), "scope must preserve externally owned tensors")
            val stillOwned = FloatArray(parameter.size).also { parameter.read(0, it) }
            assertContentEquals(_a[1f, 2f, 3f], stillOwned)
        } finally { session.close() }
    }
}
