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
    ): COpaquePointer = mmap_base(fd = -1, __len = len, __prot = prot, __flags = flags, __offset = offset)

    /** * mmap [manpage](https://www.man7.org/linux/man-pages/man2/mmap.2.html) */
    fun mmap(
        /**The length argument specifies the length of
        the mapping (which must be greater than 0).*/
        len: ULong,
        /**       The prot argument describes the desired memory protection of the
        mapping (and must not conflict with the open mode of the file).
        It is either PROT_NONE or the bitwise OR of one or more of the
        following flags:

        PROT_EXEC
        Pages may be executed.

        PROT_READ
        Pages may be read.

        PROT_WRITE
        Pages may be written.

        PROT_NONE
        Pages may not be accessed.*/
        prot: Int = PROT_READ,
        /**The flags argument
        The flags argument determines whether updates to the mapping are
        visible to other processes mapping the same region, and whether
        updates are carried through to the underlying file.  This
        behavior is determined by including exactly one of the following
        values in flags:

        MAP_SHARED
        Share this mapping.  Updates to the mapping are visible to
        other processes mapping the same region, and (in the case
        of file-backed mappings) are carried through to the
        underlying file.  (To precisely control when updates are
        carried through to the underlying file requires the use of
        msync(2).)

        MAP_SHARED_VALIDATE (since Linux 4.15)
        This flag provides the same behavior as MAP_SHARED except
        that MAP_SHARED mappings ignore unknown flags in flags.
        By contrast, when creating a mapping using
        MAP_SHARED_VALIDATE, the kernel verifies all passed flags
        are known and fails the mapping with the error EOPNOTSUPP
        for unknown flags.  This mapping type is also required to
        be able to use some mapping flags (e.g., MAP_SYNC).

        MAP_PRIVATE
        Create a private copy-on-write mapping.  Updates to the
        mapping are not visible to other processes mapping the
        same file, and are not carried through to the underlying
        file.  It is unspecified whether changes made to the file
        after the mmap() call are visible in the mapped region.

        Both MAP_SHARED and MAP_PRIVATE are described in POSIX.1-2001 and
        POSIX.1-2008.  MAP_SHARED_VALIDATE is a Linux extension.

        In addition, zero or more of the following values can be ORed in
        flags:

        MAP_32BIT (since Linux 2.4.20, 2.6)
        Put the mapping into the first 2 Gigabytes of the process
        address space.  This flag is supported only on x86-64, for
        64-bit programs.  It was added to allow thread stacks to
        be allocated somewhere in the first 2 GB of memory, so as
        to improve context-switch performance on some early 64-bit
        processors.  Modern x86-64 processors no longer have this
        performance problem, so use of this flag is not required
        on those systems.  The MAP_32BIT flag is ignored when
        MAP_FIXED is set.

        MAP_ANON
        Synonym for MAP_ANONYMOUS; provided for compatibility with
        other implementations.

        MAP_ANONYMOUS
        The mapping is not backed by any file; its contents are
        initialized to zero.  The fd argument is ignored; however,
        some implementations require fd to be -1 if MAP_ANONYMOUS
        (or MAP_ANON) is specified, and portable applications
        should ensure this.  The offset argument should be zero.
        The use of MAP_ANONYMOUS in conjunction with MAP_SHARED is
        supported on Linux only since kernel 2.4.

        MAP_DENYWRITE
        This flag is ignored.  (Long ago—Linux 2.0 and earlier—it
        signaled that attempts to write to the underlying file
        should fail with ETXTBSY.  But this was a source of
        denial-of-service attacks.)

        MAP_EXECUTABLE
        This flag is ignored.

        MAP_FILE
        Compatibility flag.  Ignored.

        MAP_FIXED
        Don't interpret addr as a hint: place the mapping at
        exactly that address.  addr must be suitably aligned: for
        most architectures a multiple of the page size is
        sufficient; however, some architectures may impose
        additional restrictions.  If the memory region specified
        by addr and length overlaps pages of any existing
        mapping(s), then the overlapped part of the existing
        mapping(s) will be discarded.  If the specified address
        cannot be used, mmap() will fail.

        Software that aspires to be portable should use the
        MAP_FIXED flag with care, keeping in mind that the exact
        layout of a process's memory mappings is allowed to change
        significantly between kernel versions, C library versions,
        and operating system releases.  Carefully read the
        discussion of this flag in NOTES!

        MAP_FIXED_NOREPLACE (since Linux 4.17)
        This flag provides behavior that is similar to MAP_FIXED
        with respect to the addr enforcement, but differs in that
        MAP_FIXED_NOREPLACE never clobbers a preexisting mapped
        range.  If the requested range would collide with an
        existing mapping, then this call fails with the error
        EEXIST.  This flag can therefore be used as a way to
        atomically (with respect to other threads) attempt to map
        an address range: one thread will succeed; all others will
        report failure.

        Note that older kernels which do not recognize the
        MAP_FIXED_NOREPLACE flag will typically (upon detecting a
        collision with a preexisting mapping) fall back to a "non-
        MAP_FIXED" type of behavior: they will return an address
        that is different from the requested address.  Therefore,
        backward-compatible software should check the returned
        address against the requested address.

        MAP_GROWSDOWN
        This flag is used for stacks.  It indicates to the kernel
        virtual memory system that the mapping should extend
        downward in memory.  The return address is one page lower
        than the memory area that is actually created in the
        process's virtual address space.  Touching an address in
        the "guard" page below the mapping will cause the mapping
        to grow by a page.  This growth can be repeated until the
        mapping grows to within a page of the high end of the next
        lower mapping, at which point touching the "guard" page
        will result in a SIGSEGV signal.

        MAP_HUGETLB (since Linux 2.6.32)
        Allocate the mapping using "huge" pages.  See the Linux
        kernel source file
        Documentation/admin-guide/mm/hugetlbpage.rst for further
        information, as well as NOTES, below.

        MAP_HUGE_2MB, MAP_HUGE_1GB (since Linux 3.8)
        Used in conjunction with MAP_HUGETLB to select alternative
        hugetlb page sizes (respectively, 2 MB and 1 GB) on
        systems that support multiple hugetlb page sizes.

        More generally, the desired huge page size can be
        configured by encoding the base-2 logarithm of the desired
        page size in the six bits at the offset MAP_HUGE_SHIFT.
        (A value of zero in this bit field provides the default
        huge page size; the default huge page size can be
        discovered via the Hugepagesize field exposed by
        /proc/meminfo.)  Thus, the above two constants are defined
        as:

        #define MAP_HUGE_2MB    (21 << MAP_HUGE_SHIFT)
        #define MAP_HUGE_1GB    (30 << MAP_HUGE_SHIFT)

        The range of huge page sizes that are supported by the
        system can be discovered by listing the subdirectories in
        /sys/kernel/mm/hugepages.

        MAP_LOCKED (since Linux 2.5.37)
        Mark the mapped region to be locked in the same way as
        mlock(2).  This implementation will try to populate
        (prefault) the whole range but the mmap() call doesn't
        fail with ENOMEM if this fails.  Therefore major faults
        might happen later on.  So the semantic is not as strong
        as mlock(2).  One should use mmap() plus mlock(2) when
        major faults are not acceptable after the initialization
        of the mapping.  The MAP_LOCKED flag is ignored in older
        kernels.

        MAP_NONBLOCK (since Linux 2.5.46)
        This flag is meaningful only in conjunction with
        MAP_POPULATE.  Don't perform read-ahead: create page
        tables entries only for pages that are already present in
        RAM.  Since Linux 2.6.23, this flag causes MAP_POPULATE to
        do nothing.  One day, the combination of MAP_POPULATE and
        MAP_NONBLOCK may be reimplemented.

        MAP_NORESERVE
        Do not reserve swap space for this mapping.  When swap
        space is reserved, one has the guarantee that it is
        possible to modify the mapping.  When swap space is not
        reserved one might get SIGSEGV upon a write if no physical
        memory is available.  See also the discussion of the file
        /proc/sys/vm/overcommit_memory in proc(5).  In kernels
        before 2.6, this flag had effect only for private writable
        mappings.

        MAP_POPULATE (since Linux 2.5.46)
        Populate (prefault) page tables for a mapping.  For a file
        mapping, this causes read-ahead on the file.  This will
        help to reduce blocking on page faults later.  The mmap()
        call doesn't fail if the mapping cannot be populated (for
        example, due to limitations on the number of mapped huge
        pages when using MAP_HUGETLB).  MAP_POPULATE is supported
        for private mappings only since Linux 2.6.23.

        MAP_STACK (since Linux 2.6.27)
        Allocate the mapping at an address suitable for a process
        or thread stack.

        This flag is currently a no-op on Linux.  However, by
        employing this flag, applications can ensure that they
        transparently obtain support if the flag is implemented in
        the future.  Thus, it is used in the glibc threading
        implementation to allow for the fact that some
        architectures may (later) require special treatment for
        stack allocations.  A further reason to employ this flag
        is portability: MAP_STACK exists (and has an effect) on
        some other systems (e.g., some of the BSDs).

        MAP_SYNC (since Linux 4.15)
        This flag is available only with the MAP_SHARED_VALIDATE
        mapping type; mappings of type MAP_SHARED will silently
        ignore this flag.  This flag is supported only for files
        supporting DAX (direct mapping of persistent memory).  For
        other files, creating a mapping with this flag results in
        an EOPNOTSUPP error.

        Shared file mappings with this flag provide the guarantee
        that while some memory is mapped writable in the address
        space of the process, it will be visible in the same file
        at the same offset even after the system crashes or is
        rebooted.  In conjunction with the use of appropriate CPU
        instructions, this provides users of such mappings with a
        more efficient way of making data modifications
        persistent.

        MAP_UNINITIALIZED (since Linux 2.6.33)
        Don't clear anonymous pages.  This flag is intended to
        improve performance on embedded devices.  This flag is
        honored only if the kernel was configured with the
        CONFIG_MMAP_ALLOW_UNINITIALIZED option.  Because of the
        security implications, that option is normally enabled
        only on embedded devices (i.e., devices where one has
        complete control of the contents of user memory).

        Of the above flags, only MAP_FIXED is specified in POSIX.1-2001
        and POSIX.1-2008.  However, most systems also support
        MAP_ANONYMOUS (or its synonym MAP_ANON).*/
        flags: Int = MAP_SHARED,

        /** offset must be a multiple
        of the page size as returned by sysconf(_SC_PAGE_SIZE).*/
        offset: off_t = 0L,
    ): CPointer<CArrayPointerVar<ByteVar>> {

        require(offset % sysconf(_SC_PAGE_SIZE).toLong() == 0L) { "offset must be a multiple of the page size as returned by sysconf(_SC_PAGE_SIZE)." }

        return mmap_base(
            fd = fd,
            __len = len,
            __prot = prot,
            __flags = flags,
            __offset = offset
        ).reinterpret<CArrayPointerVar<ByteVar>>()
    }

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
            /**
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

        //todo: better FILE* handling

        /** lean on getline to read a file into a sequence of CharSeries */
        fun readLinesSeq(path: String): Sequence<String> = memScoped {

            val file = PosixFile(path)
            val fp = fdopen(file.fd, "r")
            val line: CPointerVarOf<CPointer<ByteVarOf<Byte>>> = alloc()
            val len: ULongVarOf<size_t> = alloc()
            len.value = 0u
            var read: ssize_t = 0L
            return sequence<String> {
                while (true) {
                    read = getline(line.ptr, len.ptr, fp)
                    if (read == -1L) break
                    yield(/*CharSeries*/line.value!!.toKString().trim())
                }
                free(line.value)
                if (ferror(fp) != 0) {
                    perror("ferror")
                    exit(1)
                }
            }.also { file.close().also { fclose(fp) } }

        }

        fun readLines(path: String): List<String> = memScoped {
            val file = PosixFile(path)
            val fp = fdopen(file.fd, "r")
            val line: CPointerVarOf<CPointer<ByteVarOf<Byte>>> = alloc()
            val len: ULongVarOf<size_t> = alloc()
            len.value = 0u
            var read: ssize_t = 0L
            val list: MutableList<String> = mutableListOf<String>()

            while (true) {
                read = getline(line.ptr, len.ptr, fp)
                if (read == -1L) break
                list.add((line.value!!.toKString().trim()))
            }
            free(line.value)
            if (ferror(fp) != 0) {
                perror("ferror")
                exit(1)
            }
            return list.also { file.close().also { fclose(fp) } }
        }

        fun readAllBytes(filename: String): ByteArray = memScoped {
            val file = PosixFile(filename)
            val stat = statk(filename)
            val len = stat.st_size.convert<Int>()
            val buf = allocArray<ByteVar>(len)
            val read = read(file.fd, buf, len.convert())
            HasPosixErr.posixRequires(read.toLong() == len.toLong()) { "readAllBytes $filename" }
            file.close()
            ByteArray(len) { buf[it] }
        }
        fun readString(filename: String): String = readAllBytes(filename).decodeToString()
        fun writeBytes(filename: String, bytes: ByteArray) = memScoped {
            val file = PosixFile(filename)
            val len = bytes.size
            val buf = allocArray<ByteVar>(len)
            bytes.forEachIndexed { index, byte -> buf[index] = byte }
            val written = write(file.fd, buf, len.convert())
            HasPosixErr.posixRequires(written.toLong() == len.toLong()) { "writeAllBytes $filename" }
            file.close()
        }
        /**
         * writes \n terminated lines to a file
         */
        fun writeLines(filename: String, lines: List<String>) = memScoped {
            val file = PosixFile(filename)
            lines.forEach { line ->
                val len = line.length
                val buf = line.plus('\n').cstr.getPointer(this)
                val written = write(file.fd, buf, len.inc().convert())
                HasPosixErr.posixRequires(written.toLong() == len.inc().toLong()) { "writeAllLines $filename" }
            }.also {
                file.close()
            }
        }
        fun writeString(filename: String, string: String) = writeBytes(filename, string.encodeToByteArray())

    }
}
