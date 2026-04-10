package borg.trikeshed.platform.kernel

/**
 * Unified Kernel Abstractions
 *
 * Provides high-performance, zero-overhead interfaces to:
 * - io_uring for kernel I/O
 * - eBPF JIT compilation
 * - Memory-mapped I/O
 * - Kernel bypass techniques
 */

// Note: Feature-flagged modules in Rust are conditionally compiled.
// In Kotlin all types are available; platform-specific implementations
// use expect/actual mechanism.

// Re-exported from submodules:
// - SystemCapabilities from kernel_capabilities.kt
// - NioChannel, SimpleReactor from nio.kt
// - SocketOps, NetworkInterface from syscall_net.kt
// - PosixSocket, SocketPair from posix_sockets.kt
// - DensifiedKernel, IoUringParams from endgame_bypass.kt
