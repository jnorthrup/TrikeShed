package org.xvm.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TypedefResolutionPublisher — wires live XVM JVM pointcut events through
 * the cascade table for typedef resolution.
 *
 * Pipeline:
 *   ServiceContext.PointcutHook → TypedefCascadeTable.routeOpcode()
 *     → CascadeRollup.cascadeRollup() [every ROLLUP_INTERVAL events]
 *       → TypedefResolutionSeries.record() [WAL write]
 *
 * Registered as a ServiceContext.PointcutHook at class load.
 * Drains VmPointcutPublisher ring on subscription and on each subsequent publish.
 */
public final class TypedefResolutionPublisher {

    private TypedefResolutionPublisher() {}

    // ── Cascade components ────────────────────────────────────────────────

    private static final TypedefCascadeTable TABLE = new TypedefCascadeTable(2048);

    /** Cascade rollup result, written by rollup() and read by consumers. */
    private static volatile CascadeRollup.TierSnapshot[] CASCADE_SNAPSHOT;

    /** Rollup interval — trigger cascade rollup every N events. */
    private static final int ROLLUP_INTERVAL = 2048;

    /** Count of events since last rollup. */
    private static final AtomicLong EVENT_COUNT = new AtomicLong(0);

    /** Whether subscription has happened. */
    private static final AtomicBoolean SUBSCRIBED = new AtomicBoolean(false);

    // ── Wire to ServiceContext.PointcutHook ───────────────────────────────

    static {
        ServiceContext.pointcut = new ServiceContext.PointcutHook() {
            @Override
            public boolean active() {
                return true;
            }

            @Override
            public void publish(int opCode, String method, int addr) {
                TABLE.routeOpcode(opCode, method, addr);
                long count = EVENT_COUNT.incrementAndGet();
                if (count >= ROLLUP_INTERVAL) {
                    rollup();
                    EVENT_COUNT.set(0);
                }
            }

            @Override
            public void fieldPublish(int opCode, String method, int addr, boolean after) {
                // Field events are routed same as regular events
                TABLE.routeOpcode(opCode, method, addr);
                long count = EVENT_COUNT.incrementAndGet();
                if (count >= ROLLUP_INTERVAL) {
                    rollup();
                    EVENT_COUNT.set(0);
                }
            }
        };

        try {
            var dumpDir = System.getProperty("xvm.pointcut.dumpdir");
            if (dumpDir != null) {
                java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        drainFromRing();
                        rollup();
                        var path = java.nio.file.Path.of(dumpDir);
                        var lc = new XvmLifecycle();
                        lc.start();
                        var drain = new PointcutDrain(lc, TABLE, path);
                        drain.drain();
                        drain.shutdown();
                    } catch (Exception e) {
                        System.err.println("Failed to execute VM shutdown pointcut reification: " + e.getMessage());
                    }
                }));
            }
        } catch (Exception ignored) {
        }
    }

    // ── Cascade rollup ───────────────────────────────────────────────────

    /**
     * Run the full 5-tier cascade rollup on the current table state.
     * Thread-safe: multiple threads may call; rollup is idempotent.
     *
     * @return the 5-tier snapshot array
     */
    public static CascadeRollup.TierSnapshot[] rollup() {
        CascadeRollup.TierSnapshot[] snap = CascadeRollup.cascadeRollup(TABLE);
        CASCADE_SNAPSHOT = snap;
        return snap;
    }

    /**
     * Force a rollup regardless of event count.
     */
    public static CascadeRollup.TierSnapshot[] forceRollup() {
        EVENT_COUNT.set(0);
        return rollup();
    }

    // ── Query API ────────────────────────────────────────────────────────

    /** Current cascade snapshot, or null if no rollup has run. */
    public static CascadeRollup.TierSnapshot[] snapshot() {
        return CASCADE_SNAPSHOT;
    }

    /** Current row count in the cascade table. */
    public static int tableRowCount() {
        return TABLE.rowCount();
    }

    /** The backing cascade table for direct query. */
    public static TypedefCascadeTable table() {
        return TABLE;
    }

    /**
     * Drain the current pointcut ring into the cascade table.
     * Drains all events in the ring, then triggers a rollup.
     */
    public static void drainFromRing() {
        VmPointcutPublisher.drain((evt) -> {
            TABLE.fold(evt.opcode, evt.methodName(), evt.addr);
        });
    }

    /**
     * Subscribe to the pointcut ring. Drains all existing events,
     * then begins streaming new events into the cascade table.
     * Idempotent — subsequent calls are no-ops.
     */
    public static void subscribe() {
        if (!SUBSCRIBED.compareAndSet(false, true)) {
            return;
        }
        drainFromRing();
        rollup();
    }

    /** Drain then run rollup. */
    public static CascadeRollup.TierSnapshot[] drainAndRollup() {
        drainFromRing();
        return rollup();
    }
}
