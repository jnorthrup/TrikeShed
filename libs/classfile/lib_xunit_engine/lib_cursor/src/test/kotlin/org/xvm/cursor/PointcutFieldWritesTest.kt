package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xvm.runtime.FieldSynapse
import org.xvm.runtime.PointcutObservation
import org.xvm.runtime.VmPointcutPublisher

/**
 * TDD RED: PointcutField writes across xvm java classes
 *
 * Pointcut all writes (l_get/l_set/p_get/p_set) in the xvm codebase java classes.
 * This requires FieldSynapse to capture field opcode events from running code.
 *
 * OpCodes (from FieldSynapse):
 *   L_GET  = 0xA5  (field read, non-static)
 *   L_SET  = 0xA6  (field write, non-static)
 *   P_GET  = 0xA7  (field read, static)
 *   P_SET  = 0xA8  (field write, static)
 *
 * Wireproto 24B per record: opcode(1) + phase(1) + methodIdx(2) + addr(4)
 * + seq(4) + nano(8) + callsiteHash(2) + templateIdx(2).
 */
class PointcutFieldWritesTest {

    @Test
    fun `field synapse captures l_get opcode events`() {
        FieldSynapse.reset()
        FieldSynapse.active = true

        // Publish synthetic field access events
        val opcode = 0xA5 // L_GET
        FieldSynapse.publishStatic(opcode, "pkg.Class.fieldName", 100, false)

        // Flush and drain
        FieldSynapse.flush("test-l-get")
        var capturedCount = 0
        FieldSynapse.drain { fs ->
            val reified = fs.reify()
            if (reified.isNotEmpty()) capturedCount++
        }

        assertEquals(1, capturedCount)
        assertEquals(1, FieldSynapse.size())
        FieldSynapse.reset()
    }

    @Test
    fun `field synapse captures l_set opcode events`() {
        FieldSynapse.reset()
        FieldSynapse.active = true

        val opcode = 0xA6 // L_SET
        FieldSynapse.publishStatic(opcode, "pkg.Setter.run", 200, true)

        FieldSynapse.flush("test-l-set")
        var capturedCount = 0
        FieldSynapse.drain { fs ->
            val reified = fs.reify()
            if (reified.isNotEmpty()) capturedCount++
        }

        assertEquals(1, capturedCount)
        assertEquals(1, FieldSynapse.size())
        FieldSynapse.reset()
    }

    @Test
    fun `field synapse captures p_get and p_set static opcodes`() {
        FieldSynapse.reset()
        FieldSynapse.active = true

        val pGet = 0xA7 // P_GET
        val pSet = 0xA8 // P_SET

        FieldSynapse.publishStatic(pGet, "pkg.Static.field", 50, false)
        FieldSynapse.publishStatic(pSet, "pkg.Static.field", 50, true)

        FieldSynapse.flush("test-static-field")
        var capturedCount = 0
        FieldSynapse.drain { fs ->
            val reified = fs.reify()
            if (reified.isNotEmpty()) capturedCount++
        }

        assertEquals(2, capturedCount)
        assertEquals(2, FieldSynapse.size())
        FieldSynapse.reset()
    }

    @Test
    fun `pointcut observation receives field source events`() {
        var observedCount = 0
        val subId = PointcutObservation.subscribe { source, count, epoch ->
            if (source == PointcutObservation.Source.FIELD) {
                observedCount++
            }
        }

        FieldSynapse.reset()
        FieldSynapse.active = true

        // Publish events that should trigger FIELD source observation
        FieldSynapse.publishStatic(0xA5, "pkg.Observe.get", 100, false)
        FieldSynapse.publishStatic(0xA6, "pkg.Observe.set", 100, true)

        FieldSynapse.flush("observation-test")

        assertEquals(1, observedCount)

        PointcutObservation.unsubscribe(subId)
        FieldSynapse.reset()
    }

    @Test
    fun `ring series survives firehose rate field writes`() {
        FieldSynapse.reset()
        FieldSynapse.active = true

        // Firehose: publish many events rapidly
        val count = 2048 // RingSeries default capacity
        for (i in 0 until count) {
            val opcode = 0xA5 + (i % 4) // Cycle through A5-A8
            FieldSynapse.publishStatic(opcode, "pkg.Firehose.field$i", i % 256, (i % 2 == 1))
        }
        FieldSynapse.flush("firehose")

        // Drain and verify
        var drainedCount = 0
        FieldSynapse.drain { fs ->
            drainedCount++
        }

        assertEquals(count, drainedCount, "Firehose drain should retain every field event")
        assertEquals(count, FieldSynapse.size())
        FieldSynapse.reset()
    }

    @Test
    fun `wireproto encode produces correct record size for field events`() {
        FieldSynapse.reset()
        FieldSynapse.active = true

        // Publish exactly 10 events
        for (i in 0 until 10) {
            FieldSynapse.publishStatic(0xA5, "pkg.Wire.field", 100 + i, (i % 2 == 1))
        }

        val wireBuf = FieldSynapse.drainToWireproto()
        val remaining = wireBuf.remaining()

        // Expected: 10 records * FieldSynapse.RECORD_SIZE bytes
        val recordSize = FieldSynapse.RECORD_SIZE
        val expected = 10 * recordSize

        assertTrue(remaining > 0, "Wire buffer must have content")
        assertEquals(expected, remaining, "Wireproto should encode exactly 10 field records")
        FieldSynapse.reset()
    }

    @Test
    fun `field synapse reify produces non-empty string for non-trivial event`() {
        FieldSynapse.reset()
        FieldSynapse.active = true

        // Publish a non-trivial event with realistic method name
        FieldSynapse.publishStatic(0xA6, "org/xvm/asm/ConstantPool.getType", 0x42, true)

        FieldSynapse.flush("reify-test")

        var reifiedString = ""
        FieldSynapse.drain { fs ->
            reifiedString = fs.reify()
        }

        assertTrue(reifiedString.isNotEmpty(), "Non-trivial field event must reify to non-empty string")
        assertTrue(reifiedString.contains("getType") || reifiedString.contains("ConstantPool"),
            "Reified string should contain method name evidence")

        FieldSynapse.reset()
    }

    @Test
    fun `all four field opcodes (A5-A8) distinguishable in drain`() {
        FieldSynapse.reset()
        FieldSynapse.active = true

        // Publish one of each
        FieldSynapse.publishStatic(0xA5, "pkg.F.ldGet", 1, false)
        FieldSynapse.publishStatic(0xA6, "pkg.F.ldSet", 2, true)
        FieldSynapse.publishStatic(0xA7, "pkg.F.puGet", 3, false)
        FieldSynapse.publishStatic(0xA8, "pkg.F.puSet", 4, true)

        FieldSynapse.flush("four-opcodes")

        val events = mutableListOf<String>()
        FieldSynapse.drain { fs ->
            events.add(fs.opcodeName())
        }

        assertEquals(4, events.size, "Four distinct opcodes should produce four drain entries")
        assertTrue(events.contains("L_GET"))
        assertTrue(events.contains("L_SET"))
        assertTrue(events.contains("P_GET"))
        assertTrue(events.contains("P_SET"))
        FieldSynapse.reset()
    }
}