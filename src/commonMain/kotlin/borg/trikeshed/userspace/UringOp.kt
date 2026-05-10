@file:Suppress("unused")

package borg.trikeshed.userspace

import borg.trikeshed.context.BitMasked

/**
 * io_uring kernel opcodes — mirrors linux/io_uring.h IORING_OP_*.
 *
 * Each entry's [mask] is `1L shl ordinal` so the enum IS the Long bitmask.
 * Capabilities compose with `or`, test with [BitMasked.andAlso].
 *
 * [code] = ordinal = the kernel IORING_OP_* integer value.
 */
enum class UringOp(val desc: String) : BitMasked<Long> {
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
    FALLOCATE("fallocate — preallocate/ Punch hole"),
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
        val CAP_MANDATORY: Long = READ.mask or WRITE.mask or CLOSE.mask or STATX.mask
        val CAP_FILE_IO: Long = READ.mask or WRITE.mask or FSYNC.mask or FALLOCATE.mask or
            READV.mask or WRITEV.mask or SPLICE.mask or TEE.mask
        val CAP_NET_IO: Long = SEND.mask or RECV.mask or ACCEPT.mask or CONNECT.mask or
            SENDMSG.mask or RECVMSG.mask or SHUTDOWN.mask

        fun caps(vararg ops: UringOp): Long = ops.fold(0L) { acc, op -> acc or op.mask }


        /**
         * One io_uring SQE. The **only** type crossing [FunctionalUringFacade].
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
        )

        /** Convenience constructors. */
        object Submissions {
            fun read(fd: Int, bufAddr: Long, len: Int, offset: Long, userData: Long) =
                UringSubmission(UringOp.READ, fd, bufAddr, len, offset, 0, userData)

            fun write(fd: Int, bufAddr: Long, len: Int, offset: Long, userData: Long) =
                UringSubmission(UringOp.WRITE, fd, bufAddr, len, offset, 0, userData)

            fun statx(fd: Int, bufAddr: Long, userData: Long) =
                UringSubmission(UringOp.STATX, fd, bufAddr, 256, 0, 0, userData)

            fun openat(dirFd: Int, pathAddr: Long, len: Int, flags: Int, userData: Long) =
                UringSubmission(UringOp.OPENAT, dirFd, pathAddr, len, flags.toLong(), 0, userData)

            fun close(fd: Int, userData: Long) =
                UringSubmission(UringOp.CLOSE, fd, 0, 0, 0, 0, userData)

            fun fsync(fd: Int, userData: Long) =
                UringSubmission(UringOp.FSYNC, fd, 0, 0, 0, 0, userData)

            fun accept(fd: Int, addrBuf: Long, addrLen: Int, userData: Long) =
                UringSubmission(UringOp.ACCEPT, fd, addrBuf, addrLen, 0, 0, userData)

            fun connect(fd: Int, addrBuf: Long, addrLen: Int, userData: Long) =
                UringSubmission(UringOp.CONNECT, fd, addrBuf, addrLen, 0, 0, userData)

            fun send(fd: Int, bufAddr: Long, len: Int, userData: Long) =
                UringSubmission(UringOp.SEND, fd, bufAddr, len, 0, 0, userData)

            fun recv(fd: Int, bufAddr: Long, len: Int, userData: Long) =
                UringSubmission(UringOp.RECV, fd, bufAddr, len, 0, 0, userData)

            fun splice(fdIn: Int, offIn: Long, fdOut: Int, offOut: Long, len: Int, userData: Long) =
                UringSubmission(
                    UringOp.SPLICE,
                    fdIn,
                    (fdOut.toLong() shl 32) or (offIn and 0xFFFFFFFFL),
                    len,
                    offOut,
                    0,
                    userData
                )

            fun timeout(addr: Long, userData: Long) =
                UringSubmission(UringOp.TIMEOUT, -1, addr, 1, 0, 0, userData)

            fun nop(userData: Long) =
                UringSubmission(UringOp.NOP, -1, 0, 0, 0, 0, userData)
        }
    }}
