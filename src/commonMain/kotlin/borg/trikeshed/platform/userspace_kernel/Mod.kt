@file:JvmName("UserspaceKernelModule")

package borg.literbike.userspace_kernel

/**
 * Unified Kernel Abstractions
 *
 * Provides high-performance, zero-overhead interfaces to:
 * - io_uring for kernel I/O
 * - eBPF JIT compilation
 * - Memory-mapped I/O
 * - Kernel bypass techniques
 */

// Re-exports
typealias SystemCapabilities = borg.literbike.userspace_kernel.KernelCapabilitiesModule.SystemCapabilities
typealias NioChannel = borg.literbike.userspace_kernel.NioModule.NioChannel
typealias SimpleReactor = borg.literbike.userspace_kernel.NioModule.SimpleReactor
typealias ReadableFuture<T> = borg.literbike.userspace_kernel.NioModule.ReadableFuture<T>
typealias WritableFuture<T> = borg.literbike.userspace_kernel.NioModule.WritableFuture<T>
typealias DensifiedKernel = borg.literbike.userspace_kernel.EndgameBypassModule.DensifiedKernel
typealias IoUringParams = borg.literbike.userspace_kernel.EndgameBypassModule.IoUringParams
typealias PosixSocket = borg.literbike.userspace_kernel.PosixSocketsModule.PosixSocket
typealias SocketPair = borg.literbike.userspace_kernel.PosixSocketsModule.SocketPair
typealias NetworkInterface = borg.literbike.userspace_kernel.SyscallNetModule.NetworkInterface
typealias SocketOps = borg.literbike.userspace_kernel.SyscallNetModule.SocketOps
typealias InterfaceAddr = borg.literbike.userspace_kernel.SyscallNetModule.InterfaceAddr
