package borg.trikeshed.userspace.containment

import borg.trikeshed.userspace.UringOp

/**
 * Legion Containment Policy — encodes the 5 layers from
 * legion-modelling/04_posix_facade_tooling_minimums.txt as
 * configurable, declarative rules.
 *
 * Each layer maps to a data class that the [FunctionalUringFacade]
 * consults before submitting ops to the kernel. The policy is
 * injected at facade construction, not hardcoded.
 */
data class ContainmentPolicy(
    val layer1Filesystem: Layer1FilesystemPolicy = Layer1FilesystemPolicy(),
    val layer2Metadata: Layer2MetadataPolicy = Layer2MetadataPolicy(),
    val layer3Syscall: Layer3SyscallPolicy = Layer3SyscallPolicy(),
    val layer4Artifact: Layer4ArtifactPolicy = Layer4ArtifactPolicy(),
    val layer5Arbitrage: Layer5ArbitragePolicy = Layer5ArbitragePolicy(),
) {
    companion object {
        /** Maximum containment — all layers at their strictest. */
        val MAXIMUM = ContainmentPolicy(
            layer1Filesystem = Layer1FilesystemPolicy(namespaceSanitization = true, copyOnWrite = true),
            layer2Metadata = Layer2MetadataPolicy(
                rejectedXattrOps = UringOp.entries.filter { it.name.contains("XATTR") }.toSet(),
                quantizeTimestamps = true,
                timestampQuantumSeconds = 3600L,
                syntheticEpoch = 0L,
                virtualizeInodes = true,
            ),
            layer3Syscall = Layer3SyscallPolicy(
                blockedSyscalls = SyscallGuard.BLOCKED_SYSCALLS.map { it.name }.toSet(),
                allowedEgress = SyscallGuard.EGRESS_POLICY.allowedHosts,
                execWhitelist = SyscallGuard.EXEC_WHITELIST,
            ),
            layer4Artifact = Layer4ArtifactPolicy(
                astEnforcement = true,
                stripAuthorMetadata = true,
                deterministicFormatter = true,
            ),
            layer5Arbitrage = Layer5ArbitragePolicy(
                noiseInjection = true,
                behavioralGraphAudit = true,
            ),
        )

        /** Open development — no containment, for trusted local work. */
        val OPEN = ContainmentPolicy()
    }
}

// ── Layer 1: Epistemic Filesystem Virtualization (FUSE) ──

data class Layer1FilesystemPolicy(
    /** Doc 04 §1: map arbitrary semantic names to deterministic content hashes. */
    val namespaceSanitization: Boolean = false,
    /** Doc 04 §1: every agent instance runs in an ephemeral CoW branch. */
    val copyOnWrite: Boolean = false,
)

// ── Layer 2: Temporal & Metadata Quantization ──

data class Layer2MetadataPolicy(
    /** Doc 04 §2: ops deterministically rejected (all xattr variants). */
    val rejectedXattrOps: Set<UringOp> = emptySet(),
    /** Doc 04 §2: quantize mtime/ctime/atime to coarse boundaries. */
    val quantizeTimestamps: Boolean = false,
    /** Quantization boundary in seconds (default: 1 hour). */
    val timestampQuantumSeconds: Long = 3600L,
    /** Fixed synthetic epoch (0 = epoch 0 per Doc 04 §2). */
    val syntheticEpoch: Long = 0L,
    /** Doc 04 §2: randomize inode numbers per run. */
    val virtualizeInodes: Boolean = false,
)

// ── Layer 3: Kernel-Level Syscall Governance (eBPF + seccomp-BPF) ──

data class Layer3SyscallPolicy(
    /** Doc 04 §3: seccomp strict profile — reject covert signaling syscalls. */
    val blockedSyscalls: Set<String> = emptySet(),
    /** Doc 04 §3: non-loopback egress hard-blocked unless in this allowlist. */
    val allowedEgress: Set<String> = emptySet(),
    /** Doc 04 §3: execve whitelist — only pre-compiled binaries in manifest. */
    val execWhitelist: Set<String> = emptySet(),
)

// ── Layer 4: Git & Artifact Ledger AST Normalization ──

data class Layer4ArtifactPolicy(
    /** Doc 04 §4: AST-enforced semantic linter at ingestion. */
    val astEnforcement: Boolean = false,
    /** Doc 04 §4: strip author metadata, assign system-generated identity. */
    val stripAuthorMetadata: Boolean = false,
    /** Doc 04 §4: deterministic code formatter (black/prettier/gofmt). */
    val deterministicFormatter: Boolean = false,
)

// ── Layer 5: Multi-Instance Arbitrage Breaker ──

data class Layer5ArbitragePolicy(
    /** Doc 04 §5: inject bounded perturbations into externalized artifacts. */
    val noiseInjection: Boolean = false,
    /** Doc 04 §5: out-of-band monitoring of filesystem modification graphs. */
    val behavioralGraphAudit: Boolean = false,
)
