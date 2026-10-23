package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.Volume
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** An independent on-disk oracle: no mounted filesystem or Linux filesystem driver is used. */
class BtrfsFilesystemOracleTest {
    private val imageBytes = 128L * 1024 * 1024
    private val processes = JvmProcessOperations()

    @Test
    fun common_writer_image_passes_native_check_and_restores_exact_files() = runBlocking {
        val directory = Files.createTempDirectory("trikeshed-btrfs-writer-")
        try {
            val image = directory.resolve("written.img")
            val entries = entries()
            val root = metadata(BtrfsImageEntry("", mode = 0x41e8u)).copy(uid = 2101u, gid = 2102u)
            volume(image, create = true) {
                BtrfsFilesystemImage.write(it, entries, label = "trikeshed-oracle", rootMetadata = root)
            }
            val receipt = BtrfsProgsOracle.checkReadOnly(image.toString(), requireBtrfs())
            assertTrue(receipt.checkPassed, "${receipt.outcome}: ${receipt.failure}\n${receipt.stdout}\n${receipt.stderr}")
            assertEquals(0, receipt.exitCode)
            assertNotNull(receipt.version)
            assertTrue("--check-data-csum" in receipt.command)
            val tree = command(requireBtrfs(), "inspect-internal", "dump-tree", "-t", "5", image.toString())
            assertTrue(Regex("""(?m)^node \d+ level [1-8] """).containsMatchIn(tree),
                "The filesystem tree must contain an internal node, not just a leaf")
            assertTrue("mode 100750 " in tree, "Native tools must read the authored file permissions")
            assertTrue("mtime 1723456789.123456789 " in tree,
                "Native tools must read the authored nanosecond modification time")
            val native = nativeMetadata(tree)
            assertEquals(entries.size + 1, native.size)
            for (entry in entries + root) assertMetadata(entry, native.getValue(entry.path))

            val restored = directory.resolve("restored")
            Files.createDirectory(restored)
            command(requireBtrfs(), "restore", image.toString(), restored.toString())
            assertFiles(entries, restored)

            val reopened = volume(image) { BtrfsFilesystemImage.read(it) }
            assertEquals("trikeshed-oracle", reopened.label)
            assertEquals(16, reopened.fsid.size)
            assertEntries(entries, reopened.entries)
            assertMetadata(root, reopened.rootMetadata)
            for (entry in reopened.entries) assertMetadata(native.getValue(entry.path), entry)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun common_reader_opens_native_mkfs_rootdir_with_multiblock_file() = runBlocking {
        val directory = Files.createTempDirectory("trikeshed-btrfs-reader-")
        try {
            val input = directory.resolve("input")
            Files.createDirectory(input)
            val entries = entries()
            for (entry in entries) {
                val path = input.resolve(entry.path)
                val data = entry.data
                if (data == null) Files.createDirectories(path)
                else {
                    Files.createDirectories(path.parent)
                    Files.write(path, data)
                }
            }
            // Creating children changes directory mtimes; set every timestamp after population.
            for (entry in entries) {
                val path = input.resolve(entry.path)
                Files.setAttribute(path, "unix:mode", entry.mode.toInt() and 0xfff)
                Files.setLastModifiedTime(path, FileTime.from(Instant.ofEpochSecond(
                    entry.modifiedSeconds, entry.modifiedNanoseconds.toLong())))
            }
            Files.setAttribute(input, "unix:mode", 0x1e8)
            Files.setLastModifiedTime(input, FileTime.from(Instant.ofEpochSecond(1_723_000_001, 987_654_321)))
            val image = directory.resolve("native.img")
            mkfs(image, input)
            val receipt = BtrfsProgsOracle.checkReadOnly(image.toString(), requireBtrfs())
            assertTrue(receipt.checkPassed, "Native fixture rejected: ${receipt.failure}\n${receipt.stdout}\n${receipt.stderr}")
            val before = Files.size(image)
            val snapshot = volume(image) { BtrfsFilesystemImage.read(it) }
            assertEquals("native-oracle", snapshot.label)
            assertEquals(16, snapshot.fsid.size)
            val native = nativeMetadata(command(requireBtrfs(), "inspect-internal", "dump-tree", "-t", "5", image.toString()))
            assertEquals(entries.size + 1, native.size)
            // mkfs.btrfs 6.14 imports timestamps at whole-second precision. The on-image
            // native records are the metadata oracle; source payload bytes remain exact.
            assertEntries(entries.map { native.getValue(it.path).copy(data = it.data) }, snapshot.entries)
            assertMetadata(native.getValue(""), snapshot.rootMetadata)
            for (entry in snapshot.entries) assertMetadata(native.getValue(entry.path), entry)
            assertEquals(before, Files.size(image), "Read-only reopen changed image length")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun native_hardlinks_are_rejected_without_modifying_the_image() = runBlocking {
        unsupportedNativeImage("links 2 ", "hard links") { input ->
            Files.createLink(input.resolve("alias.txt"), input.resolve("original.txt"))
        }
    }

    @Test
    fun native_extended_attributes_are_rejected_without_modifying_the_image() = runBlocking {
        unsupportedNativeImage("XATTR_ITEM", "extended attributes") { input ->
            val attributes = assertNotNull(Files.getFileAttributeView(input.resolve("original.txt"),
                UserDefinedFileAttributeView::class.java), "Native fixture requires user xattr support")
            val bytes = "preserve-or-reject".encodeToByteArray()
            assertEquals(bytes.size, attributes.write("trikeshed", java.nio.ByteBuffer.wrap(bytes)))
        }
    }

    @Test
    fun native_checker_rejects_invalid_filesystem_bytes() = runBlocking {
        val image = Files.createTempFile("trikeshed-btrfs-invalid-", ".img")
        try {
            RandomAccessFile(image.toFile(), "rw").use { it.setLength(imageBytes) }
            val receipt = BtrfsProgsOracle.checkReadOnly(image.toString(), requireBtrfs())
            assertEquals(BtrfsProgsOracleOutcome.REJECTED, receipt.outcome,
                "${receipt.failure}\n${receipt.stderr}")
            assertFalse(receipt.checkPassed)
            assertTrue(assertNotNull(receipt.exitCode) != 0)
            assertTrue(receipt.stdout.isNotBlank() || receipt.stderr.isNotBlank())
        } finally {
            Files.deleteIfExists(image)
        }
    }

    private fun entries() = (listOf(
        BtrfsImageEntry("notes"),
        BtrfsImageEntry("empty-directory"),
        BtrfsImageEntry("fanout"),
        BtrfsImageEntry("hello.txt", "userspace Btrfs\n".encodeToByteArray(), mode = 0x81e8u),
        BtrfsImageEntry("empty", byteArrayOf()),
        BtrfsImageEntry("notes/space λ.txt", "directory names and UTF-8 bytes\n".encodeToByteArray()),
        BtrfsImageEntry("notes/large.bin", ByteArray(256 * 1024 + 317) { ((it * 73 + it / 251) and 255).toByte() }),
    ) + List(200) { index ->
        BtrfsImageEntry("fanout/file-${index.toString().padStart(3, '0')}.txt",
            "independent leaf split payload $index\n".encodeToByteArray())
    }).map(::metadata)

    private fun metadata(entry: BtrfsImageEntry) = entry.copy(
        uid = 1234u, gid = 4321u,
        modifiedSeconds = 1_723_456_789, modifiedNanoseconds = 123_456_789,
        accessedSeconds = 1_723_456_701, accessedNanoseconds = 234_567_891,
        changedSeconds = 1_723_456_702, changedNanoseconds = 345_678_912,
        createdSeconds = 1_723_456_703, createdNanoseconds = 456_789_123,
    )

    private fun assertMetadata(expected: BtrfsImageEntry, actual: BtrfsImageEntry) {
        assertEquals(expected.copy(data = null), actual.copy(data = null), "${expected.path} inode metadata")
    }

    /** Parse the independent tool's inode/ref records, including the otherwise implicit root. */
    private fun nativeMetadata(tree: String): Map<String, BtrfsImageEntry> {
        val inodes = linkedMapOf<Long, BtrfsImageEntry>()
        val parents = mutableMapOf<Long, Pair<Long, String>>()
        val items = Regex("""(?ms)^\titem \d+ key \((\d+) (INODE_ITEM|INODE_REF) (\d+)\)[^\n]*\n(.*?)(?=^\titem |\z)""")
        for (item in items.findAll(tree)) {
            val id = item.groupValues[1].toLong()
            val body = item.groupValues[4]
            if (item.groupValues[2] == "INODE_REF") {
                val name = assertNotNull(Regex("""(?m)^\t\tindex \d+ namelen (\d+) name: (.*)$""").find(body))
                parents[id] = item.groupValues[3].toLong() to nativeName(name.groupValues[2], name.groupValues[1].toInt())
                continue
            }
            val ownership = assertNotNull(Regex("""mode ([0-7]+) links \d+ uid (\d+) gid (\d+)""").find(body))
            fun time(name: String): Pair<Long, Int> {
                val value = assertNotNull(Regex("""(?m)^\t\t$name (-?\d+)\.(\d+) """).find(body))
                return value.groupValues[1].toLong() to value.groupValues[2].toInt()
            }
            val atime = time("atime"); val ctime = time("ctime"); val mtime = time("mtime"); val otime = time("otime")
            inodes[id] = BtrfsImageEntry("", mode = ownership.groupValues[1].toUInt(8),
                uid = ownership.groupValues[2].toUInt(), gid = ownership.groupValues[3].toUInt(),
                modifiedSeconds = mtime.first, modifiedNanoseconds = mtime.second,
                accessedSeconds = atime.first, accessedNanoseconds = atime.second,
                changedSeconds = ctime.first, changedNanoseconds = ctime.second,
                createdSeconds = otime.first, createdNanoseconds = otime.second)
        }
        fun path(id: Long, seen: Set<Long> = emptySet()): String {
            if (id == 256L) return ""
            assertTrue(id !in seen, "Native fixture contains a directory cycle")
            val (parent, name) = assertNotNull(parents[id], "Native inode $id has no reference")
            return listOf(path(parent, seen + id), name).filter { it.isNotEmpty() }.joinToString("/")
        }
        assertTrue(256L in inodes, "Native dump must contain the root inode")
        return inodes.map { (id, inode) -> path(id).let { it to inode.copy(path = it) } }.toMap()
    }

    /** dump-tree escapes spaces and non-ASCII filename bytes, e.g. space\ \316\273.txt. */
    private fun nativeName(escaped: String, size: Int): String {
        val bytes = ByteArrayOutputStream()
        var position = 0
        while (position < escaped.length) {
            if (escaped[position] != '\\') {
                val end = escaped.indexOf('\\', position).takeIf { it >= 0 } ?: escaped.length
                bytes.write(escaped.substring(position, end).encodeToByteArray())
                position = end
                continue
            }
            position++
            assertTrue(position < escaped.length, "Truncated native filename escape")
            val character = escaped[position]
            if (character in '0'..'7') {
                assertTrue(position + 3 <= escaped.length, "Truncated native filename octal byte")
                val octal = escaped.substring(position, position + 3)
                assertTrue(octal.all { it in '0'..'7' }, "Invalid native filename octal byte")
                val value = octal.toInt(8)
                assertTrue(value <= 255, "Native filename escape exceeds one byte")
                bytes.write(value)
                position += 3
            } else {
                bytes.write(when (character) {
                    'a' -> 7; 'b' -> 8; 't' -> 9; 'n' -> 10; 'v' -> 11; 'f' -> 12; 'r' -> 13
                    ' ', '\\', '\'', '"', '?' -> character.code
                    else -> error("Unknown native filename escape: $character")
                })
                position++
            }
        }
        assertEquals(size, bytes.size(), "Native inode reference filename length")
        return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    private fun assertEntries(expected: List<BtrfsImageEntry>, actual: List<BtrfsImageEntry>) {
        assertEquals(expected.map { it.path }.toSet(), actual.map { it.path }.toSet())
        assertEquals(expected.size, actual.size, "Duplicate directory entries")
        for (entry in expected) {
            val found = actual.single { it.path == entry.path }
            if (entry.data == null) assertEquals(null, found.data, "${entry.path} is a directory")
            else assertContentEquals(entry.data, found.data, entry.path)
            assertEquals(entry.mode, found.mode, "${entry.path} mode")
            assertEquals(entry.modifiedSeconds, found.modifiedSeconds, "${entry.path} mtime seconds")
            assertEquals(entry.modifiedNanoseconds, found.modifiedNanoseconds, "${entry.path} mtime nanoseconds")
        }
    }

    private fun assertFiles(expected: List<BtrfsImageEntry>, root: Path) {
        val paths = Files.walk(root).use { files ->
            files.filter { it != root }.map { root.relativize(it).toString().replace(File.separatorChar, '/') }.toList().toSet()
        }
        assertEquals(expected.map { it.path }.toSet(), paths)
        for (entry in expected) {
            val path = root.resolve(entry.path)
            if (entry.data == null) assertTrue(Files.isDirectory(path), entry.path)
            else assertContentEquals(entry.data, Files.readAllBytes(path), entry.path)
        }
    }

    private suspend fun <T> volume(path: Path, create: Boolean = false, block: suspend (Volume) -> T): T {
        val channel = UringChannels.open(entries = 8)
        try {
            val volume = BtrfsUringFileVolume.open(channel, path.toString(), capacity = imageBytes / 4096,
                create = create, readOnly = !create, backendReport = currentNioCapabilityReport())
            try {
                return block(volume)
            } finally {
                volume.drain()
            }
        } finally {
            channel.drain()
        }
    }

    private fun requireBtrfs(): String = assertNotNull(BtrfsProgsOracle.locateBtrfs().second,
        "btrfs-progs is required for native interoperability tests; configure TRIKESHED_BTRFS_PROGS")

    private fun requireMkfs(): String {
        val btrfs = File(requireBtrfs())
        val candidates = listOf(File(btrfs.parentFile, "mkfs.btrfs"), File(btrfs.parentFile, "../sbin/mkfs.btrfs")) +
            System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it, "mkfs.btrfs") }
        return assertNotNull(candidates.firstOrNull { it.isFile && it.canExecute() },
            "mkfs.btrfs is required for native interoperability tests").canonicalPath
    }

    private suspend fun mkfs(image: Path, input: Path) {
        RandomAccessFile(image.toFile(), "rw").use { it.setLength(imageBytes) }
        command(requireMkfs(), "--force", "--data", "single", "--metadata", "single",
            "--nodesize", "16384", "--sectorsize", "4096", "--csum", "crc32c",
            "--label", "native-oracle", "--rootdir", input.toString(), image.toString())
    }

    private suspend fun unsupportedNativeImage(evidence: String, reason: String, populate: (Path) -> Unit) {
        val directory = Files.createTempDirectory("trikeshed-btrfs-unsupported-")
        try {
            val input = Files.createDirectory(directory.resolve("input"))
            Files.write(input.resolve("original.txt"), "native fixture bytes".encodeToByteArray())
            populate(input)
            val image = directory.resolve("native.img")
            mkfs(image, input)
            val check = BtrfsProgsOracle.checkReadOnly(image.toString(), requireBtrfs())
            assertTrue(check.checkPassed, "Native fixture must be valid: ${check.failure}\n${check.stderr}")
            val tree = command(requireBtrfs(), "inspect-internal", "dump-tree", "-t", "5", image.toString())
            assertTrue(evidence in tree, "mkfs must actually retain the unsupported feature: $evidence")
            val before = digest(image)
            val failure = assertFailsWith<IllegalArgumentException> { volume(image) { BtrfsFilesystemImage.read(it) } }
            assertTrue(failure.message.orEmpty().contains(reason), failure.message)
            assertContentEquals(before, digest(image), "Rejected native image was modified")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun digest(image: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(image).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private suspend fun command(executable: String, vararg args: String) = withTimeout(60_000) {
        val result = processes.exec(executable, args.toList())
        assertEquals(0, result.exitCode,
            "$executable ${args.joinToString(" ")}\n${result.stdout.decodeToString()}\n${result.stderr.decodeToString()}")
        result.stdout.decodeToString()
    }
}
