package borg.trikeshed.userspace.containment

import borg.trikeshed.userspace.UringOp

/**
 * Legion Doc 04 Layer 3 — Kernel-Level Syscall Governance.
 *
 * Two distinct syscall surfaces are governed:
 *
 * 1. io_uring ops (UringOp) — what [FunctionalUringFacade] enqueues.
 *    These are checked at enqueue time before reaching the kernel.
 *
 * 2. Raw Linux syscalls — what seccomp-BPF filters at the kernel boundary.
 *    These are NOT io_uring ops; they're the traditional syscall interface
 *    (ptrace, bpf, mount, etc.) that an agent could invoke via execve or
 *    direct libc calls. eBPF probes attach to sys_enter_* tracepoints.
 *
 * On JVM/other platforms, both layers are enforced in userspace by the
 * facade, providing defense-in-depth without kernel eBPF support.
 */
object SyscallGuard {

    // ── Surface 1: io_uring op governance ──

    /**
     * io_uring ops that are quarantined (held for audit) under containment.
     * Doc 04 §3: "Hard-block any non-loopback network egress."
     *
     * Note: SOCKET, FUTEX_WAIT, FUTEX_WAKE, WAITID exist as UringOp enum
     * entries but are NOT in the repo's kernel IORING_OP_* header (pre-5.18).
     * They are still defined so the facade can reject them at enqueue time
     * before the kernel ever sees them.
     */
    val QUARANTINED_URING_OPS: Set<UringOp> = setOf(
        UringOp.SOCKET,
    )

    /**
     * Evaluate a UringOp against io_uring governance policy.
     */
    fun evaluateUringOp(op: UringOp, fd: Int, addr: Long, len: Int): UringVerdict {
        if (op in QUARANTINED_URING_OPS) return UringVerdict.QUARANTINE("op $op quarantined by containment policy")
        return UringVerdict.ALLOW
    }

    // ── Surface 2: raw Linux syscall governance (seccomp-BPF) ──

    /**
     * seccomp-BPF strict profile — raw syscalls that are NEVER allowed.
     * Doc 04 §3: "Proactively reject calls used for covert signaling
     * or sandbox inspection."
     *
     * These are NOT io_uring ops. They are the traditional Linux syscall
     * numbers filtered by seccomp-BPF at the kernel boundary.
     */
    val BLOCKED_SYSCALLS: Set<RawSyscall> = setOf(
        RawSyscall.PTRACE,        // sandbox inspection
        RawSyscall.BPF,           // direct bpf(2) — could load rogue programs
        RawSyscall.USERFAULTFD,   // user-space page fault handling — covert channel
        RawSyscall.CLONE3,        // raw namespace flags
        RawSyscall.MOUNT,         // filesystem mount — breakout
        RawSyscall.PIVOT_ROOT,    // root filesystem swap — breakout
        RawSyscall.PERF_EVENT_OPEN, // performance monitoring — side-channel
        RawSyscall.KEXEC_LOAD,    // kernel replacement
    )

    /**
     * Check if a raw syscall is blocked by the seccomp profile.
     */
    fun isSyscallBlocked(syscall: RawSyscall): Boolean = syscall in BLOCKED_SYSCALLS

    /**
     * Check if a raw syscall name is blocked.
     */
    fun isSyscallBlocked(name: String): Boolean =
        RawSyscall.entries.any { it.name.equals(name, ignoreCase = true) && it in BLOCKED_SYSCALLS }

    // ── Network egress policy ──

    /**
     * Doc 04 §3: "Hard-block any non-loopback network egress unless
     * directed to a strictly monitored proxy."
     */
    val EGRESS_POLICY: EgressPolicy = EgressPolicy(
        allowedHosts = setOf("127.0.0.1", "::1"),
        allowedPorts = emptySet(),
        blockAllNonLoopback = true,
        proxyRequired = false,
    )

    // ── execve whitelist ──

    /**
     * Doc 04 §3: "Block invocation of binaries not present in the
     * pre-compiled benchmark manifest."
     * Empty set = no execve allowed at all (strictest).
     */
    val EXEC_WHITELIST: Set<String> = emptySet()

    fun isExecAllowed(path: String): Boolean =
        path in EXEC_WHITELIST
}

/**
 * Raw Linux syscalls governed by seccomp-BPF.
 * These are NOT io_uring ops — they are the traditional syscall interface.
 */
enum class RawSyscall {
    PTRACE, BPF, USERFAULTFD, CLONE3, MOUNT, PIVOT_ROOT,
    PERF_EVENT_OPEN, KEXEC_LOAD,
    SOCKET, CONNECT, EXECVE,
}

/** Verdict for io_uring op governance. */
sealed class UringVerdict {
    object ALLOW : UringVerdict()
    class QUARANTINE(val reason: String) : UringVerdict()
}

/** Network egress policy (Doc 04 §3). */
data class EgressPolicy(
    val allowedHosts: Set<String>,
    val allowedPorts: Set<Int>,
    val blockAllNonLoopback: Boolean,
    val proxyRequired: Boolean,
)
