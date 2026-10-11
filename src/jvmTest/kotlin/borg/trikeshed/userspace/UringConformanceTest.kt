package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.WritableByteChannel
import borg.trikeshed.userspace.nio.channels.ReadableByteChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.ebpf.UringEbpfContextLayout
import borg.trikeshed.userspace.nio.ebpf.UringEbpfInsn
import borg.trikeshed.userspace.nio.ebpf.UringEbpfPhase
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFails

class UringConformanceTest {
    @Test
    fun statx_preserves_slice_bounds_and_rejects_invalid_buffers() = runBlocking {
        val path = Files.createTempFile("trikeshed-statx-", ".bin")
        Files.write(path, byteArrayOf(2, 3, 5, 7))
        val backend = openUserspaceChannelBackend(8)
        val facade = FunctionalUringFacade(8, backend)
        val fd = UringFileConformance.one(facade, Submissions.openat(path.toString(), 0, 81)).res
        assertTrue(fd >= 0)
        try {
            // statx(fd, "", AT_EMPTY_PATH): the empty path's NUL, then struct statx.
            val request = Submissions.statx(fd, "", UringOp.AT_EMPTY_PATH, UringOp.STATX_BASIC_STATS, 82)
            assertEquals(0, UringFileConformance.one(facade, request).res)
            val metadata = Statx.of(request)
            assertEquals(4uL, metadata.stx_size)
            assertEquals(UringOp.S_IFREG, metadata.stx_mode and UringOp.S_IFMT)
            // The JDK's unix attribute view of the same file is the oracle for the other fields.
            val unix = Files.readAttributes(path, "unix:mode,nlink,uid,ino,dev")
            assertEquals(unix["mode"] as Int, metadata.stx_mode)
            assertEquals((unix["nlink"] as Int).toUInt(), metadata.stx_nlink)
            assertEquals((unix["uid"] as Int).toUInt(), metadata.stx_uid)
            assertEquals((unix["ino"] as Long).toULong(), metadata.stx_ino)
            val byPath = Submissions.statx(UringOp.AT_FDCWD, path.toString(), UringOp.AT_SYMLINK_NOFOLLOW, UringOp.STATX_BASIC_STATS, 83)
            assertEquals(0, UringFileConformance.one(facade, byPath).res)
            assertEquals(metadata.stx_ino, Statx.of(byPath).stx_ino)
            assertEquals(metadata.stx_dev_major to metadata.stx_dev_minor, Statx.of(byPath).stx_dev_major to Statx.of(byPath).stx_dev_minor)
            val short = request.copy(buffer = ByteBuffer(UringOp.STATX_SIZE), userData = 84)
            assertEquals(-22, UringFileConformance.one(facade, short).res)
            val readOnly = request.copy(buffer = ByteBuffer(1 + UringOp.STATX_SIZE).asReadOnlyBuffer(), userData = 85)
            assertEquals(-22, UringFileConformance.one(facade, readOnly).res)
            assertEquals(-22, UringFileConformance.one(facade, request.copy(buffer = ByteBuffer(1 + UringOp.STATX_SIZE), addr = 1, userData = 86)).res)
            assertEquals(-22, UringFileConformance.one(facade, request.copy(buffer = ByteBuffer(1 + UringOp.STATX_SIZE), operationFlags = 0x40000000, userData = 87)).res)
        } finally {
            try { UringFileConformance.one(facade, Submissions.close(fd, 85)) } finally {
                backend.close()
                Files.deleteIfExists(path)
            }
        }
    }

    @Test
    fun emulated_disposable_file_contract() = runBlocking {
        val backend = openJvmEmulatedChannelBackend()
        assertEquals(0L, backend.nativeCapabilities)
        val path = Files.createTempFile("trikeshed-uring-emulated-", ".bin")
        try {
            UringFileConformance.file(FunctionalUringFacade(8, backend), path.toString())
        } finally {
            backend.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun selected_backend_disposable_file_contract() = runBlocking {
        val backend = openUserspaceChannelBackend(8)
        println("uring selection: ${backend.availability}; capabilities=${backend.capabilities}; native=${backend.nativeCapabilities}")
        val report = currentNioCapabilityReport()
        println("uring report: ${report.toReadable()}; probe=${report.uringProbe?.state}")
        if (!System.getProperty("os.name").lowercase().contains("linux")) {
            assertEquals(0L, backend.nativeCapabilities, "non-Linux must never claim kernel uring")
            assertEquals("uring_emulated", report.backendName)
            assertEquals(false, report.ioUringAvailable)
            assertEquals(UringProbeState.HOST_UNSUPPORTED, report.uringProbe?.state)
        }
        val path = Files.createTempFile("trikeshed-uring-selected-", ".bin")
        try {
            UringFileConformance.file(FunctionalUringFacade(8, backend), path.toString())
        } finally {
            backend.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun ebpf_admission_protects_real_file_and_completion_preserves_performed_io() = runBlocking {
        val rejectToken = UringEbpfProgram.of("submission-token", UringEbpfPhase.SUBMIT, longArrayOf(
            UringEbpfInsn.loadCtx64(UringEbpfInsn.R0, UringEbpfContextLayout.USER_DATA),
            UringEbpfInsn.jne64Imm(UringEbpfInsn.R0, 22, 2),
            UringEbpfInsn.mov64Imm(UringEbpfInsn.R0, -13),
            UringEbpfInsn.exit(),
            UringEbpfInsn.mov64Imm(UringEbpfInsn.R0, 0),
            UringEbpfInsn.exit(),
        ))
        val complete = UringEbpfProgram.of("completion-status", UringEbpfPhase.COMPLETE,
            longArrayOf(UringEbpfInsn.mov64Imm(UringEbpfInsn.R0, -13), UringEbpfInsn.exit()))
        val backend = openJvmEmulatedChannelBackend()
        val facade = FunctionalUringFacade(8, backend, ebpfPrograms = listOf(rejectToken, complete))
        val path = Files.createTempFile("trikeshed-uring-ebpf-", ".bin")
        try {
            val fd = UringFileConformance.one(facade, Submissions.openat(path.toString(), 2, 20)).res
            assertTrue(fd >= 0)
            val admitted = ByteBuffer(byteArrayOf(1, 2, 3))
            assertEquals(3, UringFileConformance.one(facade,
                Submissions.write(fd, 0, 3, 0, 21).copy(buffer = admitted)).res)
            assertEquals(3, admitted.position())
            val denied = ByteBuffer(byteArrayOf(8, 9))
            assertEquals(-13, UringFileConformance.one(facade,
                Submissions.write(fd, 0, 2, 0, 22).copy(buffer = denied)).res)
            assertEquals(0, denied.position())
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(path))
            assertEquals(0, UringFileConformance.one(facade, Submissions.close(fd, 23)).res)
            assertEquals(-9, UringFileConformance.one(facade,
                Submissions.read(fd, 0, 1, 0, 24).copy(buffer = ByteBuffer(1))).res)
        } finally {
            backend.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun file_handle_and_submission_share_descriptor_lifetime() = runBlocking {
        val backend = openJvmEmulatedChannelBackend()
        val facade = FunctionalUringFacade(2, backend)
        val path = Files.createTempFile("trikeshed-uring-descriptor-", ".bin")
        val file = FilesImpl.open(path.toString(), readOnly = false)
        try {
            assertTrue(file.isOpen())
            assertEquals(2, UringFileConformance.one(facade,
                Submissions.write(file.id, 0, 2, 0, 41).copy(buffer = ByteBuffer(byteArrayOf(7, 8)))).res)
            assertEquals(2L, file.size())
            assertEquals(0, UringFileConformance.one(facade, Submissions.close(file.id, 42)).res)
            assertTrue(!file.isOpen())
            assertEquals(-9, UringFileConformance.one(facade, Submissions.close(file.id, 43)).res)
        } finally {
            file.close()
            backend.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun drain_executes_prepared_file_write_before_descriptor_cleanup() = runBlocking {
        val backend = openJvmEmulatedChannelBackend()
        val facade = FunctionalUringFacade(2, backend)
        val path = Files.createTempFile("trikeshed-uring-drain-", ".bin")
        try {
            val fd = UringFileConformance.one(facade, Submissions.openat(path.toString(), 2, 51)).res
            val buffer = ByteBuffer(byteArrayOf(3, 4, 5))
            facade.enqueue(Submissions.write(fd, 0, 3, 0, 52).copy(buffer = buffer))
            facade.drain()
            assertContentEquals(byteArrayOf(3, 4, 5), Files.readAllBytes(path))
            assertEquals(3, buffer.position())
            assertEquals(listOf(SelectionResult(3, 52)), facade.peek())
            assertTrue(!FileImpl(fd).isOpen())
        } finally {
            backend.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun file_channel_open_and_partial_transfer_use_real_submissions() {
        val directory = Files.createTempDirectory("trikeshed-uring-channel-")
        val sourcePath = directory.resolve("source.bin")
        val targetPath = directory.resolve("target.bin")
        val options = arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
        try {
            FileChannel.open(sourcePath.toString(), *options).use { source ->
                assertFails { FileChannel.open(sourcePath.toString(), *options) }
                assertEquals(5, source.write(ByteBuffer(byteArrayOf(1, 2, 3, 4, 5))))
                source.force(true)
                source.position(0)
                val read = ByteBuffer(9)
                assertEquals(5, source.read(read))
                assertEquals(-1, source.read(read), "FileChannel translates uring EOF to its NIO return")
                assertEquals(5L, source.position())
                assertEquals(2, source.read(ByteBuffer(2), 1))
                assertEquals(5L, source.position(), "positional reads retain channel position")
                FileChannel.open(targetPath.toString(), *options).use { target ->
                    var writes = 0
                    val partialTarget = object : WritableByteChannel {
                        override fun isOpen(): Boolean = target.isOpen()
                        override fun close() = Unit
                        override fun write(src: ByteBuffer): Int {
                            if (writes++ > 0) return 0
                            val limit = src.limit()
                            try {
                                src.limit(src.position() + minOf(2, src.remaining()))
                                return target.write(src)
                            } finally { src.limit(limit) }
                        }
                    }
                    assertEquals(2L, source.transferTo(0, 5, partialTarget))
                    assertContentEquals(byteArrayOf(1, 2), Files.readAllBytes(targetPath))
                    source.position(0)
                    val partialSource = object : ReadableByteChannel {
                        override fun isOpen(): Boolean = source.isOpen()
                        override fun close() = Unit
                        override fun read(dst: ByteBuffer): Int {
                            val limit = dst.limit()
                            try {
                                dst.limit(dst.position() + minOf(2, dst.remaining()))
                                return source.read(dst)
                            } finally { dst.limit(limit) }
                        }
                    }
                    assertEquals(5L, target.transferFrom(partialSource, 0, 10))
                    assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), Files.readAllBytes(targetPath))
                }
            }
        } finally {
            Files.deleteIfExists(sourcePath)
            Files.deleteIfExists(targetPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun backend_close_releases_opened_descriptors() = runBlocking {
        val backend = openJvmEmulatedChannelBackend()
        val facade = FunctionalUringFacade(2, backend)
        val observer = openJvmEmulatedChannelBackend()
        val path = Files.createTempFile("trikeshed-uring-close-", ".bin")
        try {
            val fd = UringFileConformance.one(facade, Submissions.openat(path.toString(), 2, 31)).res
            assertTrue(fd >= 0)
            backend.close()
            assertEquals(-9, UringFileConformance.one(FunctionalUringFacade(2, observer),
                Submissions.read(fd, 0, 1, 0, 32).copy(buffer = ByteBuffer(1))).res)
        } finally {
            backend.close()
            observer.close()
            Files.deleteIfExists(path)
        }
    }

    /** MKDIRAT, OPENAT with a mode, RENAMEAT, UNLINKAT and GETDENTS, each checked against the JDK's view of the same tree. */
    @Test
    fun path_operations_and_getdents_agree_with_the_jdk() = runBlocking {
        val root = Files.createTempDirectory("trikeshed-dirops-")
        val backend = openUserspaceChannelBackend(8)
        val facade = FunctionalUringFacade(8, backend)
        suspend fun run(submission: UringOp.Companion.UringSubmission) = UringFileConformance.one(facade, submission).res
        try {
            val sub = root.resolve("sub")
            assertEquals(0, run(Submissions.mkdirat(UringOp.AT_FDCWD, sub.toString(), 0x1c0, 1)))
            assertEquals(-17, run(Submissions.mkdirat(UringOp.AT_FDCWD, sub.toString(), 0x1c0, 2)))
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(sub)))
            val fd = run(Submissions.openat(root.resolve("a").toString(), UringOp.O_WRONLY or UringOp.O_CREAT or UringOp.O_EXCL, 3, 0x180))
            assertTrue(fd >= 0, "OPENAT failed: $fd")
            assertEquals(0, run(Submissions.close(fd, 4)))
            assertEquals(-17, run(Submissions.openat(root.resolve("a").toString(), UringOp.O_WRONLY or UringOp.O_CREAT or UringOp.O_EXCL, 5, 0x180)))
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("a"))))
            Files.write(root.resolve("b"), byteArrayOf(1))
            assertEquals(0, run(Submissions.renameat(UringOp.AT_FDCWD, root.resolve("a").toString(), UringOp.AT_FDCWD, root.resolve("c").toString(), 0, 6)))
            assertTrue(!Files.exists(root.resolve("a")) && Files.exists(root.resolve("c")))
            assertEquals(-2, run(Submissions.renameat(UringOp.AT_FDCWD, root.resolve("a").toString(), UringOp.AT_FDCWD, root.resolve("d").toString(), 0, 7)))
            Files.createSymbolicLink(root.resolve("link"), root.resolve("c"))
            assertEquals(-40, run(Submissions.openat(root.resolve("link").toString(), UringOp.O_RDONLY or UringOp.O_NOFOLLOW, 8)))

            // linux_dirent64 records: d_ino at 0, d_reclen at 16, d_type at 18, the NUL-terminated name at 19.
            val dir = run(Submissions.openat(root.toString(), UringOp.O_RDONLY or UringOp.O_DIRECTORY, 9))
            assertTrue(dir >= 0, "OPENAT failed: $dir")
            val listed = mutableMapOf<String, Pair<Long, Int>>()
            val bytes = ByteArray(64)
            while (true) {
                val count = run(Submissions.getdents(dir, ByteBuffer.wrap(bytes, 0, bytes.size), 10))
                assertTrue(count >= 0, "GETDENTS failed: $count")
                if (count == 0) break
                val records = java.nio.ByteBuffer.wrap(bytes, 0, count).order(java.nio.ByteOrder.nativeOrder())
                var at = 0
                while (at < count) {
                    var end = at + 19
                    while (bytes[end] != 0.toByte()) end++
                    listed[bytes.decodeToString(at + 19, end)] = records.getLong(at) to (bytes[at + 18].toInt() and 0xff)
                    at += records.getShort(at + 16).toInt() and 0xffff
                }
            }
            assertEquals(0, run(Submissions.close(dir, 11)))
            listed.remove("."); listed.remove("..")
            val expected = Files.list(root).use { names -> names.toList() }.associate { path ->
                val type = when {
                    Files.isSymbolicLink(path) -> 10
                    Files.isDirectory(path) -> 4
                    else -> 8
                }
                path.fileName.toString() to ((Files.getAttribute(path, "unix:ino", java.nio.file.LinkOption.NOFOLLOW_LINKS) as Long) to type)
            }
            assertEquals(expected, listed)

            assertEquals(0, run(Submissions.unlinkat(UringOp.AT_FDCWD, root.resolve("b").toString(), 0, 12)))
            assertTrue(!Files.exists(root.resolve("b")))
            // unlink(2) of a directory: EISDIR on Linux, EPERM on Darwin.
            assertEquals(if (JvmFileSyscalls.linux) -21 else -1, run(Submissions.unlinkat(UringOp.AT_FDCWD, sub.toString(), 0, 13)))
            assertEquals(0, run(Submissions.unlinkat(UringOp.AT_FDCWD, sub.toString(), UringOp.AT_REMOVEDIR, 14)))
            assertTrue(!Files.exists(sub))
        } finally {
            backend.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
