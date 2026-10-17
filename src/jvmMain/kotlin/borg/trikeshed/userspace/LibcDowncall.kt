package borg.trikeshed.userspace

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

/**
 * The libc call shapes of [JvmFileSyscalls] and [JvmSocketSyscalls], unbound: a call passes the function's address
 * ([libcSymbol]) first, then its errno capture segment where the shape captures errno. No library is touched here, so
 * native-image initializes this at build time (META-INF/native-image/borg.trikeshed/userspace) and compiles each call
 * into a direct stub call. A handle created at image run time is interpreted instead: Oracle GraalVM 25.4.4 on the M3
 * measured getpid at 1.9 µs per call that way against 3.2 ns, and a 4 KiB pwrite to /dev/null at 4.4 µs against 263 ns
 * (JIT: 11.0 ns bound, 8.9 ns unbound getpid). Identical shapes share one handle.
 */
internal object LibcDowncall {
    private val linker = Linker.nativeLinker()
    private val linux = System.getProperty("os.name").lowercase().contains("linux")
    private val darwin = System.getProperty("os.name").lowercase().contains("mac")
    private val errno = Linker.Option.captureCallState("errno")
    private fun errno(descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        linker.downcallHandle(descriptor, errno, *options)

    /** int openat(int dirfd, const char*, int flags, ...mode). */
    @JvmField val OPENAT: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
        Linker.Option.firstVariadicArg(3))
    /** ssize_t pread / pwrite(int, void*, size_t, off_t). */
    @JvmField val PREAD: MethodHandle = errno(FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG))
    /** ssize_t read / write(int, void*, size_t). */
    @JvmField val READ: MethodHandle = errno(FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG))
    /** int fsync / fdatasync / close(int). */
    @JvmField val FD: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    /** int flock / listen / shutdown(int, int). */
    @JvmField val FD_INT: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
    /** int ftruncate(int, off_t). */
    @JvmField val FTRUNCATE: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG))
    /** int statx(int dirfd, const char*, int flags, unsigned mask, struct statx*). */
    @JvmField val STATX: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS))
    /** int fstat(int, struct stat*). */
    @JvmField val FSTAT: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS))
    /** int fstatat(int dirfd, const char*, struct stat*, int flags). */
    @JvmField val FSTATAT: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    /** int mkdirat(int dirfd, const char*, mode_t): mode_t is 16 bits on Darwin. */
    @JvmField val MKDIRAT: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, if (darwin) JAVA_SHORT else JAVA_INT))
    /** int renameat(int, const char*, int, const char*). */
    @JvmField val RENAMEAT: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
    /** int unlinkat(int dirfd, const char*, int flags). */
    @JvmField val UNLINKAT: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
    /** long syscall(long number, ...): getdents64(fd, buffer, length). */
    @JvmField val SYSCALL: MethodHandle = errno(FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG),
        Linker.Option.firstVariadicArg(1))
    /** ssize_t __getdirentries64(int, char*, size_t, off_t* basep) (Darwin). */
    @JvmField val GETDIRENTRIES: MethodHandle = errno(FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS))
    /** uid_t geteuid(void), no errno. */
    @JvmField val GETEUID: MethodHandle = linker.downcallHandle(FunctionDescriptor.of(JAVA_INT))
    /** void* malloc(size_t), no errno. */
    @JvmField val MALLOC: MethodHandle = linker.downcallHandle(FunctionDescriptor.of(ADDRESS, JAVA_LONG))
    /** void free(void*). */
    @JvmField val FREE: MethodHandle = linker.downcallHandle(FunctionDescriptor.ofVoid(ADDRESS))
    /** int socket(int domain, int type, int protocol). */
    @JvmField val SOCKET: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT))
    /** int bind / connect(int, const struct sockaddr*, socklen_t). */
    @JvmField val FD_ADDRESS_LENGTH: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
    /** int accept / getsockname / getpeername(int, struct sockaddr*, socklen_t*). */
    @JvmField val FD_ADDRESS_ADDRESS: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
    /** ssize_t recv / send(int, void*, size_t, int flags). */
    @JvmField val RECV: MethodHandle = errno(FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT))
    /** int poll(struct pollfd*, nfds_t, int timeout): nfds_t is unsigned long on Linux, unsigned int on Darwin. */
    @JvmField val POLL: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, ADDRESS, if (linux) JAVA_LONG else JAVA_INT, JAVA_INT))
    /** int getsockopt(int, int level, int name, void*, socklen_t*). */
    @JvmField val GETSOCKOPT: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
    /** int setsockopt(int, int level, int name, const void*, socklen_t). */
    @JvmField val SETSOCKOPT: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
    /** int fcntl(int, int command, ...int). */
    @JvmField val FCNTL: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
        Linker.Option.firstVariadicArg(2))
    /** int epoll_ctl(int epfd, int op, int fd, struct epoll_event*). */
    @JvmField val EPOLL_CTL: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS))
    /** int epoll_wait(int epfd, struct epoll_event*, int maxevents, int timeout). */
    @JvmField val EPOLL_WAIT: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
    /** int kqueue(void). */
    @JvmField val KQUEUE: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT))
    /** int kevent(int kq, const struct kevent*, int, struct kevent*, int, const struct timespec*). */
    @JvmField val KEVENT: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
    /** long syscall(long number, ...): io_uring_setup, io_uring_enter and io_uring_register take up to six words. */
    @JvmField val SYSCALL6: MethodHandle = errno(FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
        JAVA_LONG, JAVA_LONG, JAVA_LONG), Linker.Option.firstVariadicArg(1))
    /** void* mmap(void*, size_t, int prot, int flags, int fd, off_t). */
    @JvmField val MMAP: MethodHandle = errno(FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG))
    /** int munmap(void*, size_t). */
    @JvmField val MUNMAP: MethodHandle = errno(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG))
}

/** The address of libc function [name] in this process, for [LibcDowncall]'s handles. */
internal fun libcSymbol(name: String): MemorySegment =
    Linker.nativeLinker().defaultLookup().find(name).orElseThrow { UnsupportedOperationException("libc $name is unavailable") }
