@file:Suppress("unused")

package borg.trikeshed.userspace

import borg.trikeshed.context.BitMasked
import borg.trikeshed.context.or
import borg.trikeshed.userspace.UringOp.Companion.STATX_SIZE
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.ByteOrder
import kotlin.jvm.JvmInline

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
    SYNC_FILE_RANGE(8, "sync_file_range — flush page cache / writeback"),
    BIND(56, "bind — bind socket to address (sockaddr; sockaddr_un for UNIX)"),
    LISTEN(57, "listen — mark socket passive"),
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

        // Linux asm-generic open, *at and statx values: the flag vocabulary of every backend,
        // which translates them to its host's numbering.
        const val O_RDONLY: Int = 0
        const val O_WRONLY: Int = 1
        const val O_RDWR: Int = 2
        const val O_CREAT: Int = 0x40
        const val O_EXCL: Int = 0x80
        const val O_TRUNC: Int = 0x200
        const val O_APPEND: Int = 0x400
        const val O_NONBLOCK: Int = 0x800
        const val O_DIRECTORY: Int = 0x10000
        const val O_NOFOLLOW: Int = 0x20000
        const val O_CLOEXEC: Int = 0x80000
        const val AT_FDCWD: Int = -100
        const val AT_SYMLINK_NOFOLLOW: Int = 0x100
        const val AT_REMOVEDIR: Int = 0x200
        const val AT_EMPTY_PATH: Int = 0x1000
        const val STATX_BASIC_STATS: Int = 0x7ff
        const val S_IFMT: Int = 0xf000
        const val S_IFDIR: Int = 0x4000
        const val S_IFREG: Int = 0x8000
        const val DT_UNKNOWN: Int = 0
        const val DT_REG: Int = 8
        const val LOCK_EX: Int = 2
        const val LOCK_NB: Int = 4
        /** sizeof(struct statx). */
        const val STATX_SIZE: Int = 256

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
            /** Portable OPENAT path bytes; flags use the Linux O_* values above, [mode] the creation permissions. */
            fun openat(path: String, flags: Int = 0, userData: Long = 0, mode: Int = 438): UringSubmission {
                require(path.isNotEmpty() && '\u0000' !in path)
                val bytes = path.encodeToByteArray()
                return UringSubmission(OPENAT, AT_FDCWD, 0, bytes.size, flags.toLong(),
                    userData = userData, buffer = ByteBuffer(bytes), operationFlags = mode)
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

            /**
             * io_uring_prep_statx(dfd, path, flags, mask, statxbuf): the window holds the path's
             * [UringSubmission.len] bytes, its NUL and the [STATX_SIZE] bytes the kernel writes, so one
             * pinned buffer carries both of liburing's pointers. An empty path with AT_EMPTY_PATH
             * reads [dfd] itself. Read the result with [Statx.of].
             */
            fun statx(dfd: Int, path: String, flags: Int, mask: Int, userData: Long): UringSubmission {
                require('\u0000' !in path)
                val bytes = path.encodeToByteArray()
                return UringSubmission(STATX, dfd, 0, bytes.size, mask.toLong(), userData = userData,
                    buffer = ByteBuffer(bytes.copyOf(bytes.size + 1 + STATX_SIZE)), operationFlags = flags)
            }

            /** io_uring_prep_mkdirat(dfd, path, mode). */
            fun mkdirat(dfd: Int, path: String, mode: Int, userData: Long): UringSubmission = paths(MKDIRAT, dfd, 0, mode, userData, path)

            /** io_uring_prep_unlinkat(dfd, path, flags). */
            fun unlinkat(dfd: Int, path: String, flags: Int, userData: Long): UringSubmission = paths(UNLINKAT, dfd, 0, flags, userData, path)

            /** io_uring_prep_renameat(olddfd, oldpath, newdfd, newpath, flags): both paths NUL-separated in one buffer. */
            fun renameat(oldDfd: Int, oldPath: String, newDfd: Int, newPath: String, flags: Int, userData: Long): UringSubmission =
                paths(RENAMEAT, oldDfd, newDfd.toLong(), flags, userData, oldPath, newPath)

            /** getdents64(fd, dirp, count) into [buffer]'s remaining window: linux_dirent64 records, 0 at the end. */
            fun getdents(fd: Int, buffer: ByteBuffer, userData: Long): UringSubmission =
                UringSubmission(GETDENTS, fd, 0, buffer.remaining(), 0, userData = userData, buffer = buffer)

            fun paths(opcode: UringOp, fd: Int, offset: Long, operationFlags: Int, userData: Long, vararg paths: String): UringSubmission {
                require(paths.all { it.isNotEmpty() && '\u0000' !in it })
                val bytes = paths.joinToString(0.toChar().toString()).encodeToByteArray()
                return UringSubmission(opcode, fd, 0, bytes.size, offset, userData = userData,
                    buffer = ByteBuffer(bytes), operationFlags = operationFlags)
            }

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

/**
 * linux/stat.h `struct statx` as STATX writes it: [STATX_SIZE] bytes in host order, the order the
 * kernel writes and every emulation reproduces.
 */
@JvmInline
value class Statx(val buffer: ByteBuffer) {
    val stx_nlink: UInt get() = buffer.getInt(0x10).toUInt()
    val stx_uid: UInt get() = buffer.getInt(0x14).toUInt()
    val stx_mode: Int get() = buffer.getShort(0x1c).toInt() and 0xffff
    val stx_ino: ULong get() = buffer.getLong(0x20).toULong()
    val stx_size: ULong get() = buffer.getLong(0x28).toULong()
    val stx_dev_major: UInt get() = buffer.getInt(0x88).toUInt()
    val stx_dev_minor: UInt get() = buffer.getInt(0x8c).toUInt()

    companion object {
        /** The struct a completed [UringOp.Companion.Submissions.statx] wrote behind its path. */
        fun of(submission: UringSubmission): Statx {
            val window = requireNotNull(submission.buffer)
            return Statx(ByteBuffer.wrap(window.array(), window.arrayOffset() + window.position() + submission.len + 1, STATX_SIZE)
                .order(ByteOrder.nativeOrder()))
        }
    }
}
