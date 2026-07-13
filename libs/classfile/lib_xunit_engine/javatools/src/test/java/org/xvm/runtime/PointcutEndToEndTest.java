package org.xvm.runtime;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.xvm.asm.ErrorList;
import org.xvm.tool.Compiler;
import org.xvm.tool.Launcher;
import org.xvm.tool.LauncherOptions.CompilerOptions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: compile real XTC, run through VM dispatch, capture pointcut event state.
 * NOT a unit test — runs the real VM pipeline and measures events.
 */
public class PointcutEndToEndTest {

    private static File xdkLibDir;
    private static File xdkJavaToolsDir;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void findXdk() {
        File base = new File(System.getProperty("user.dir"));
        File xdkBuild = new File(base, "xdk/build/install/xdk");
        if (!xdkBuild.exists()) {
            xdkBuild = new File(base.getParentFile(), "xdk/build/install/xdk");
        }
        xdkLibDir = new File(xdkBuild, "lib");
        xdkJavaToolsDir = new File(xdkBuild, "javatools");
        assertTrue(xdkLibDir.isDirectory(),
                "XDK lib not found: " + xdkLibDir + " — run ./gradlew installDist");
    }

    @Test
    void captureHelloWorld() throws Exception {
        captureModule("HelloWorld", """
            module HelloWorld {
                void run() {
                    @Inject Console c;
                    c.print("hello");
                }
            }
            """);
    }

    @Test
    void captureFactorial() throws Exception {
        captureModule("Factorial", """
            module Factorial {
                void run() {
                    @Inject Console c;
                    Int result = 1;
                    for (Int i = 1; i <= 10; ++i) {
                        result = result * i;
                    }
                    c.print(result.toString());
                }
            }
            """);
    }

    @Test
    void captureAllocLoop() throws Exception {
        captureModule("AllocLoop", """
            module AllocLoop {
                void run() {
                    @Inject Console c;
                    for (Int i = 0; i < 5; ++i) {
                        String s = "item_" + i.toString();
                        c.print(s);
                    }
                }
            }
            """);
    }

    @Test
    void captureNestedCalls() throws Exception {
        captureModule("NestedCalls", """
            module NestedCalls {
                Int add(Int a, Int b) { return a + b; }
                Int mul(Int a, Int b) { return a * b; }
                void run() {
                    @Inject Console c;
                    Int v = add(mul(3, 4), mul(5, 6));
                    c.print(v.toString());
                }
            }
            """);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Core capture logic
    // ═══════════════════════════════════════════════════════════════════════

    private void captureModule(String moduleName, String source) throws Exception {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;

        try {
            // Compile
            Path srcFile = tempDir.resolve(moduleName + ".x");
            Files.writeString(srcFile, source);
            File outputDir = tempDir.resolve("out").toFile();
            outputDir.mkdirs();

            ErrorList errors = new ErrorList(20);
            CompilerOptions opts = new CompilerOptions.Builder()
                    .addModulePath(xdkLibDir)
                    .addModulePath(xdkJavaToolsDir)
                    .setOutputLocation(outputDir)
                    .addInputFile(srcFile.toFile())
                    .build();

            Compiler compiler = new Compiler(opts, null, errors);
            int rc = compiler.run();
            System.out.println("[capture] compile rc=" + rc + " errors=" + errors.hasSeriousErrors());

            // Run with 5s timeout via thread
            File xtcFile = new File(outputDir, moduleName + ".xtc");
            StringBuilder sb = new StringBuilder();
            org.xvm.tool.Console console = new org.xvm.tool.Console() {
                @Override public String out(Object o) { if (o != null) sb.append(o); return ""; }
            };

            String[] runArgs = {
                "-L", xdkLibDir.getAbsolutePath(),
                "-L", xdkJavaToolsDir.getAbsolutePath(),
                "-L", outputDir.getAbsolutePath(),
                xtcFile.getAbsolutePath()
            };

            Thread runner = new Thread(() -> {
                try {
                    Launcher.launch(Launcher.CMD_RUN, runArgs, console, null);
                } catch (Exception e) {
                    System.out.println("[run] " + e.getMessage());
                }
            }, "vm-runner");
            runner.setDaemon(true);
            runner.start();
            runner.join(5_000); // 5s max

            // Capture state immediately
            long totalInvoked = VmPointcutPublisher.totalInvoked();
            int ringSize = VmPointcutPublisher.size();
            Map<String, Integer> dist = opcodeDistribution();

            System.out.println("\n=== " + moduleName + " ===");
            System.out.println("output        : " + sb.toString().trim().replaceAll("\n.*", ""));
            System.out.println("publish() called: " + totalInvoked);
            System.out.println("ring captured : " + ringSize + " / " + totalInvoked);
            System.out.println("ring overflow : " + (totalInvoked > 65536 ? "yes" : "no"));
            dumpDist(dist);
            System.out.println("==================\n");

        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    private Map<String, Integer> opcodeDistribution() {
        int[] counts = new int[256];
        VmPointcutPublisher.drain(evt -> counts[evt.opcode]++);
        Map<String, Integer> dist = new LinkedHashMap<>();
        for (int op = 0; op < 256; op++) {
            if (counts[op] > 0) {
                dist.put(opcodeName(op), counts[op]);
            }
        }
        return dist;
    }

    private void dumpDist(Map<String, Integer> dist) {
        int total = dist.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            System.out.println("(no events in ring)");
            return;
        }
        System.out.println("opcode distribution (" + total + " captured):");
        dist.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.println(String.format("  %-16s %5d  (%.1f%%)",
                        e.getKey(), e.getValue(), 100.0 * e.getValue() / total)));
    }

    private static String opcodeName(int op) {
        return new VmPointcutPublisher.PointcutEvent(0, 0, op, 0, "").opcodeName();
    }
}
