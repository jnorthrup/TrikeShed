package borg.trikeshed.userspace.kernel

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.ByteRegion

/**
 * Unified Kernel Syscall Interface
 *
 * Provides a single API surface for all syscall adapters.
 */

interface SyscallAdapter {
    fun read(fd: Int, dst: ByteRegion): Int
    fun write(fd: Int, src: ByteSeries): Int
    fun close(fd: Int)
}

/** fd-level socket syscalls — not a protocol adapter. */
interface SocketSyscalls {
    fun connect(host: String, port: Int): Int
    fun bind(host: String, port: Int): Int
    fun listen(fd: Int, backlog: Int)
    fun accept(fd: Int): Pair<Int, String>
    fun send(fd: Int, src: ByteSeries, flags: Int): Int
    fun recv(fd: Int, dst: ByteRegion, flags: Int): Int
}

@Deprecated("Use SocketSyscalls", ReplaceWith("SocketSyscalls"))
typealias NetworkAdapter = SocketSyscalls

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
