/**
 * TrikeShed Platform — Userspace Kernel
 *
 * Ported from literbike `src/userspace/kernel/`. Low-level kernel interfaces
 * for eBPF, io_uring, POSIX sockets, syscalls, kernel capabilities, and bypass mechanisms.
 *
 * Sub-modules:
 * - densified_ops: Densified kernel operations
 * - ebpf / ebpf_mmap: eBPF integration and mmap
 * - endgame_bypass: Endgame bypass mechanisms
 * - io_uring: io_uring kernel interface
 * - kernel_capabilities: Linux capability management
 * - knox_proxy: Knox proxy integration
 * - nio: Kernel-level NIO interface
 * - posix_sockets: POSIX socket wrappers
 * - syscall / syscall_net: Syscall interface for network operations
 * - tethering_bypass: Network tethering bypass
 * - uring/emulator: io_uring userspace emulator
 */
package borg.trikeshed.platform.kernel
