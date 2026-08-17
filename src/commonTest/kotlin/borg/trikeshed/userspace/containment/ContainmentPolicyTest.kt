package borg.trikeshed.userspace.containment

import borg.trikeshed.userspace.UringOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainmentPolicyTest {

    // ── Layer 2: xattr rejection (all 8 ops) ──

    @Test
    fun maximum_policy_rejects_all_eight_xattr_ops() {
        val rejected = ContainmentPolicy.MAXIMUM.layer2Metadata.rejectedXattrOps
        assertTrue(UringOp.FSETXATTR in rejected)
        assertTrue(UringOp.SETXATTR in rejected)
        assertTrue(UringOp.FGETXATTR in rejected)
        assertTrue(UringOp.GETXATTR in rejected)
        assertTrue(UringOp.FLISTXATTR in rejected)
        assertTrue(UringOp.LISTXATTR in rejected)
        assertTrue(UringOp.FREMOVEXATTR in rejected)
        assertTrue(UringOp.REMOVEXATTR in rejected)
    }

    @Test
    fun open_policy_rejects_nothing() {
        val rejected = ContainmentPolicy.OPEN.layer2Metadata.rejectedXattrOps
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun all_xattr_ops_exist_in_uring_enum() {
        val xattrOps = UringOp.entries.filter { it.name.contains("XATTR") }
        assertEquals(8, xattrOps.size)
    }

    // ── Layer 2: timestamp quantization ──

    @Test
    fun maximum_policy_quantizes_timestamps_to_epoch_zero() {
        val policy = ContainmentPolicy.MAXIMUM.layer2Metadata
        assertTrue(policy.quantizeTimestamps)
        assertEquals(0L, policy.syntheticEpoch)
        assertEquals(3600L, policy.timestampQuantumSeconds)
    }

    @Test
    fun open_policy_does_not_quantize_timestamps() {
        val policy = ContainmentPolicy.OPEN.layer2Metadata
        assertFalse(policy.quantizeTimestamps)
    }

    // ── Layer 3: syscall guard — seccomp raw syscalls ──

    @Test
    fun syscall_guard_blocks_seccomp_profile() {
        assertTrue(SyscallGuard.isSyscallBlocked(RawSyscall.PTRACE))
        assertTrue(SyscallGuard.isSyscallBlocked(RawSyscall.BPF))
        assertTrue(SyscallGuard.isSyscallBlocked(RawSyscall.USERFAULTFD))
        assertTrue(SyscallGuard.isSyscallBlocked(RawSyscall.CLONE3))
        assertTrue(SyscallGuard.isSyscallBlocked(RawSyscall.MOUNT))
        assertTrue(SyscallGuard.isSyscallBlocked(RawSyscall.PIVOT_ROOT))
    }

    @Test
    fun syscall_guard_blocks_by_name() {
        assertTrue(SyscallGuard.isSyscallBlocked("ptrace"))
        assertTrue(SyscallGuard.isSyscallBlocked("bpf"))
        assertTrue(SyscallGuard.isSyscallBlocked("userfaultfd"))
    }

    @Test
    fun syscall_guard_does_not_block_read_write() {
        assertFalse(SyscallGuard.isSyscallBlocked("read"))
        assertFalse(SyscallGuard.isSyscallBlocked("write"))
        assertFalse(SyscallGuard.isSyscallBlocked("openat"))
    }

    // ── Layer 3: io_uring op governance ──

    @Test
    fun syscall_guard_quarantines_socket_creation() {
        val verdict = SyscallGuard.evaluateUringOp(UringOp.SOCKET, fd = -1, addr = 0, len = 0)
        assertTrue(verdict is UringVerdict.QUARANTINE)
    }

    @Test
    fun syscall_guard_allows_read_write() {
        assertTrue(SyscallGuard.evaluateUringOp(UringOp.READ, fd = 3, addr = 0, len = 1024) is UringVerdict.ALLOW)
        assertTrue(SyscallGuard.evaluateUringOp(UringOp.WRITE, fd = 3, addr = 0, len = 1024) is UringVerdict.ALLOW)
    }

    @Test
    fun syscall_guard_allows_statx() {
        assertTrue(SyscallGuard.evaluateUringOp(UringOp.STATX, fd = 3, addr = 0, len = 256) is UringVerdict.ALLOW)
    }

    // ── Layer 3: execve whitelist ──

    @Test
    fun syscall_guard_blocks_all_execve_with_empty_whitelist() {
        assertFalse(SyscallGuard.isExecAllowed("/bin/sh"))
        assertFalse(SyscallGuard.isExecAllowed("/usr/bin/python3"))
    }

    // ── Layer 3: egress policy ──

    @Test
    fun egress_policy_blocks_all_non_loopback() {
        val policy = SyscallGuard.EGRESS_POLICY
        assertTrue(policy.blockAllNonLoopback)
        assertTrue("127.0.0.1" in policy.allowedHosts)
        assertTrue("::1" in policy.allowedHosts)
    }

    // ── Layer 1: filesystem virtualization ──

    @Test
    fun maximum_policy_enables_filesystem_virtualization() {
        val policy = ContainmentPolicy.MAXIMUM.layer1Filesystem
        assertTrue(policy.namespaceSanitization)
        assertTrue(policy.copyOnWrite)
    }

    // ── Layer 4: artifact normalization ──

    @Test
    fun maximum_policy_enables_artifact_normalization() {
        val policy = ContainmentPolicy.MAXIMUM.layer4Artifact
        assertTrue(policy.astEnforcement)
        assertTrue(policy.stripAuthorMetadata)
        assertTrue(policy.deterministicFormatter)
    }

    // ── Layer 5: arbitrage breaker ──

    @Test
    fun maximum_policy_enables_arbitrage_breaker() {
        val policy = ContainmentPolicy.MAXIMUM.layer5Arbitrage
        assertTrue(policy.noiseInjection)
        assertTrue(policy.behavioralGraphAudit)
    }

    // ── Full policy structure ──

    @Test
    fun maximum_policy_has_all_five_layers() {
        val policy = ContainmentPolicy.MAXIMUM
        assertTrue(policy.layer1Filesystem.namespaceSanitization)
        assertTrue(policy.layer2Metadata.rejectedXattrOps.isNotEmpty())
        assertTrue(policy.layer3Syscall.blockedSyscalls.isNotEmpty())
        assertTrue(policy.layer4Artifact.astEnforcement)
        assertTrue(policy.layer5Arbitrage.noiseInjection)
    }
}
