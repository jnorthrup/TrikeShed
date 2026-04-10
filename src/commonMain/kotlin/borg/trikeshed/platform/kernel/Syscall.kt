package borg.trikeshed.platform.kernel

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * Unified Kernel Syscall Interface
 *
 * Provides a single API surface for all syscall adapters in the kernel module.
 * Note: Platform-specific syscall access uses expect/actual declarations.
 */

/**
 * FFI handle for file descriptor
 */
typealias RawFd = Long

/**
 * Syscall adapter interface
 */
interface SyscallAdapter {
    fun read(fd: RawFd, buf: ByteArray): Result<Int>
    fun write(fd: RawFd, buf: ByteArray): Result<Int>
    fun close(fd: RawFd): Result<Unit>
}

/**
 * Network adapter interface
 */
interface NetworkAdapter {
    fun connect(addr: String): Result<RawFd>
    fun bind(addr: String): Result<RawFd>
    fun listen(addr: String, backlog: Int): Result<Unit>
    fun accept(fd: RawFd): Result<Pair<RawFd, String>>
    fun send(fd: RawFd, buf: ByteArray, flags: Int): Result<Int>
    fun recv(fd: RawFd, buf: ByteArray, flags: Int): Result<Int>
}

/**
 * io_uring adapter interface (Linux-specific, stubbed on other platforms)
 */
interface IoUringAdapter {
    fun submit(sqe: SyscallSqe): Result<Long>
    fun wait(timeoutMs: Int): Result<List<SyscallCqe>>
    fun setup(entries: Int, flags: Int): Result<RawFd>
}

/**
 * Syscall Submission Queue Entry - kernel ABI compatible (64 bytes)
 */
data class SyscallSqe(
    val opcode: Byte,
    val flags: Byte,
    val ioprio: Short,
    val fd: Int,
    val offAddr2: Long,
    val addr: Long,
    val len: Int,
    val rwFlags: Int,
    val userData: Long,
    val bufIndex: Short,
    val personality: Short,
    val spliceFdIn: Int,
    val addr3: Long,
    val resv: Long
) {
    companion object {
        /** UNSAFE: Size must be 64 bytes for kernel ABI */
        const val ABI_SIZE_BYTES = 64
    }
}

/**
 * Syscall Completion Queue Entry - kernel ABI compatible (16 bytes)
 */
data class SyscallCqe(
    val res: Int,
    val flags: Int,
    val userData: Long
) {
    companion object {
        /** UNSAFE: Size must be 16 bytes for kernel ABI */
        const val ABI_SIZE_BYTES = 16
    }
}

/**
 * Platform-specific syscall implementations (expect declarations)
 */
@OptIn(ExperimentalNativeApi::class)
object PlatformSyscalls {
    /**
     * Whether we're running on Linux
     */
    val isLinux: Boolean
        get() = Platform.osFamily == org.jetbrains.kotlin.platform.OsFamily.LINUX

    /**
     * Whether we're running on macOS
     */
    val isMacos: Boolean
        get() = Platform.osFamily == org.jetbrains.kotlin.platform.OsFamily.OSX

    /**
     * RawFd for invalid/uninitialized fd
     */
    const val INVALID_FD: RawFd = -1L
}
