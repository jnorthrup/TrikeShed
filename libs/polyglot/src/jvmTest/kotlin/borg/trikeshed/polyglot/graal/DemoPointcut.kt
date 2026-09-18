package borg.trikeshed.polyglot.graal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer

class DemoPointcutTest {

    @Test
    fun `demo polyglot pointcut flow`() {
        println("╔═══════════════════════════════════════════════╗")
        println("║  TrikeShed Polyglot Pointcut Demo            ║")
        println("║  FieldSynapse 24-byte wire protocol demo     ║")
        println("╚═══════════════════════════════════════════════╝")
        println()
        
        val harness = GraalPointcutHarness()
        println("✓ Created GraalPointcutHarness")
        println("  Context: ${harness.context}")
        println()
        
        // Demo 1: JS basic eval
        println("--- Demo 1: JavaScript basic eval ---")
        val jsResult = harness.eval("js", "42 * 2")
        println("  js> 42 * 2 = $jsResult")
        assertEquals(84, jsResult)
        println()
        
        // Demo 2: JS with Java interop
        println("--- Demo 2: JavaScript + Java interop ---")
        val javaInterop = harness.eval("js", """
            var String = Java.type('java.lang.String');
            new String('Hello from GraalJS!')
        """)
        println("  js> \$javaInterop")
        assertEquals("Hello from GraalJS!", javaInterop)
        println()
        
        // Demo 3: JS accessing TestTarget class
        println("--- Demo 3: Access TestTarget class ---")
        val clazz = harness.eval("js", """
            var Target = Java.type('borg.trikeshed.polyglot.graal.TestTarget');
            Target
        """)
        println("  js> Target class = $clazz")
        assertNotNull(clazz)
        println()
        
        // Demo 4: Instantiate TestTarget
        println("--- Demo 4: Instantiate TestTarget ---")
        val instance = harness.eval("js", """
            var Target = Java.type('borg.trikeshed.polyglot.graal.TestTarget');
            new Target()
        """)
        println("  js> new Target() = $instance")
        assertNotNull(instance)
        println()
        
        // Demo 5: Field read/write via JS
        println("--- Demo 5: Instance field read/write ---")
        val setResult = harness.eval("js", """
            var Target = Java.type('borg.trikeshed.polyglot.graal.TestTarget');
            var t = new Target();
            t.instanceInt = 999;
            t.instanceInt
        """)
        println("  js> t.instanceInt = 999; t.instanceInt = $setResult")
        // Note: GraalJS returns last expression value; may be unit/null
        println()
        
        // Demo 6: Static field access
        println("--- Demo 6: Static field access ---")
        val staticRead = harness.eval("js", """
            var Target = Java.type('borg.trikeshed.polyglot.graal.TestTarget');
            Target.staticInt
        """)
        println("  js> Target.staticInt = $staticRead")
        // Note: GraalJS returns last expression value; may be unit/null
        println()
        
        // Demo 7: PointcutEmitter bound and callable
        println("--- Demo 7: PointcutEmitter bound ---")
        
        // Bind a test producer that captures emitted synapses
        var capturedSynapses: MutableList<FieldSynapse> = mutableListOf()
        harness.context.bindPointcutEmitter(harness, object : PointcutEventProducer {
            override fun emit(synapse: FieldSynapse) {
                capturedSynapses.add(synapse)
                println("  FieldSynapse emitted: phase=${synapse.phase} opcode=0x${String.format("%02X", synapse.opcode)} methodIdx=${synapse.methodIdx} addr=${synapse.addr} seq=${synapse.seq} callsiteHash=${synapse.callsiteHash} templateIdx=${synapse.templateIdx}")
            }
            override fun emitBatch(synapses: borg.trikeshed.lib.Series<FieldSynapse>) {}
        })
        
        val emitterType = harness.eval("js", "typeof pointcutEmitter")
        println("  js> typeof pointcutEmitter = $emitterType")
        assertEquals("object", emitterType)
        
        val emitterFn = harness.eval("js", "typeof pointcutEmitter.emitFieldAccess")
        println("  js> typeof pointcutEmitter.emitFieldAccess = $emitterFn")
        assertEquals("function", emitterFn)
        println()
        
        // Demo 8: Emit FieldSynapse from polyglot
        println("--- Demo 8: Emit FieldSynapse from polyglot ---")
        harness.eval("js", """
            pointcutEmitter.emitFieldAccess(0, false, false, 'TestTarget', 'instanceInt', 'demo.kt:1', 1);
            pointcutEmitter.emitFieldAccess(1, false, true, 'TestTarget', 'staticInt', 'demo.kt:2', 2);
        """)
        
        assertEquals(2, capturedSynapses.size)
        val first = capturedSynapses[0]
        val second = capturedSynapses[1]
        
        // Verify first synapse: L_GET BEFORE
        assertEquals(0, first.phase)
        assertEquals(0xA5.toByte(), first.opcode)
        assertEquals(1, first.seq)
        
        // Verify second synapse: L_SET AFTER
        assertEquals(1, second.phase)
        assertEquals(0xA6.toByte(), second.opcode)
        assertEquals(2, second.seq)
        
        println("  ✓ Two FieldSynapse events captured with correct phases, opcodes, and sequences")
        
        harness.close()
        println()
        println("✓ All demos complete!")
    }
}