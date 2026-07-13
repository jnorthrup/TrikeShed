package org.xvm.runtime;

import borg.trikeshed.lib.EvictionListener;
import borg.trikeshed.lib.RingSeries;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Synapse journal for field pointcut events (P_GET, P_SET, L_GET, L_SET).
 * Backed by borg.trikeshed.lib.RingSeries.
 */
public final class FieldSynapse {

    private static final int RING_CAP = 2048;
    public static final int RECORD_SIZE = 24;
    public static final int SLAB_SIZE = RING_CAP;

    private static int TPL_BEFORE_GET;
    private static int TPL_AFTER_GET;
    private static int TPL_BEFORE_SET;
    private static int TPL_AFTER_SET;

    private static int OP_L_GET;
    private static int OP_L_SET;
    private static int OP_P_GET;
    private static int OP_P_SET;

    private static void initTemplates() {
        TPL_BEFORE_GET = VmPointcutPublisher.pool().intern("BEFORE %s.%s @ %d");
        TPL_AFTER_GET  = VmPointcutPublisher.pool().intern("AFTER  %s.%s @ %d →");
        TPL_BEFORE_SET = VmPointcutPublisher.pool().intern("BEFORE %s.%s @ %d =");
        TPL_AFTER_SET  = VmPointcutPublisher.pool().intern("AFTER  %s.%s @ %d ←");

        OP_L_GET = VmPointcutPublisher.pool().intern("L_GET");
        OP_L_SET = VmPointcutPublisher.pool().intern("L_SET");
        OP_P_GET = VmPointcutPublisher.pool().intern("P_GET");
        OP_P_SET = VmPointcutPublisher.pool().intern("P_SET");
    }

    static {
        initTemplates();
    }

    private static int opcodePoolIdx(int opcode) {
        return switch (opcode & 0xFF) {
            case 0xA5 -> OP_L_GET;
            case 0xA6 -> OP_L_SET;
            case 0xA7 -> OP_P_GET;
            case 0xA8 -> OP_P_SET;
            default -> VmPointcutPublisher.pool().intern("OP_0x" + Integer.toHexString(opcode & 0xFF));
        };
    }

    private static final RingSeries<FieldSynapse> RING =
            new RingSeries<>(RING_CAP, (EvictionListener<FieldSynapse>) evt -> {});

    private static final java.util.ArrayList<FieldSynapse> EVENTS = new java.util.ArrayList<>(RING_CAP);

    private static final AtomicInteger SEQ = new AtomicInteger();
    public static volatile boolean active = false;
    private static long slabEpoch = 0;

    public final byte phase;
    public final byte opcode;
    public final int  methodIdx;
    public final int  addr;
    public final int  seq;
    public final long nano;
    public final int  callsiteHash;
    public final int  templateIdx;

    private FieldSynapse(byte phase, byte opcode, int methodIdx, int addr,
                          int seq, long nano, int callsiteHash, int templateIdx) {
        this.phase = phase;
        this.opcode = opcode;
        this.methodIdx = methodIdx;
        this.addr = addr;
        this.seq = seq;
        this.nano = nano;
        this.callsiteHash = callsiteHash;
        this.templateIdx = templateIdx;
    }

    public static int callsiteHash(int opcode, int methodIdx, int addr) {
        int h = 0x811c9dc5;
        h ^= opcode;          h *= 0x01000193;
        h ^= methodIdx;       h *= 0x01000193;
        h ^= (addr & 0xFF);   h *= 0x01000193;
        h ^= (addr >>> 8);    h *= 0x01000193;
        h ^= (addr >>> 16);   h *= 0x01000193;
        h ^= (addr >>> 24);   h *= 0x01000193;
        return h & 0xFFFF;
    }

    public static void publishStatic(int opcode, String method, int addr, boolean isAfter) {
        if (!active) return;

        int methodIdx = VmPointcutPublisher.pool().intern(method);
        boolean isSet = (opcode & 0xFF) == 0xA6 || (opcode & 0xFF) == 0xA8;

        int tplIdx = !isAfter ? (isSet ? TPL_BEFORE_SET : TPL_BEFORE_GET)
                              : (isSet ? TPL_AFTER_SET : TPL_AFTER_GET);

        int csh = callsiteHash(opcode, methodIdx, addr);
        long nano = System.nanoTime();

        FieldSynapse evt = new FieldSynapse(
                (byte) (isAfter ? 1 : 0),
                (byte) opcode,
                methodIdx,
                addr,
                SEQ.getAndIncrement(),
                nano,
                csh,
                tplIdx
        );

        synchronized (EVENTS) {
            RING.add(evt);
            EVENTS.add(evt);
        }

        if (RING.getA() == SLAB_SIZE) {
            flush("fire");
        }
    }

    public static void flush(String reason) {
        int count;
        synchronized (EVENTS) {
            count = RING.getA();
            if (count == 0) return;
            RING.clear();
        }

        long epoch = slabEpoch++;
        PointcutObservation.publish(PointcutObservation.Source.FIELD, count, epoch);
    }

    public static void timeoutFlush() {
        int count;
        synchronized (EVENTS) {
            count = RING.getA();
        }
        if (count > 0 && count < SLAB_SIZE) {
            flush("timeout");
        }
    }

    public static int size() {
        synchronized (EVENTS) {
            return EVENTS.size();
        }
    }

    public static FieldSynapse peek(int i) {
        synchronized (EVENTS) {
            return EVENTS.get(i);
        }
    }

    public static void drain(Consumer<FieldSynapse> consumer) {
        int sz;
        synchronized (EVENTS) {
            sz = EVENTS.size();
            for (int i = 0; i < sz; i++) {
                consumer.accept(EVENTS.get(i));
            }
        }
    }

    public String reify() {
        String template = VmPointcutPublisher.pool().resolve(templateIdx);
        String opName = VmPointcutPublisher.pool().resolve(opcodePoolIdx(opcode));
        String methodName = VmPointcutPublisher.pool().resolve(methodIdx);
        return String.format(template, opName, methodName, addr);
    }

    public String opcodeName() { return VmPointcutPublisher.pool().resolve(opcodePoolIdx(opcode)); }
    public String methodName() { return VmPointcutPublisher.pool().resolve(methodIdx); }
    public String phaseLabel() { return phase == 0 ? "BEFORE" : "AFTER"; }
    public boolean isSet() { return (opcode & 0xFF) == 0xA6 || (opcode & 0xFF) == 0xA8; }

    public static int wireprotoLength() { return size() * RECORD_SIZE; }

    public static void writeRecord(ByteBuffer target, int index) {
        writeRecord(target, peek(index));
    }

    private static void writeRecord(ByteBuffer target, FieldSynapse evt) {
        target.put(evt.opcode);
        target.put(evt.phase);
        target.putShort((short) evt.methodIdx);
        target.putInt(evt.addr);
        target.putInt(evt.seq);
        target.putLong(evt.nano);
        target.putShort((short) evt.callsiteHash);
        target.putShort((short) evt.templateIdx);
    }

    public static ByteBuffer drainToWireproto() {
        int sz;
        synchronized (EVENTS) {
            sz = RING.getA();
        }
        ByteBuffer buf = ByteBuffer.allocate(sz * RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sz; i++) {
            FieldSynapse evt;
            synchronized (EVENTS) {
                evt = RING.getB().invoke(i);
            }
            writeRecord(buf, evt);
        }
        buf.flip();
        return buf;
    }

    public static FieldSynapse fromWireproto(ByteBuffer buf) {
        byte opcode = buf.get();
        byte phase = buf.get();
        int methodIdx = buf.getShort() & 0xFFFF;
        int addr = buf.getInt();
        int seq = buf.getInt();
        long nano = buf.getLong();
        int callsiteHash = buf.getShort() & 0xFFFF;
        int templateIdx = buf.getShort() & 0xFFFF;
        return new FieldSynapse(phase, opcode, methodIdx, addr, seq, nano, callsiteHash, templateIdx);
    }

    public static void startTimer(long intervalMs) {
    }

    public static void stopTimer() {
    }

    public static void reset() {
        active = false;
        synchronized (EVENTS) {
            EVENTS.clear();
            RING.clear();
        }
        SEQ.set(0);
        slabEpoch = 0;
        initTemplates();
    }
}