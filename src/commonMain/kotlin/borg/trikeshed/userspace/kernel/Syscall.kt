package borg.trikeshed.userspace.kernel

/**
 * Unified Kernel Syscall Interface
 *
 * Provides a single API surface for all syscall adapters.
 */

interface SyscallAdapter {
    fun read(fd: Int, buf: ByteArray): Int
    fun write(fd: Int, buf: ByteArray): Int
    fun close(fd: Int)
}

interface NetworkAdapter {
    fun connect(host: String, port: Int): Int
    fun bind(host: String, port: Int): Int
    fun listen(fd: Int, backlog: Int)
    fun accept(fd: Int): Pair<Int, String>
    fun send(fd: Int, buf: ByteArray, flags: Int): Int
    fun recv(fd: Int, buf: ByteArray, flags: Int): Int
}

interface IoUringAdapter {
    fun submit(sqe: SyscallSqe): Long
    fun wait(timeoutMs: Int): List<SyscallCqe>
    fun setup(entries: Int, flags: Int): Int
}

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
)

data class SyscallCqe(
    val res: Int,
    val flags: Int,
    val userData: Long
)
