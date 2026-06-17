package org.xvm.runtime;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: VmPointcutEmitter — pure JVM normalization + in-process dispatch.
 * Replaces VmPointcutEmitter.py entirely.
 */
public class VmPointcutEmitterTest {

    // ── phaseOf / familyOf ─────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void phaseOf_callOpcodes() {
        for (int op = 0x10; op <= 0x1F; op++) {
            assertEquals("CALL", VmPointcutEmitter.phaseOf(op), opcodeHex(op) + " should be CALL");
        }
        for (int op = 0x20; op <= 0x2F; op++) {
            assertEquals("CALL", VmPointcutEmitter.phaseOf(op), opcodeHex(op) + " should be CALL");
        }
    }

    @org.junit.jupiter.api.Test
    public void phaseOf_constrOpcodes() {
        for (int op = 0x34; op <= 0x37; op++) {
            assertEquals("ALLOC", VmPointcutEmitter.phaseOf(op), opcodeHex(op) + " should be ALLOC");
        }
    }

    @org.junit.jupiter.api.Test
    public void phaseOf_newOpcodes() {
        for (int op = 0x38; op <= 0x3B; op++) {
            assertEquals("ALLOC", VmPointcutEmitter.phaseOf(op), opcodeHex(op) + " should be ALLOC");
        }
    }

    @org.junit.jupiter.api.Test
    public void phaseOf_newcOpcodes() {
        for (int op = 0x40; op <= 0x43; op++) {
            assertEquals("ALLOC", VmPointcutEmitter.phaseOf(op), opcodeHex(op) + " should be ALLOC");
        }
    }

    @org.junit.jupiter.api.Test
    public void phaseOf_newvOpcodes() {
        for (int op = 0x48; op <= 0x4B; op++) {
            assertEquals("ALLOC", VmPointcutEmitter.phaseOf(op), opcodeHex(op) + " should be ALLOC");
        }
    }

    @org.junit.jupiter.api.Test
    public void phaseOf_returnOpcodes() {
        for (int op = 0x4C; op <= 0x4F; op++) {
            assertEquals("RETURN", VmPointcutEmitter.phaseOf(op), opcodeHex(op) + " should be RETURN");
        }
    }

    @org.junit.jupiter.api.Test
    public void phaseOf_fieldOpcodes() {
        assertEquals("FIELD", VmPointcutEmitter.phaseOf(0xA5));
        assertEquals("FIELD", VmPointcutEmitter.phaseOf(0xA6));
        assertEquals("FIELD", VmPointcutEmitter.phaseOf(0xA7));
        assertEquals("FIELD", VmPointcutEmitter.phaseOf(0xA8));
    }

    @org.junit.jupiter.api.Test
    public void phaseOf_loopOpcodes() {
        for (int op = 0x70; op <= 0x7F; op++) {
            assertEquals("LOOP", VmPointcutEmitter.phaseOf(op), opcodeHex(op) + " should be LOOP");
        }
    }

    @org.junit.jupiter.api.Test
    public void phaseOf_gapOpcodes() {
        assertEquals("GAP", VmPointcutEmitter.phaseOf(0x00));
        assertEquals("GAP", VmPointcutEmitter.phaseOf(0x50));
        assertEquals("GAP", VmPointcutEmitter.phaseOf(0xFF));
    }

    @org.junit.jupiter.api.Test
    public void phaseOf_outOfRange_negative() {
        assertEquals("GAP", VmPointcutEmitter.phaseOf(-1));
    }

    @org.junit.jupiter.api.Test
    public void phaseOf_outOfRange_256() {
        assertEquals("GAP", VmPointcutEmitter.phaseOf(256));
    }

    // ── normalizeLine ───────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void normalizeLine_ghidraRawFormat() {
        String raw = "{\"opcode\":52,\"method\":\"org/example/MyClass.<init>\",\"addr\":100}";
        String result = VmPointcutEmitter.normalizeLine(raw, 0);
        assertNotNull(result);
        assertTrue(result.contains("\"opcode\":52"), "should have opcode 52 (0x34 CONSTR_0)");
        assertTrue(result.contains("\"method\":\"org/example/MyClass.<init>\""), "should preserve method");
        assertTrue(result.contains("\"phase\":\"ALLOC\""), "should enrich phase");
    }

    @org.junit.jupiter.api.Test
    public void normalizeLine_alreadyNormalized() {
        // Already in PointcutServer wire format
        String normalized = "{\"seq\":0,\"nano\":123,\"opcode\":0xA5,\"phase\":\"FIELD\",\"addr\":10,\"method\":\"Foo.bar\"}";
        String result = VmPointcutEmitter.normalizeLine(normalized, 5);
        assertNotNull(result);
        assertTrue(result.contains("\"opcode\":165") || result.contains("\"opcode\":0xa5"), "should preserve opcode");
    }

    @org.junit.jupiter.api.Test
    public void normalizeLine_singleQuotes() {
        // JSON5 single quotes
        String raw = "{'opcode': 52, 'method': 'Foo.bar', 'addr': 10}";
        String result = VmPointcutEmitter.normalizeLine(raw, 0);
        assertNotNull(result, "single-quote JSON5 should be handled");
        assertTrue(result.contains("\"opcode\":52"));
    }

    @org.junit.jupiter.api.Test
    public void normalizeLine_trailingComma() {
        String raw = "{\"opcode\":52,\"method\":\"Foo.bar\",\"addr\":10,}";
        String result = VmPointcutEmitter.normalizeLine(raw, 0);
        assertNotNull(result, "trailing comma should be stripped");
    }

    @org.junit.jupiter.api.Test
    public void normalizeLine_noValidOpcode_returnsNull() {
        // No digits at all — extractFirstNumber returns null
        String raw = "{\"no\":\"opcode\",\"here\":\"textonly\"}";
        String result = VmPointcutEmitter.normalizeLine(raw, 0);
        assertNull(result, "no digits → no opcode → null");
    }

    @org.junit.jupiter.api.Test
    public void normalizeLine_enrichesPhase() {
        String raw = "{\"opcode\":52,\"method\":\"Test.method\"}";  // no phase field
        String result = VmPointcutEmitter.normalizeLine(raw, 0);
        assertNotNull(result);
        assertTrue(result.contains("\"phase\":\"ALLOC\""), "phase should be auto-enriched");
    }

    @org.junit.jupiter.api.Test
    public void normalizeLine_preservesPhase() {
        String raw = "{\"opcode\":52,\"method\":\"Test.method\",\"phase\":\"CONSTRUCTOR\"}";
        String result = VmPointcutEmitter.normalizeLine(raw, 0);
        assertNotNull(result);
        assertTrue(result.contains("\"phase\":\"CONSTRUCTOR\"") || result.contains("\"phase\":\"ALLOC\""),
            "existing phase should be preserved or mapped to ALLOC");
    }

    @org.junit.jupiter.api.Test
    public void normalizeLine_fieldOpcode_phaseIsField() {
        String raw = "{\"opcode\":165,\"method\":\"MyClass.prop\"}";  // 0xA5 L_GET
        String result = VmPointcutEmitter.normalizeLine(raw, 0);
        assertNotNull(result);
        assertTrue(result.contains("\"phase\":\"FIELD\""), "L_GET (0xA5) should get FIELD phase");
    }

    @org.junit.jupiter.api.Test
    public void normalizeLine_escapesJsonString() {
        String raw = "{\"opcode\":52,\"method\":\"MyClass.method\\nwith\\tnewlines\"}";
        String result = VmPointcutEmitter.normalizeLine(raw, 0);
        assertNotNull(result);
        assertTrue(result.contains("\\n"), "newlines should be escaped");
        assertTrue(result.contains("\\t"), "tabs should be escaped");
    }

    // ── in-process publish ─────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void inprocess_publishesToPublisher() throws Exception {
        // Write a temp NDJSON file
        Path tmp = Files.createTempFile("ghidra_out", ".ndjson");
        Files.writeString(tmp, """
            {"opcode":52,"method":"Test.method","addr":100}
            {"opcode":165,"method":"Test.prop","addr":200}
            """);

        try {
            VmPointcutPublisher.reset();
            VmPointcutPublisher.active = true;

            // Simulate in-process: just call publish directly on the parsed normalized output
            VmPointcutPublisher.publish(0x34, "Test.constr", 100);
            VmPointcutPublisher.publish(0xA5, "Test.getter", 200);

            int[] counts = new int[256];
            AtomicInteger total = new AtomicInteger();
            VmPointcutPublisher.drain(evt -> {
                counts[evt.opcode]++;
                total.incrementAndGet();
            });

            assertEquals(2, total.get());
            assertEquals(1, counts[0x34]);
            assertEquals(1, counts[0xA5]);
        } finally {
            VmPointcutPublisher.active = false;
            Files.deleteIfExists(tmp);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String opcodeHex(int op) {
        return "0x" + Integer.toHexString(op);
    }
}