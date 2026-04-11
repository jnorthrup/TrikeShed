package borg.literbike.userspace_kernel

import java.io.IOException

/**
 * Unified Kernel Syscall Interface
 *
 * Provides a single API surface for all syscall adapters in the kernel module.
 */
object SyscallModule {

    interface SyscallAdapter {
        fun read(fd: Int, buf: ByteArray): Result<Int>
        fun write(fd: Int, buf: ByteArray): Result<Int>
        fun close(fd: Int): Result<Unit>
    }

    interface NetworkAdapter {
        fun connect(addr: String): Result<Int>
        fun bind(addr: String): Result<Int>
        fun listen(addr: String, backlog: Int = 128): Result<Unit>
        fun accept(fd: Int): Result<Pair<Int, String>>
        fun send(fd: Int, buf: ByteArray, flags: Int = 0): Result<Int>
        fun recv(fd: Int, buf: ByteArray, flags: Int = 0): Result<Int>
    }

    interface IoUringAdapter {
        fun submit(sqe: SyscallSqe): Result<Long>
        fun wait(timeoutMs: Int): Result<List<SyscallCqe>>
        fun setup(entries: Int, flags: Int): Result<Int>
    }

    data class SyscallSqe(
        val opcode: Int,
        val flags: Int = 0,
        val ioprio: Int = 0,
        val fd: Int = -1,
        val offAddr2: Long = 0,
        val addr: Long = 0,
        val len: Int = 0,
        val rwFlags: Int = 0,
        val userData: Long = 0,
        val bufIndex: Int = 0,
        val personality: Int = 0,
        val spliceFdIn: Int = 0,
        val addr3: Long = 0,
        val resv: Long = 0
    )

    data class SyscallCqe(
        val res: Int,
        val flags: Int,
        val userData: Long
    )
}
