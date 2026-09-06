package borg.trikeshed.parse;

import borg.trikeshed.lib.CharSeries;
import borg.trikeshed.parse.json.JsonSupport;
import java.lang.management.ManagementFactory;

/** Same-process thread allocation counter; run unchanged against before/after classpaths. */
public class CharSeriesAllocationProbe {
    private static volatile Object result;
    private static volatile long checksum;

    public static void main(String[] args) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        var chars = new CharSeries("x".repeat(4096));
        for (int i = 0; i < 16; i++) chars = chars.pos(1).lim(chars.length() - 1).getSlice();
        final var window = chars;
        String leaf = "{\"name\":\"" + "a".repeat(512) + "\",\"value\":[1,2,3,4]}";
        String json = "[" + String.join(",", java.util.Collections.nCopies(16, leaf)) + "]";
        measure(bean, "nested-char-window", 300, () -> {
            long sum = 0;
            for (int i = 0; i < window.length(); i++) sum += window.charAt(i);
            checksum = sum;
        });
        if (checksum != 4064L * 'x') throw new AssertionError("Window contents changed");
        measure(bean, "json-reify", 150, () -> {
            result = JsonSupport.INSTANCE.parse(json);
            checksum = consume(result);
        });
        if (checksum != 16L * (512 + 10)) throw new AssertionError("JSON contents changed: " + checksum);
    }

    private static long consume(Object value) {
        if (value instanceof String s) return s.length();
        if (value instanceof Number n) return n.longValue();
        long sum = 0;
        if (value instanceof java.util.Map<?, ?> m) for (Object item : m.values()) sum += consume(item);
        else if (value instanceof Iterable<?> items) for (Object item : items) sum += consume(item);
        return sum;
    }

    private static void measure(com.sun.management.ThreadMXBean bean, String name, int iterations, Runnable body) {
        for (int i = 0; i < iterations; i++) body.run();
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread), started = System.nanoTime();
        for (int i = 0; i < iterations; i++) body.run();
        long elapsed = System.nanoTime() - started, bytes = bean.getThreadAllocatedBytes(thread) - before;
        System.out.printf("%s iterations=%d bytes=%d bytes/op=%.1f ns/op=%.1f%n",
            name, iterations, bytes, (double) bytes / iterations, (double) elapsed / iterations);
    }
}
