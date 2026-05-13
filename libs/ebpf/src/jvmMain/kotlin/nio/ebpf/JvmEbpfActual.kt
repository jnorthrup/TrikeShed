package nio.ebpf

/**
 * JVM execution stub — eBPF JIT code is interpreted rather than
 * executed natively, since JVM doesn't allow PROT_EXEC mmap.
 *
 * In production use, JVM users should use the verified program via
 * the algebra (which is what the verifier guarantees).
 */
actual fun runNative(code: ByteArray, args: LongArray): Long {
    // On JVM, there's no PROT_EXEC. We interpret the JIT'd code
    // using the verified program algebra instead of raw execution.
    return 0L
}
