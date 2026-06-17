package org.xvm.runtime;

import borg.trikeshed.lib.EvictionListener;
import borg.trikeshed.lib.RingSeries;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * CRUdux event publisher for VM pointcut journal.
 * Backed by borg.trikeshed.lib.RingSeries (TrikeShed).
 */
public final class VmPointcutPublisher {
    static {
        try {
            ServiceContext.pointcut = new ServiceContext.PointcutHook() {
                @Override public boolean active() { return VmPointcutPublisher.active; }
                @Override public void publish(int opcode, String method, int addr) { VmPointcutPublisher.publish(opcode, method, addr); }
                @Override public void fieldPublish(int opcode, String method, int addr, boolean after) { FieldSynapse.publishStatic(opcode, method, addr, after); }
            };
        } catch (NoClassDefFoundError ignored) {
        }
    }

    private static final int CAP = 65536;
    public static final int RECORD_SIZE = 20;
    private static final EvictionListener<PointcutEvent> NO_OP = evt -> {};
    private static final RingSeries<PointcutEvent> RING = new RingSeries<>(CAP, NO_OP);
    private static final ArrayList<PointcutEvent> EVENTS = new ArrayList<>(CAP);
    private static final long[] JOURNAL = new long[CAP];
    private static volatile long version = 0L;
    private static final AtomicInteger SEQ = new AtomicInteger();
    public static volatile boolean active = false;
    private static final AtomicLong TOTAL_INVOKED = new AtomicLong();
    private static final AtomicInteger SUB_SEQ = new AtomicInteger();
    private static final ConcurrentHashMap<Integer, Consumer<PointcutEvent>> SUBS = new ConcurrentHashMap<>();
    private static final InternPool POOL = new InternPool();

    public static long versionStamp() { return version; }
    public static long totalInvoked() { return TOTAL_INVOKED.get(); }

    public static final class InternPool {
        private final String[] table = new String[65536];
        private final HashMap<String, Integer> index = new HashMap<>();
        private int next = 0;

        public synchronized int intern(String s) {
            return index.computeIfAbsent(s, k -> {
                var idx = next++;
                table[idx] = k;
                return idx;
            });
        }

        public synchronized String resolve(int idx) {
            return table[idx];
        }

        public synchronized void reset() {
            index.clear();
            for (var i = 0; i < next; i++) {
                table[i] = null;
            }
            next = 0;
        }

        public synchronized byte[] toBytes() {
            var totalUtf8 = 0;
            for (var i = 0; i < next; i++) {
                totalUtf8 += table[i].getBytes(StandardCharsets.UTF_8).length;
            }
            var buf = ByteBuffer.allocate(2 + next * 4 + totalUtf8).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) next);
            for (var i = 0; i < next; i++) {
                var utf8 = table[i].getBytes(StandardCharsets.UTF_8);
                buf.putShort((short) i);
                buf.putShort((short) utf8.length);
                buf.put(utf8);
            }
            var result = new byte[buf.position()];
            buf.flip();
            buf.get(result);
            return result;
        }
    }

    public static InternPool pool() {
        return POOL;
    }

    public static void publish(int opcode, String method, int addr) {
        TOTAL_INVOKED.incrementAndGet();
        if (!active) {
            return;
        }
        var event = new PointcutEvent(
                SEQ.getAndIncrement(),
                System.nanoTime(),
                opcode,
                addr,
                POOL.intern(method)
        );
        synchronized (EVENTS) {
            RING.add(event);
            EVENTS.add(event);
        }
    }

    public static int size() {
        synchronized (EVENTS) {
            return EVENTS.size();
        }
    }

    public static PointcutEvent peek(int i) {
        synchronized (EVENTS) {
            return EVENTS.get(i);
        }
    }

    public static void revise(int index, PointcutEvent evt) {
        synchronized (EVENTS) {
            var old = EVENTS.get(index);
            if (index < JOURNAL.length) {
                JOURNAL[index] = old.nano;
            }
            var revised = new PointcutEvent(evt.seq, System.nanoTime(), evt.opcode, evt.addr, evt.methodIdx);
            EVENTS.set(index, revised);
            var retainedStart = EVENTS.size() - RING.getA();
            var ringIndex = index - retainedStart;
            if (ringIndex >= 0 && ringIndex < RING.getA()) {
                RING.set(ringIndex, revised);
            }
            version = System.nanoTime();
            notify(revised);
        }
    }

    public static void drain(Consumer<PointcutEvent> consumer) {
        int sz;
        synchronized (EVENTS) {
            sz = EVENTS.size();
            for (var i = 0; i < sz; i++) {
                consumer.accept(EVENTS.get(i));
            }
        }
        PointcutObservation.publish(PointcutObservation.Source.VM, sz, 0L);
    }

    public static void drainOpcodes(java.util.function.IntConsumer opcodeSink) {
        synchronized (EVENTS) {
            for (var i = 0; i < EVENTS.size(); i++) {
                var evt = EVENTS.get(i);
                opcodeSink.accept(evt.opcode);
            }
        }
    }

    public static void drainAll(java.util.function.ObjIntConsumer<String> sink) {
        synchronized (EVENTS) {
            for (var i = 0; i < EVENTS.size(); i++) {
                var evt = EVENTS.get(i);
                sink.accept(evt.methodName(), evt.opcode);
            }
        }
    }

    public static int wireprotoLength() {
        return size() * RECORD_SIZE;
    }

    public static void writeRecord(ByteBuffer target, int index) {
        var evt = peek(index);
        writeRecord(target, evt);
    }

    public static void writeRecord(ByteBuffer target, PointcutEvent evt) {
        target.put((byte) evt.opcode);
        target.put((byte) 0);
        target.putShort((short) evt.methodIdx);
        target.putInt(evt.addr);
        target.putInt(evt.seq);
        target.putLong(evt.nano);
    }

    public static ByteBuffer drainToWireproto() {
        var sz = size();
        var buf = ByteBuffer.allocate(sz * RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < sz; i++) {
            writeRecord(buf, i);
        }
        buf.flip();
        return buf;
    }

    public static PointcutEvent fromWireproto(ByteBuffer buf) {
        var opcode = buf.get() & 0xFF;
        buf.get();
        var methodIdx = buf.getShort() & 0xFFFF;
        var addr = buf.getInt();
        var seq = buf.getInt();
        var nano = buf.getLong();
        return new PointcutEvent(seq, nano, opcode, addr, methodIdx);
    }

    public static RingView ring() { return new RingView(); }

    public static final class RingView {
        public int head() { return 0; }
        public int cap()  { return CAP; }
        public int size() { return RING.getA(); }
    }

    public static JournalView journal() { return new JournalView(); }

    public static final class JournalView {
        public long oldNanoAt(int index) { return JOURNAL[index]; }
    }

    public static int subscribe(Consumer<PointcutEvent> fn) {
        var id = SUB_SEQ.getAndIncrement();
        SUBS.put(id, fn);
        return id;
    }

    public static void unsubscribe(int id) { SUBS.remove(id); }

    public static void reset() {
        active = false;
        version = 0L;
        TOTAL_INVOKED.set(0);
        synchronized (EVENTS) {
            EVENTS.clear();
            RING.clear();
        }
        SEQ.set(0);
        SUB_SEQ.set(0);
        POOL.reset();
        for (var i = 0; i < CAP; i++) {
            JOURNAL[i] = 0L;
        }
    }

    private static void notify(PointcutEvent evt) {
        SUBS.values().forEach(fn -> fn.accept(evt));
    }

    public static final class PointcutEvent {
        public final int seq;
        public final long nano;
        public final int opcode;
        public final int addr;
        public final int methodIdx;

        public PointcutEvent(int seq, long nano, int opcode, int addr, String method) {
            this(seq, nano, opcode, addr, POOL.intern(method));
        }

        public PointcutEvent(int seq, long nano, int opcode, int addr, int methodIdx) {
            this.seq = seq;
            this.nano = nano;
            this.opcode = opcode;
            this.addr = addr;
            this.methodIdx = methodIdx;
        }

        public String methodName() {
            return POOL.resolve(methodIdx);
        }

        public String opcodeName() {
            switch (opcode) {
                case 0x10: return "CALL_00"; case 0x11: return "CALL_01"; case 0x12: return "CALL_0N"; case 0x13: return "CALL_0T";
                case 0x14: return "CALL_10"; case 0x15: return "CALL_11"; case 0x16: return "CALL_1N"; case 0x17: return "CALL_1T";
                case 0x18: return "CALL_N0"; case 0x19: return "CALL_N1"; case 0x1A: return "CALL_NN"; case 0x1B: return "CALL_NT";
                case 0x1C: return "CALL_T0"; case 0x1D: return "CALL_T1"; case 0x1E: return "CALL_TN"; case 0x1F: return "CALL_TT";
                case 0x20: return "NVOK_00"; case 0x21: return "NVOK_01"; case 0x22: return "NVOK_0N"; case 0x23: return "NVOK_0T";
                case 0x24: return "NVOK_10"; case 0x25: return "NVOK_11"; case 0x26: return "NVOK_1N"; case 0x27: return "NVOK_1T";
                case 0x28: return "NVOK_N0"; case 0x29: return "NVOK_N1"; case 0x2A: return "NVOK_NN"; case 0x2B: return "NVOK_NT";
                case 0x2C: return "NVOK_T0"; case 0x2D: return "NVOK_T1"; case 0x2E: return "NVOK_TN"; case 0x2F: return "NVOK_TT";
                case 0x33: return "SYN_INIT";
                case 0x34: return "CONSTR_0"; case 0x35: return "CONSTR_1"; case 0x36: return "CONSTR_N"; case 0x37: return "CONSTR_T";
                case 0x38: return "NEW_0";   case 0x39: return "NEW_1";   case 0x3A: return "NEW_N";   case 0x3B: return "NEW_T";
                case 0x40: return "NEWC_0";  case 0x41: return "NEWC_1";  case 0x42: return "NEWC_N";  case 0x43: return "NEWC_T";
                case 0x48: return "NEWV_0";  case 0x49: return "NEWV_1";  case 0x4A: return "NEWV_N";  case 0x4B: return "NEWV_T";
                case 0x4C: return "RETURN_0"; case 0x4D: return "RETURN_1"; case 0x4E: return "RETURN_N"; case 0x4F: return "RETURN_T";
                case 0x65: return "MOV_TYPE"; case 0x66: return "CAST";
                case 0x77: return "LOOP";     case 0x78: return "LOOP_END";
                case 0x79: return "JMP";      case 0x7A: return "JMP_TRUE";  case 0x7B: return "JMP_FALSE";
                case 0x90: return "ASSERT";   case 0x91: return "ASSERT_M";  case 0x92: return "ASSERT_V";
                case 0xA5: return "L_GET";    case 0xA6: return "L_SET";
                case 0xA7: return "P_GET";    case 0xA8: return "P_SET";
                default: return "OP_0x" + Integer.toHexString(opcode);
            }
        }

        @Override public String toString() {
            return "PointcutEvent{seq=" + seq + ", opcode=" + opcodeName() +
                    "(0x" + Integer.toHexString(opcode) + "), addr=" + addr +
                    ", nano=" + nano +
                    ", method=" + methodName() + '}';
        }
    }
}