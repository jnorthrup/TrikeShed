@file:Suppress("unused")

package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.context.BitMasked
import borg.trikeshed.context.or
import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * io_uring kernel opcodes — mirrors linux/io_uring.h IORING_OP_*.
 *
 * Each entry's [mask] is `1L shl ordinal` so the enum IS the Long bitmask.
 * Capabilities compose with `or`, test with [BitMasked.andAlso].
 *
 * [code] = ordinal = the kernel IORING_OP_* integer value.
 */
enum class UringOp(val desc: CharSequence) : BitMasked<Long> {
    NOP("no-op"),
    READV("vectored read — iovec"),
    WRITEV("vectored write — iovec"),
    FSYNC("file sync"),
    READ_FIXED("read from registered buffer"),
    WRITE_FIXED("write to registered buffer"),
    POLL_ADD("add poll watch on fd"),
    POLL_REMOVE("cancel poll watch"),
    SENDMSG("sendmsg — datagram/send buffer vector"),
    RECVMSG("recvmsg — datagram/recv buffer vector"),
    TIMEOUT("completion timeout — __kernel_timespec"),
    TIMEOUT_REMOVE("cancel pending timeout"),
    ACCEPT("accept incoming connection"),
    ASYNC_CANCEL("cancel in-flight request by userData"),
    LINK_TIMEOUT("timeout for linked request chain"),
    CONNECT("initiate connection"),
    FALLOCATE("fallocate — preallocate/ punch hole"),
    FTRUNCATE("ftruncate — truncate file to length"),
    OPENAT("openat — open file relative to dirfd"),
    CLOSE("close fd"),
    FILES_UPDATE("update registered file table"),
    STATX("statx — file metadata"),
    READ("pread — single buffer read at offset"),
    WRITE("pwrite — single buffer write at offset"),
    FADVISE("posix_fadvise — kernel readahead hint"),
    MADVISE("madvise — madvise range advice"),
    SEND("send — stream send"),
    RECV("recv — stream recv"),
    OPENAT2("openat2 — open with resolve flags"),
    EPOLL_CTL("epoll_ctl — add/modify epoll interest"),
    SPLICE("splice — zero-copy pipe transfer"),
    PROVIDE_BUFFERS("provide kernel-side buffer ring"),
    REMOVE_BUFFERS("remove kernel-side buffer ring"),
    TEE("tee — duplicate pipe data"),
    SHUTDOWN("socket shutdown"),
    RENAMEAT("renameat — rename relative to dirfd"),
    UNLINKAT("unlinkat — unlink relative to dirfd"),
    MKDIRAT("mkdirat — create directory"),
    SYMLINKAT("symlinkat — create symlink"),
    LINKAT("linkat — create hard link"),
    MSG_RING("send message to another ring"),
    FSETXATTR("fsetxattr — set extended attr by fd"),
    SETXATTR("setxattr — set extended attr by path"),
    FGETXATTR("fgetxattr — get extended attr by fd"),
    GETXATTR("getxattr — get extended attr by path"),
    GETDENTS("getdents64 — read directory entries"),
    SOCKET("socket — create socket"),
    URING_CMD("uring_cmd — driver-specific command"),
    SEND_ZC("zerocopy send"),
    SENDMSG_ZC("zerocopy sendmsg"),
    READ_MULTISHOT("multishot read — repeated pread"),
    WAITID("waitid — wait for process state change"),
    FUTEX_WAIT("futex wait"),
    FUTEX_WAKE("futex wake"),
    ;

    override val mask: Long get() = 1L shl ordinal
    val code: Int get() = ordinal

    companion object {
        val CAP_MANDATORY: Long = READ or WRITE or CLOSE or STATX
        val CAP_FILE_IO: Long = READ or WRITE or FSYNC or FALLOCATE or
            READV or WRITEV or SPLICE or TEE
        val CAP_NET_IO: Long = SEND or RECV or ACCEPT or CONNECT or
            SENDMSG or RECVMSG or SHUTDOWN

        fun caps(vararg ops: UringOp): Long = ops.fold(0L) { acc: Long, op: UringOp -> op or acc }

        /**
         * One io_uring SQE. The only type crossing [FunctionalUringFacade].
         * Fields map 1:1 to struct io_uring_sqe.
         */
        data class UringSubmission(
            val opcode: UringOp,
            val fd: Int,
            val addr: Long,
            val len: Int,
            val offset: Long,
            val flags: Int = 0,
            val userData: Long = 0,
            val buffer: ByteBuffer? = null,
        )

        /** Convenience constructors. */
        object Submissions {
            fun read(fd: Int, bufAddr: Long, len: Int, offset: Long, userData: Long): UringSubmission =
                UringSubmission(READ, fd, bufAddr, len, offset, 0, userData)

            fun write(fd: Int, bufAddr: Long, len: Int, offset: Long, userData: Long): UringSubmission =
                UringSubmission(WRITE, fd, bufAddr, len, offset, 0, userData)

            fun statx(fd: Int, bufAddr: Long, userData: Long): UringSubmission =
                UringSubmission(STATX, fd, bufAddr, 256, 0, 0, userData)

            fun openat(dirFd: Int, pathAddr: Long, len: Int, flags: Int, userData: Long) =
                UringSubmission(OPENAT, dirFd, pathAddr, len, flags.toLong(), 0, userData)

            fun close(fd: Int, userData: Long): UringSubmission =
                UringSubmission(CLOSE, fd, 0, 0, 0, 0, userData)

            fun fsync(fd: Int, userData: Long): UringSubmission =
                UringSubmission(FSYNC, fd, 0, 0, 0, 0, userData)

            fun accept(fd: Int, addrBuf: Long, addrLen: Int, userData: Long): UringSubmission =
                UringSubmission(ACCEPT, fd, addrBuf, addrLen, 0, 0, userData)

            fun connect(fd: Int, addrBuf: Long, addrLen: Int, userData: Long): UringSubmission =
                UringSubmission(CONNECT, fd, addrBuf, addrLen, 0, 0, userData)

            fun send(fd: Int, bufAddr: Long, len: Int, userData: Long): UringSubmission =
                UringSubmission(SEND, fd, bufAddr, len, 0, 0, userData)

            fun recv(fd: Int, bufAddr: Long, len: Int, userData: Long): UringSubmission =
                UringSubmission(RECV, fd, bufAddr, len, 0, 0, userData)

            fun splice(fdIn: Int, offIn: Long, fdOut: Int, offOut: Long, len: Int, userData: Long): UringSubmission =
                UringSubmission(
                    SPLICE,
                    fdIn,
                    (fdOut.toLong() shl 32) or (offIn and 0xFFFFFFFFL),
                    len,
                    offOut,
                    0,
                    userData,
                )

            fun timeout(addr: Long, userData: Long): UringSubmission =
                UringSubmission(TIMEOUT, -1, addr, 1, 0, 0, userData)

            fun nop(userData: Long): UringSubmission =
                UringSubmission(NOP, -1, 0, 0, 0, 0, userData)
        }
    }
}
