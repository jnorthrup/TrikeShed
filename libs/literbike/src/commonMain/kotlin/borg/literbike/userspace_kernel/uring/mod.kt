@file:JvmName("UringModule")

package borg.literbike.userspace_kernel.uring

/**
 * liburing-compatible wrapper with software emulation fallback
 *
 * Provides io_uring-style API that automatically falls back to
 * epoll-based emulation when io_uring is unavailable.
 */

// Re-exports — TODO: resolve actual type locations
// typealias UringBackend = borg.literbike.userspace_kernel.uring.UringBackend
// typealias UringEmulator = borg.literbike.userspace_kernel.uring.UringEmulator
// typealias Uring = borg.literbike.userspace_kernel.uring.Uring
// typealias UringOp = borg.literbike.userspace_kernel.uring.UringOp
// typealias UringOpBuilder = borg.literbike.userspace_kernel.uring.UringOpBuilder
// typealias UringFut = borg.literbike.userspace_kernel.uring.UringFut
// typealias OpBuilder = borg.literbike.userspace_kernel.uring.OpBuilder
