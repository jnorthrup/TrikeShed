package org.xvm.runtime;

import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

import org.xvm.tool.Console;
import org.xvm.tool.Launcher;
import org.xvm.tool.Launcher.LauncherException;
import org.xvm.tool.LauncherOptions.RunnerOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live VM smoke test for VmPointcutPublisher.
 * Runs a real .x module through the VM dispatch loop and reports captured
 * events.
 */
public class VmPointcutFirehoseTest {

    @TempDir
    java.nio.file.Path tempDir;

    // ─── live VM rate test ─────────────────────────────────────────────────────

    @Test
    public void liveFirehose() throws Exception {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;

        try {
            // ── warmup module run (no timing, no assertions) ──
            warmupModule(
                    "module Warmup { void run() { @Inject Console c; c.print(\"warmup\"); } }");
            // reset to clear warmup events
            VmPointcutPublisher.reset();
            VmPointcutPublisher.active = true;

            // ── timed run ──
            long t0 = System.nanoTime();
            String output = runModule(
                    "module FirehoseTest {" +
                            "  void run() {" +
                            "    @Inject Console c;" +
                            "    c.print(\"hello\");" +
                            "  }" +
                            "}");
            long t1 = System.nanoTime();

            int[] counts = new int[256];
            AtomicInteger total = new AtomicInteger();

            VmPointcutPublisher.drain(evt -> {
                int op = evt.opcode;
                if (counts[op] == 0)
                    counts[op] = 1;
                else
                    counts[op]++;
                total.incrementAndGet();
                assertTrue(evt.nano >= t0 && evt.nano <= t1, "firehose event nano must be in bounds");
            });

            int got = total.get();

            System.out.println("\n=== live VM firehose ===");
            System.out.println("module output : " + output.trim());
            System.out.println("total events  : " + got);
            if (got == 0) {
                System.out
                        .println("NOTE: 0 events — likely fiber paused at MAX_OPS_PER_RUN before dispatch loop entry");
            } else {
                System.out.println("opcode distribution:");
                for (int op = 0; op < 256; op++) {
                    if (counts[op] > 0) {
                        var name = new VmPointcutPublisher.PointcutEvent(0, 0, op, 0, "").opcodeName();
                        System.out.println("  " + name + "(0x" + Integer.toHexString(op) + ") = " + counts[op]);
                    }
                }
            }
            System.out.println("===========================\n");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @Test
    public void simulationFirehose_retainsEveryPublishedEventPastRingCapacity() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;

        try {
            var produced = 70000;
            for (var i = 0; i < produced; i++) {
                VmPointcutPublisher.publish(0x10, "Burst.run", i);
            }

            var drained = new AtomicInteger();
            VmPointcutPublisher.drain(evt -> {
                assertEquals(drained.get(), evt.addr, "drain order must preserve every simulated event");
                drained.incrementAndGet();
            });

            assertEquals(produced, drained.get(), "simulation firehose storage must be lossless");
            assertEquals(produced, VmPointcutPublisher.size(), "publisher size must report lossless storage size");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @Test
    public void simulationFirehose_wireprotoContainsEveryPublishedEventPastRingCapacity() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;

        try {
            var produced = 70000;
            for (var i = 0; i < produced; i++) {
                VmPointcutPublisher.publish(0x10, "Wire.run", i);
            }

            var wire = VmPointcutPublisher.drainToWireproto().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(produced * VmPointcutPublisher.RECORD_SIZE, wire.remaining());
            for (var i = 0; i < produced; i++) {
                var event = VmPointcutPublisher.fromWireproto(wire);
                assertEquals(i, event.seq, "wireproto seq must preserve publication order");
                assertEquals(i, event.addr, "wireproto addr must preserve every simulated event");
            }
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private static RunnerOptions optionsFor(java.nio.file.Path moduleFile) {
        var builder = new RunnerOptions.Builder()
                .setTarget(moduleFile.toFile());
        var xdkLib = System.getProperty("pointcutVm.xdkLibDir");
        if (xdkLib != null && !xdkLib.isBlank()) {
            builder.addModulePath(xdkLib);
            // javatools/ sibling contains javatools_turtle.xtc (mack.xtclang.org)
            builder.addModulePath(java.nio.file.Path.of(xdkLib).resolveSibling("javatools").toString());
        }
        // Add the directory containing the source so the compiled .xtc is
        // resolvable by its domain-qualified name (e.g. Warmup.mack.xtclang.org).
        builder.addModulePath(moduleFile.getParent().toString());
        return builder.build();
    }

    /** Warmup: run a module to trigger VM JIT compilation */
    private void warmupModule(String moduleSrc) throws Exception {
        var file = tempDir.resolve("Warmup.x");
        java.nio.file.Files.writeString(file, moduleSrc);
        Console console = new Console() {
            @Override
            public String out(Object o) {
                return "";
            }
        };
        try {
            Launcher.launch(optionsFor(file), console, null);
        } catch (LauncherException e) {
            /* ignore */ }
        System.out.println("[VmPointcutFirehoseTest] warmup module ran");
    }

    private String runModule(String moduleSrc) throws Exception {
        var file = tempDir.resolve("Test.x");
        java.nio.file.Files.writeString(file, moduleSrc);
        var sb = new StringBuilder();
        Console console = new Console() {
            @Override
            public String out(Object o) {
                if (o != null)
                    sb.append(o);
                return "";
            }
        };
        try {
            Launcher.launch(optionsFor(file), console, null);
        } catch (LauncherException e) {
            /* ignore */ }
        System.out.println("[VmPointcutFirehoseTest] module output: [" + sb + "]");
        return sb.toString();
    }

}
