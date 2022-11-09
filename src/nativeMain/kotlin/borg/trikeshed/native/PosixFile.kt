package simple

import borg.trikeshed.lib.CZero.z
import borg.trikeshed.native.HasDescriptor
import borg.trikeshed.native.HasPosixErr
import kotlinx.cinterop.*
//import linux_uring.AT_FDCWD
//import linux_uring.fstatat
import platform.posix.*

/**
opens file for syncronous read  /write
 */
class PosixFile(
    val path: String?,
    O_FLAGS: __u32 = PosixOpenOpts.withFlags(PosixOpenOpts.OpenReadOnly, PosixOpenOpts.OpenSync),
    override val fd: Int = run {
        platform.posix.open(path, O_FLAGS.toInt())
    },
) : HasDescriptor, HasSize {
    override fun read64(buf: ByteArray): ULong {
        val addressOf = buf.pin().addressOf(0)
        val b: CArrayPointer<ByteVar> = addressOf.reinterpret<ByteVar>()
        val read = read(fd, b, buf.size.toULong())
        HasPosixErr.posixRequires(read >= 0) { "read failed with result ${HasPosixErr.reportErr(read.toInt())}" }
        return read.toULong()
    }

    override fun close(): Int {
        val close = close(fd)
        HasPosixErr.posixRequires(close >= 0) { "close failed with result ${HasPosixErr.reportErr(close)}" }
        st_?.let { nativeHeap.free(it.rawPtr) }
        return close
    }

    override var st_: stat? = null
    override val st: stat by lazy {
        val st__ = st_ ?: (nativeHeap.alloc<stat>().also { st_ = it })
        fstat(fd, st__.ptr)
        st__
    }

    /**
    flags

    # define AT_FDCWD		-100	/* Special value used to indicate the *at functions should use the current working directory. */
    # define AT_SYMLINK_NOFOLLOW	0x100	/* Do not follow symbolic links.  */
    # define AT_REMOVEDIR		0x200	/* Remove directory instead of unlinking file.  */
    # define AT_SYMLINK_FOLLOW	0x400	/* Follow symbolic links.  */
    # ifdef __USE_GNU
    #  define AT_NO_AUTOMOUNT	0x800	/* Suppress terminal automount traversal.  */
    #  define AT_EMPTY_PATH		0x1000	/* Allow empty relative pathname.  */
    #  define AT_STATX_SYNC_TYPE	0x6000
    #  define AT_STATX_SYNC_AS_STAT	0x0000
    #  define AT_STATX_FORCE_SYNC	0x2000
    #  define AT_STATX_DONT_SYNC	0x4000
    #  define AT_RECURSIVE		0x8000	/* Apply to the entire subtree.  */
    # endif
    # define AT_EACCESS		0x200	/* Test access permitted for effective IDs, not real IDs.  */
    #endif

    AT_EMPTY_PATH (since Linux 2.6.39)
    If pathname is an empty string, operate on the file
    referred to by dirfd (which may have been obtained using
    the open(2) O_PATH flag).  In this case, dirfd can refer
    to any type of file, not just a directory, and the
    behavior of fstatat() is similar to that of fstat().  If
    dirfd is AT_FDCWD, the call operates on the current
    working directory.  This flag is Linux-specific; define
    _GNU_SOURCE to obtain its definition.

    AT_NO_AUTOMOUNT (since Linux 2.6.38)
    Don't automount the terminal ("basename") component of
    pathname if it is a directory that is an automount point.
    This allows the caller to gather attributes of an
    automount point (rather than the location it would mount).
    Since Linux 4.14, also don't instantiate a nonexistent
    name in an on-demand directory such as used for
    automounter indirect maps.  This flag has no effect if the
    mount point has already been mounted over.

    Both stat() and lstat() act as though AT_NO_AUTOMOUNT was
    set.

    The AT_NO_AUTOMOUNT can be used in tools that scan
    directories to prevent mass-automounting of a directory of
    automount points.

    This flag is Linux-specific; define _GNU_SOURCE to obtain
    its definition.

    AT_SYMLINK_NOFOLLOW
    If pathname is a symbolic link, do not dereference it:
    instead return information about the link itself, like
    lstat().  (By default, fstatat() dereferences symbolic
    links, like stat().) */

/* uring linux headers
    fun followLink(flags: Int = 0) = apply {
        val st__ = st_ ?: (nativeHeap.alloc<stat>().also { st_ = it })
        fstatat(fd, null, st__.ptr.reinterpret(), flags)
    }
*/

    override fun write64(buf: ByteArray): ULong {
        val b: Long = buf.pin().objcPtr().toLong()
        val write = write(fd, b.toCPointer<ByteVar>()!!, buf.size.toULong())
        HasPosixErr.posixRequires(write >= 0) { "write failed with result ${HasPosixErr.reportErr(write.toInt())}" }
        return write.toULong()
    }

    /** *lseek [manpage](https://www.man7.org/linux/man-pages/man2/lseek.2.html) */
    override fun seek(offset: __off_t, whence: Int): ULong {
        val offr: __off_t = lseek(fd, offset, whence)
        HasPosixErr.posixRequires(offr >= 0) { "seek failed with result ${HasPosixErr.reportErr(res = offr.toInt())}" }
        return offr.toULong()
    }

    /** * mmap [manpage](https://www.man7.org/linux/man-pages/man2/mmap.2.html) */
    fun mapBag(
        len: ULong,
        prot: Int = PROT_READ or PROT_WRITE,
        flags: Int = MAP_SHARED or MAP_ANONYMOUS,
        offset: off_t = 0L,
    ): COpaquePointer? = mmap_base(fd = -1, __len = len, __prot = prot, __flags = flags, __offset = offset)

    /** * mmap [manpage](https://www.man7.org/linux/man-pages/man2/mmap.2.html) */
    fun mmap(len: ULong, prot: Int = PROT_READ, flags: Int = MAP_SHARED, offset: off_t = 0L) = mmap_base(
        fd = fd,
        __len = len,
        __prot = prot,
        __flags = flags,
        __offset = offset
    ).reinterpret<CArrayPointerVar<ByteVar>>()

    /** * lseek [manpage](https://www.man7.org/linux/man-pages/man2/leek.2.html) */
    fun at(offset: __off_t, whence: Int) {
        lseek(fd, offset /* = kotlin.Long */, __whence = whence)
    }

    companion object {
        /*** open [manpage](https://www.man7.org/linux/man-pages/man2/open.2.html) */

        fun open(path: String?, O_FLAGS: Int): Int {
            val fd = platform.posix.open(path, O_FLAGS)
            HasPosixErr.posixRequires(fd > 0) { "File::open $path returned ${HasPosixErr.reportErr(fd)}" }
            return fd
        }

        fun statk(path: String?, stat1: stat = nativeHeap.alloc<stat>()): stat {
            stat(path, stat1.ptr).also {
                HasPosixErr.posixRequires(it.z) { "statx $path" }
                return stat1
            }
        }

        val page_size by lazy { sysconf(_SC_PAGE_SIZE) }

        /** [manpage](https://www.man7.org/linux/man-pages/man2/mmap.2.html) */
        fun mmap_base(
            __addr: CValuesRef<*>? = 0L.toCPointer<ByteVar>(),
            __len: size_t = page_size.convert(),
            /**
            PROT_EXEC
            Pages may be executed.

            PROT_READ
            Pages may be read.

            PROT_WRITE
            Pages may be written.

            PROT_NONE
            Pages may not be accessed.

             */

            __prot: Int,
            /** *
             * exactly one:
             *        MAP_SHARED MAP_SHARED_VALIDATE MAP_PRIVATE
             *
             *  |=MAP_ANON MAP_FIXED MAP_FIXED_NOREPLACE MAP_GROWSDOWN MAP_HUGETLB
             *   MAP_HUGE_2MB MAP_HUGE_1GB MAP_LOCKED MAP_NONBLOCK MAP_NORESERVE
             *   MAP_POPULATE MAP_STACK MAP_SYNC MAP_UNINITIALIZED
             */
            __flags: Int,
            fd: Int,
            __offset: __off_t,
        ): COpaquePointer {
            HasPosixErr.warning(__offset % page_size == 0L) { "$__offset requires blocksize of $page_size" }
            val cPointer = mmap(__addr, __len, __prot, __flags, fd, __offset)
            HasPosixErr.posixRequires(cPointer.toLong() != -1L) {
                "mmap failed with result ${
                    HasPosixErr.reportErr(cPointer.toLong().toInt())
                }"
            }

            return cPointer!!
        }

/* uring linux headers
        fun getDirFd(namedDirAndFile: List<String>): Int = if (namedDirAndFile.first().isEmpty()) {
            AT_FDCWD
        } else {
            platform.posix.open(namedDirAndFile.first(), O_DIRECTORY).also {
                HasPosixErr.posixRequires(it > 0) { "opendir ${namedDirAndFile.first()}" }
            }
        }
*/

        fun namedDirAndFile(file_path: String) = file_path.lastIndexOf('/').let { tail ->
            if (tail == -1) listOf("", file_path) else listOf(
                file_path.substring(0, tail),
                file_path.substring(tail.inc())
            )
        }

        fun exists(fname: String) = access(fname, F_OK).z
    }
}