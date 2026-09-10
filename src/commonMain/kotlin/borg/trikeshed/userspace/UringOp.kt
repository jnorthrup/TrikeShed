@file:Suppress("unused")

package borg.trikeshed.userspace

import borg.trikeshed.context.BitMasked
import borg.trikeshed.context.or
import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * Supported portable subset of linux/io_uring.h IORING_OP_* codes (liburing 2.15).
 *
 * Each entry's [mask] is `1L shl ordinal` so the enum IS the Long bitmask.
 * Capabilities compose with `or`, test with [BitMasked.andAlso].
 *
 * [code] is independent of the capability bit position. Unique negative codes
 * identify userspace extensions, which must be emulated or rejected.
 */
enum class UringOp(val code: Int, val desc: String) : BitMasked<Long> {
    NOP(0, "no-op"),
    READV(1, "vectored read — iovec"),
    WRITEV(2, "vectored write — iovec"),
    FSYNC(3, "file sync"),
    READ_FIXED(4, "read from registered buffer"),
    WRITE_FIXED(5, "write to registered buffer"),
    POLL_ADD(6, "add poll watch on fd"),
    POLL_REMOVE(7, "cancel poll watch"),
    SENDMSG(9, "sendmsg — datagram/send buffer vector"),
    RECVMSG(10, "recvmsg — datagram/recv buffer vector"),
    TIMEOUT(11, "completion timeout — __kernel_timespec"),
    TIMEOUT_REMOVE(12, "cancel pending timeout"),
    ACCEPT(13, "accept incoming connection"),
    ASYNC_CANCEL(14, "cancel in-flight request by userData"),
    LINK_TIMEOUT(15, "timeout for linked request chain"),
    CONNECT(16, "initiate connection"),
    FALLOCATE(17, "fallocate — preallocate/ punch hole"),
    FTRUNCATE(55, "ftruncate — truncate file to length"),
    OPENAT(18, "openat — open file relative to dirfd"),
    CLOSE(19, "close fd"),
    FILES_UPDATE(20, "update registered file table"),
    STATX(21, "statx — file metadata"),
    READ(22, "pread — single buffer read at offset"),
    WRITE(23, "pwrite — single buffer write at offset"),
    FADVISE(24, "posix_fadvise — kernel readahead hint"),
    MADVISE(25, "madvise — madvise range advice"),
    SEND(26, "send — stream send"),
    RECV(27, "recv — stream recv"),
    OPENAT2(28, "openat2 — open with resolve flags"),
    EPOLL_CTL(29, "epoll_ctl — add/modify epoll interest"),
    SPLICE(30, "splice — zero-copy pipe transfer"),
    PROVIDE_BUFFERS(31, "provide buffers for selection by buffer group"),
    REMOVE_BUFFERS(32, "remove buffers from a selection group"),
    TEE(33, "tee — duplicate pipe data"),
    SHUTDOWN(34, "socket shutdown"),
    RENAMEAT(35, "renameat — rename relative to dirfd"),
    UNLINKAT(36, "unlinkat — unlink relative to dirfd"),
    MKDIRAT(37, "mkdirat — create directory"),
    SYMLINKAT(38, "symlinkat — create symlink"),
    LINKAT(39, "linkat — create hard link"),
    MSG_RING(40, "send message to another ring"),
    FSETXATTR(41, "fsetxattr — set extended attr by fd"),
    SETXATTR(42, "setxattr — set extended attr by path"),
    FGETXATTR(43, "fgetxattr — get extended attr by fd"),
    GETXATTR(44, "getxattr — get extended attr by path"),
    FLISTXATTR(-1, "flistxattr — list extended attrs by fd"),
    LISTXATTR(-2, "listxattr — list extended attrs by path"),
    FREMOVEXATTR(-3, "fremovexattr — remove extended attr by fd"),
    REMOVEXATTR(-4, "removexattr — remove extended attr by path"),
    GETDENTS(-5, "getdents64 — read directory entries"),
    SOCKET(45, "socket — create socket"),
    URING_CMD(46, "uring_cmd — driver-specific command"),
    SEND_ZC(47, "zerocopy send"),
    SENDMSG_ZC(48, "zerocopy sendmsg"),
    READ_MULTISHOT(49, "multishot read — repeated pread"),
    WAITID(50, "waitid — wait for process state change"),
    FUTEX_WAIT(51, "futex wait"),
    FUTEX_WAKE(52, "futex wake"),
    ;

    override val mask: Long get() = 1L shl ordinal

    init {
        require(ordinal < Long.SIZE_BITS) { "Portable capability subset exceeds a Long" }
    }

    companion object {
        val CAP_MANDATORY: Long = READ or WRITE or CLOSE or STATX
        val CAP_FILE_IO: Long = READ or WRITE or FSYNC or FALLOCATE or
            READV or WRITEV or SPLICE or TEE
        val CAP_NET_IO: Long = SEND or RECV or ACCEPT or CONNECT or
            SENDMSG or RECVMSG or SHUTDOWN

        fun caps(vararg ops: UringOp): Long = ops.fold(0L) { acc: Long, op: UringOp -> op or acc }

        /**
         * Common submission contract crossing [FunctionalUringFacade].
         * [flags] holds IOSQE flags; [operationFlags] holds the opcode-specific
         * union (advice, fsync flags, etc.). [code] is encoded by the backend.
         * Heap buffers and mapping owners remain borrowed through completion.
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
            val operationFlags: Int = 0,
            val bufferIndex: Int = -1,
            val memory: MemoryMapping? = null,
        )

        /** Convenience constructors. */
        object Submissions {
            /** Portable OPENAT path bytes; flags use Linux O_RDONLY/O_RDWR/O_CREAT/O_TRUNC. */
            fun openat(path: String, flags: Int = 0, userData: Long = 0): UringSubmission {
                require(path.isNotEmpty() && '\u0000' !in path)
                val bytes = path.encodeToByteArray()
                return UringSubmission(OPENAT, -100, 0, bytes.size, flags.toLong(),
                    userData = userData, buffer = ByteBuffer(bytes), operationFlags = 438)
            }

            fun read(fd: Int, bufAddr: Long, len: Int, offset: Long, userData: Long): UringSubmission =
                UringSubmission(READ, fd, bufAddr, len, offset, 0, userData)

            fun write(fd: Int, bufAddr: Long, len: Int, offset: Long, userData: Long): UringSubmission =
                UringSubmission(WRITE, fd, bufAddr, len, offset, 0, userData)

            /** Owned advice follows the common admission/drain lifetime of its mapping. */
            fun madvise(memory: MemoryMapping, offset: Long = 0, len: Int, advice: Int, userData: Long): UringSubmission {
                require(offset >= 0 && len >= 0 && offset <= memory.length && len <= memory.length - offset)
                return UringSubmission(MADVISE, -1, memory.address + offset, len, 0,
                    userData = userData, operationFlags = advice, memory = memory)
            }

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
