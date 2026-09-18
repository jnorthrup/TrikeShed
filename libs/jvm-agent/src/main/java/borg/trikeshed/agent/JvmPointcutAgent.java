package borg.trikeshed.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM Pointcut Agent - Intercepts JVM bytecode operations and emits FieldSynapse points.
 * 
 * Intercepts:
 * - putfield (0xB5) -> L_SET (0xA6)
 * - getfield (0xB4) -> L_GET (0xA5)
 * - putstatic (0xB3) -> P_SET (0xA8)
 * - getstatic (0xB2) -> P_GET (0xA7)
 * 
 * Emits FieldSynapse wire protocol frames via callback.
 */
public class JvmPointcutAgent {

    // FieldSynapse opcodes (matching xvm wire protocol)
    public static final byte OP_L_GET = (byte) 0xA5;
    public static final byte OP_L_SET = (byte) 0xA6;
    public static final byte OP_P_GET = (byte) 0xA7;
    public static final byte OP_P_SET = (byte) 0xA8;
    public static final byte PHASE_BEFORE = 0;
    public static final byte PHASE_AFTER = 1;

    // JVM bytecode opcodes
    private static final int JVM_GETFIELD = 180;  // 0xB4
    private static final int JVM_PUTFIELD = 181;  // 0xB5
    private static final int JVM_GETSTATIC = 178; // 0xB2
    private static final int JVM_PUTSTATIC = 179; // 0xB3

    private static final AtomicLong sequenceCounter = new AtomicLong(0);
    private static final ConcurrentHashMap<String, Integer> methodIndexCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> templateIndexCache = new ConcurrentHashMap<>();

    // Callback for emitting FieldSynapse
    public interface PointcutEmitter {
        void emit(byte phase, byte opcode, int methodIdx, int addr, int seq, long nano, int callsiteHash, int templateIdx);
    }

    private static volatile PointcutEmitter emitter;
    private static volatile PrintStream logStream = System.err;

    /**
     * Entry point for Java agent (premain/agentmain)
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        logStream.println("[JvmPointcutAgent] Starting JVM pointcut agent");
        install(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        logStream.println("[JvmPointcutAgent] Attaching JVM pointcut agent");
        install(inst);
    }

    private static void install(Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] classfileBuffer) throws IllegalClassFormatException {
                
                // Skip system classes to avoid infinite recursion
                if (className.startsWith("java/") || 
                    className.startsWith("javax/") ||
                    className.startsWith("sun/") ||
                    className.startsWith("com/sun/") ||
                    className.startsWith("org/objectweb/asm/") ||
                    className.startsWith("borg/trikeshed/agent/")) {
                    return classfileBuffer;
                }

                try {
                    return transformClass(className, classfileBuffer);
                } catch (Exception e) {
                    logStream.println("[JvmPointcutAgent] Transform failed for " + className + ": " + e.getMessage());
                    return classfileBuffer;
                }
            }
        });

        logStream.println("[JvmPointcutAgent] ClassFileTransformer registered");
    }

    private static byte[] transformClass(String className, byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (mv != null && !"<init>".equals(name) && !"<clinit>".equals(name)) {
                    // Wrap with advice adapter to intercept field access
                    return new FieldAccessAdviceAdapter(Opcodes.ASM9, mv, access, name, desc, className);
                }
                return mv;
            }
        }, ClassReader.SKIP_DEBUG);

        return writer.toByteArray();
    }

    /**
     * AdviceAdapter that intercepts field access bytecodes
     */
    private static class FieldAccessAdviceAdapter extends AdviceAdapter {
        private final String className;
        private final String methodName;
        private final String methodDesc;
        private final int methodIdx;

        protected FieldAccessAdviceAdapter(int api, MethodVisitor mv, int access, String name, String desc, String className) {
            super(Opcodes.ASM9, mv, access, name, desc);
            this.className = className;
            this.methodName = name;
            this.methodDesc = desc;
            // Generate method index
            String key = className + "." + name + desc;
            this.methodIdx = methodIndexCache.computeIfAbsent(key, k -> methodIndexCache.size());
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            // BEFORE phase - emit before the field access
            if (emitter != null) {
                int seq = (int) sequenceCounter.incrementAndGet();
                String callsiteKey = owner + "." + name + desc;
                int callsiteHash = callsiteKey.hashCode();
                int templateIdx = templateIndexCache.computeIfAbsent(callsiteKey, k -> templateIndexCache.size());
                long nano = System.nanoTime();

                byte phase = PHASE_BEFORE;
                byte fsOpcode;
                
                // Map JVM opcode to FieldSynapse opcode
                if (opcode == JVM_GETFIELD) {
                    fsOpcode = OP_L_GET;
                } else if (opcode == JVM_PUTFIELD) {
                    fsOpcode = OP_L_SET;
                } else if (opcode == JVM_GETSTATIC) {
                    fsOpcode = OP_P_GET;
                } else if (opcode == JVM_PUTSTATIC) {
                    fsOpcode = OP_P_SET;
                } else {
                    // Not a field access we care about, just emit and continue
                    super.visitFieldInsn(opcode, owner, name, desc);
                    return;
                }

                // Emit BEFORE phase
                emitter.emit(phase, fsOpcode, methodIdx, owner.hashCode() + name.hashCode(), seq, System.nanoTime(), callsiteHash, templateIdx);

                // Emit the actual field instruction
                super.visitFieldInsn(opcode, owner, name, desc);

                // Emit AFTER phase
                phase = PHASE_AFTER;
                emitter.emit(phase, fsOpcode, methodIdx, owner.hashCode() + name.hashCode(), seq, System.nanoTime(), callsiteHash, templateIdx);
            } else {
                // No emitter, just emit the instruction normally
                super.visitFieldInsn(opcode, owner, name, desc);
            }
        }

        protected void onMethodEnter() {
            // Could emit method entry pointcut here
        }

        protected void onMethodExit(int opcode) {
            // Could emit method exit pointcut here
        }
    }

    /**
     * Set the emitter callback (called from host)
     */
    public static void setEmitter(PointcutEmitter e) {
        emitter = e;
        logStream.println("[JvmPointcutAgent] Emitter registered: " + (e != null));
    }

    /**
     * Get next sequence number
     */
    public static long nextSeq() {
        return sequenceCounter.incrementAndGet();
    }

    /**
     * Set log stream
     */
    public static void setLogStream(PrintStream ps) {
        logStream = ps;
    }
}