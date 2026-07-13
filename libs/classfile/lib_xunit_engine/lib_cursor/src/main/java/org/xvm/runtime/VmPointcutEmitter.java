package org.xvm.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pointcut event normalizer — pure JVM, zero external deps.
 *
 * Consumes NDJSON event streams (opcode + method + addr per line).
 * Emits normalized NDJSON or direct in-process dispatch.
 *
 * Pipeline:
 *   NDJSON source → this emitter (normalize + index)
 *       → PointcutServer.stdinFlow()        (NDJSON output mode)
 *       → VmPointcutPublisher.publish()      (in-process mode, zero IPC)
 *           → ObservableConfixOracle (TrikeShed ConfixParser)
 *
 * Usage:
 *   java -cp javatools.jar org.xvm.runtime.VmPointcutEmitter \
 *       --input /tmp/events.ndjson \
 *       --output /tmp/normalized.ndjson
 *
 *   // or in-process:
 *   java -cp javatools.jar org.xvm.runtime.VmPointcutEmitter \
 *       --input /tmp/events.ndjson \
 *       --mode inprocess
 */
public final class VmPointcutEmitter {

    /** Opcode → phase name (mirrors VmPointcutDispatch.Kind) */
    private static final String[] PHASE_NAME = new String[256];
    private static final int[] OPCODE_FAMILY = new int[256];

    static {
        Arrays.fill(PHASE_NAME, "GAP");
        Arrays.fill(OPCODE_FAMILY, -1);

        // CALL (0x10-0x1F)
        fillRange(0x10, 0x1F, "CALL", 1);
        // NVOK (0x20-0x2F)
        fillRange(0x20, 0x2F, "CALL", 2);
        // SYN_INIT (0x33)
        setOne(0x33, "SYNC", 3);
        // CONSTR (0x34-0x37)
        fillRange(0x34, 0x37, "ALLOC", 4);
        // NEW (0x38-0x3B)
        fillRange(0x38, 0x3B, "ALLOC", 5);
        // NEWC (0x40-0x43)
        fillRange(0x40, 0x43, "ALLOC", 6);
        // NEWV (0x48-0x4B)
        fillRange(0x48, 0x4B, "ALLOC", 7);
        // RETURN (0x4C-0x4F)
        fillRange(0x4C, 0x4F, "RETURN", 8);
        // MOV_TYPE (0x65)
        setOne(0x65, "TYPE", 9);
        // CAST (0x66)
        setOne(0x66, "TYPE", 10);
        // LOOP (0x70-0x7F)
        fillRange(0x70, 0x7F, "LOOP", 11);
        // ASSERT (0x90-0x92)
        fillRange(0x90, 0x92, "ASSERT", 12);
        // L_GET (0xA5)
        setOne(0xA5, "FIELD", 13);
        // L_SET (0xA6)
        setOne(0xA6, "FIELD", 14);
        // P_GET (0xA7)
        setOne(0xA7, "FIELD", 15);
        // P_SET (0xA8)
        setOne(0xA8, "FIELD", 16);
    }

    private static void fillRange(int from, int to, String phase, int family) {
        for (int op = from; op <= to; op++) {
            PHASE_NAME[op] = phase;
            OPCODE_FAMILY[op] = family;
        }
    }

    private static void setOne(int op, String phase, int family) {
        PHASE_NAME[op] = phase;
        OPCODE_FAMILY[op] = family;
    }

    public static String phaseOf(int opcode) {
        if (opcode < 0 || opcode >= 256) return "GAP";
        return PHASE_NAME[opcode];
    }

    public static int familyOf(int opcode) {
        if (opcode < 0 || opcode >= 256) return -1;
        return OPCODE_FAMILY[opcode];
    }

    // ── Mode ───────────────────────────────────────────────────────────────────

    public enum Mode {
        /** Write normalized NDJSON to --output file or stdout */
        NDJSON,
        /** Call VmPointcutPublisher.publish() directly (in-process, no serialization) */
        INPROCESS,
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        var input = findArg(args, "--input", "-i");
        var output = findArg(args, "--output", "-o");
        var modeArg = findArg(args, "--mode", "-m");
        var verbose = hasArg(args, "--verbose", "-v");
        var help = hasArg(args, "--help", "-h");

        if (help) {
            System.err.println("""
                Usage: VmPointcutEmitter [options]
                
                  --input  <path>   NDJSON input (default: stdin)
                  --output <path>   Normalized NDJSON output (default: stdout)
                  --mode   <mode>   NDJSON (default) | INPROCESS
                  --verbose         Print progress to stderr
                  --help            This message
                """);
            return;
        }

        Mode mode = Mode.NDJSON;
        if (modeArg != null) {
            mode = Mode.valueOf(modeArg.toUpperCase());
        }

        Path inputPath = input != null ? Path.of(input) : null;
        Path outputPath = output != null ? Path.of(output) : null;

        if (verbose) {
            System.err.println("[VmPointcutEmitter] mode=" + mode + ", input=" + inputPath + ", output=" + outputPath);
        }

        if (mode == Mode.INPROCESS) {
            runInProcess(inputPath, verbose);
        } else {
            runNdjson(inputPath, outputPath, verbose);
        }
    }

    // ── NDJSON mode ────────────────────────────────────────────────────────────

    /**
     * Read NDJSON, normalize each line, write to output.
     */
    private static void runNdjson(Path inputPath, Path outputPath, boolean verbose) throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        long start = System.nanoTime();

        var inputStream = inputPath != null
            ? new BufferedReader(new InputStreamReader(new FileInputStream(inputPath.toFile())))
            : new BufferedReader(new InputStreamReader(System.in));

        var outputStream = outputPath != null
            ? new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputPath.toFile())))
            : new BufferedWriter(new OutputStreamWriter(System.out));

        try {
            String line;
            while ((line = inputStream.readLine()) != null) {
                if (line.isBlank()) continue;

                String normalized = normalizeLine(line, count.get());
                if (normalized != null) {
                    outputStream.write(normalized);
                    outputStream.newLine();
                    count.incrementAndGet();
                }
            }
        } finally {
            inputStream.close();
            outputStream.close();
        }

        if (verbose) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.err.println("[VmPointcutEmitter] " + count.get() + " events normalized in " + elapsed + "ms");
        }
    }

    /**
     * Normalize a raw NDJSON line into wire format.
     */
    static String normalizeLine(String rawLine, int index) {
        rawLine = rawLine.trim();
        if (rawLine.isEmpty()) return null;

        rawLine = sanitizeJson(rawLine);

        Integer opcode = extractInt(rawLine, "opcode");
        String method = extractString(rawLine, "method");
        Integer addr = extractInt(rawLine, "addr");
        Long nano = extractLong(rawLine, "nano");
        String phase = extractString(rawLine, "phase");

        if (opcode == null) {
            opcode = extractFirstNumber(rawLine);
        }
        if (opcode == null) return null;

        int op = opcode;

        if (phase == null) {
            phase = phaseOf(op);
        }

        if (addr == null) addr = -1;
        if (nano == null) nano = System.nanoTime();
        if (method == null) method = "unknown";

        return "{\"seq\":" + index
            + ",\"nano\":" + nano
            + ",\"opcode\":" + op
            + ",\"phase\":\"" + phase + "\""
            + ",\"addr\":" + addr
            + ",\"method\":\"" + escapeJson(method) + "\"}";
    }

    private static String sanitizeJson(String s) {
        s = s.replaceAll(",\\s*([\\]}])", "$1");
        s = s.replace('\'', '"');
        return s;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static Integer extractInt(String json, String key) {
        String pattern = "[{,]\\s*\"" + key + "\"\\s*:\\s*(0x[0-9A-Fa-f]+|\\d+)";
        var m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            try { return Integer.decode(m.group(1)); } catch (Exception e) { }
        }
        return null;
    }

    private static Long extractLong(String json, String key) {
        String pattern = "[{,]\\s*\"" + key + "\"\\s*:\\s*(0x[0-9A-Fa-f]+|\\d+)";
        var m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            try { return Long.decode(m.group(1)); } catch (Exception e) { }
        }
        return null;
    }

    private static String extractString(String json, String key) {
        String pattern = "[{,]\\s*\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        var m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    private static Integer extractFirstNumber(String json) {
        var m = java.util.regex.Pattern.compile(":\\s*(\\d+)").matcher(json);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception e) { }
        }
        return null;
    }

    // ── In-process mode ─────────────────────────────────────────────────────────

    /**
     * Feed NDJSON directly into VmPointcutPublisher.publish() — no serialization round-trip.
     * Zero-copy path: file → JVM objects → RingSeries.
     */
    private static void runInProcess(Path inputPath, boolean verbose) throws Exception {
        if (verbose) {
            System.err.println("[VmPointcutEmitter] INPROCESS mode — feeding VmPointcutPublisher directly");
        }

        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;

        AtomicInteger count = new AtomicInteger(0);
        long start = System.nanoTime();

        AtomicInteger subCount = new AtomicInteger(0);
        int subId = VmPointcutPublisher.subscribe(evt -> subCount.incrementAndGet());

        try {
            try (var br = inputPath != null && Files.exists(inputPath)
                    ? Files.newBufferedReader(inputPath)
                    : new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                int idx = 0;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String normalized = normalizeLine(line, idx++);
                    if (normalized == null) continue;

                    Integer opcode = extractInt(normalized, "opcode");
                    String method = extractString(normalized, "method");
                    Integer addr = extractInt(normalized, "addr");

                    if (opcode != null) {
                        VmPointcutPublisher.publish(opcode, method != null ? method : "unknown", addr != null ? addr : -1);
                        count.incrementAndGet();
                    }
                }
            }

            if (verbose) {
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                System.err.println("[VmPointcutEmitter] published " + count.get() + " events in " + elapsed + "ms");
                System.err.println("[VmPointcutEmitter] subscriber received " + subCount.get());
            }

        } finally {
            VmPointcutPublisher.unsubscribe(subId);
            VmPointcutPublisher.active = false;
        }
    }

    // ── Args helper ────────────────────────────────────────────────────────────

    private static String findArg(String[] args, String... names) {
        for (int i = 0; i < args.length - 1; i++) {
            for (String n : names) {
                if (args[i].equals(n)) return args[i + 1];
            }
        }
        return null;
    }

    private static boolean hasArg(String[] args, String... names) {
        for (String a : args) {
            for (String n : names) {
                if (a.equals(n)) return true;
            }
        }
        return false;
    }
}
