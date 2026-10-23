package borg.trikeshed.btrfs

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.Volume
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BtrfsFileSystemElementTest {
    private class Images {
        val directory: Path = Files.createTempDirectory("trikeshed-btrfs-fs-")
        val files = JvmFileOperations()
        val manifest = directory.resolve("volume.manifest").toString()
        fun config(name: String = "initial.img", blocks: Long = 32768) = BtrfsRaidImageConfig(
            BtrfsVolumeLayout.SINGLE, 4096, 1,
            s_[BtrfsRaidImageMember(1u, directory.resolve(name).toString(), blocks)],
        )

        suspend fun create(
            scope: CoroutineScope,
            queueCapacity: Int = 16,
            initialEntries: List<BtrfsImageEntry> = emptyList(),
        ): BtrfsFileSystemElement {
            val backing = BtrfsRaidRebalance.create(scope, files, manifest, config()) {
                BtrfsFilesystemImage.write(it, initialEntries, "userspace",
                    rootMetadata = BtrfsImageEntry("", uid = 123u, gid = 456u,
                        accessedSeconds = 100, createdSeconds = 200))
            }
            return BtrfsFileSystemElement.mount(scope, backing, queueCapacity)
        }

        suspend fun reopen(scope: CoroutineScope): BtrfsFileSystemElement =
            BtrfsFileSystemElement.mount(scope, BtrfsRaidRebalance.open(scope, files, manifest))

        fun delete() { directory.toFile().deleteRecursively() }
    }

    @Test
    fun native_filesystem_mutations_metadata_and_topology_survive_reopen() = runTest {
        val images = Images()
        var fs = images.create(this, initialEntries = listOf(BtrfsImageEntry("documents/drafts/a.txt",
            ByteArray(0), uid = 789u, gid = 1011u, accessedSeconds = 300, createdSeconds = 400)))
        try {
            val fsid = fs.snapshot().fsid
            assertEquals(BtrfsFileType.DIRECTORY, fs.stat("/")?.type)
            fs.setMode("/", 0x1e8u)
            fs.setModifiedTime("/", 1_600_000_000, 123)
            fs.mkdir("/documents/drafts", recursive = true)
            fs.write("/documents/drafts/a.txt", "alpha".encodeToByteArray())
            fs.write("/documents/drafts/a.txt", 7, "z".encodeToByteArray())
            assertContentEquals(byteArrayOf(97, 108, 112, 104, 97, 0, 0, 122), fs.read("/documents/drafts/a.txt"))
            fs.truncate("/documents/drafts/a.txt", 5)
            fs.setMode("/documents/drafts/a.txt", 0x180u)
            val beforeTimes = fs.generation()
            fs.setTimes("/documents/drafts/a.txt", accessedSeconds = 301, accessedNanoseconds = 456,
                modifiedSeconds = 1_700_000_000, modifiedNanoseconds = 123456789)
            assertEquals(beforeTimes + 1, fs.generation())
            fs.rename("/documents/drafts", "/documents/final")
            assertEquals(listOf("final"), fs.list("/documents"))
            fs.setMetadataCompression(BtrfsMetadataCompression.LZ4)
            val previousGeneration = fs.generation()
            val previousSpace = fs.statFs()
            fs.migrate(images.config("larger.img", 65536))
            assertTrue(fs.generation() > previousGeneration)
            val grownSpace = fs.statFs()
            assertEquals(previousSpace.totalBlocks + 32768, grownSpace.totalBlocks)
            assertEquals(previousSpace.freeDataBlocks + 32768, grownSpace.freeDataBlocks)
            assertEquals(grownSpace.freeDataBlocks, grownSpace.availableDataBlocks)
            fs.drain()

            fs = images.reopen(this)
            assertContentEquals(fsid, fs.snapshot().fsid)
            val root = assertNotNull(fs.stat("/"))
            assertEquals(0x41e8u, root.mode)
            assertEquals(123u, root.uid)
            assertEquals(456u, root.gid)
            assertEquals(100L, root.accessedSeconds)
            assertEquals(200L, root.createdSeconds)
            assertEquals(1_600_000_000L, root.modifiedSeconds)
            assertEquals(123, root.modifiedNanoseconds)
            assertEquals(BtrfsMetadataCompression.LZ4, fs.metadataCompression())
            assertEquals(65536L, fs.config().members[0].capacityBlocks)
            assertEquals("alpha", fs.read("/documents/final/a.txt").decodeToString())
            val stat = assertNotNull(fs.stat("/documents/final/a.txt"))
            assertEquals(BtrfsFileType.REGULAR, stat.type)
            assertEquals(5L, stat.size)
            assertEquals(0x8180u, stat.mode)
            assertEquals(1_700_000_000L, stat.modifiedSeconds)
            assertEquals(123456789, stat.modifiedNanoseconds)
            assertEquals(789u, stat.uid)
            assertEquals(1011u, stat.gid)
            assertEquals(301L, stat.accessedSeconds)
            assertEquals(456, stat.accessedNanoseconds)
            assertEquals(400L, stat.createdSeconds)
            fs.remove("/documents", recursive = true)
            fs.drain()
            fs = images.reopen(this)
            assertEquals(emptyList(), fs.list())
        } finally {
            fs.drain()
            images.delete()
        }
    }

    @Test
    fun rejected_operations_and_returned_arrays_cannot_mutate_the_committed_tree() = runTest {
        val images = Images()
        val fs = images.create(this)
        try {
            fs.mkdir("/directory")
            fs.write("/directory/file", byteArrayOf(1, 2, 3))
            val generation = fs.generation()
            assertEquals(BtrfsFileError.NOT_EMPTY,
                assertFailsWith<BtrfsFileSystemException> { fs.remove("/directory") }.error)
            assertEquals(BtrfsFileError.IS_DIRECTORY,
                assertFailsWith<BtrfsFileSystemException> {
                    fs.remove("/directory", expectedType = BtrfsFileType.REGULAR)
                }.error)
            assertEquals(BtrfsFileError.NOT_DIRECTORY,
                assertFailsWith<BtrfsFileSystemException> {
                    fs.remove("/directory/file", expectedType = BtrfsFileType.DIRECTORY)
                }.error)
            assertEquals(BtrfsFileError.NOT_DIRECTORY,
                assertFailsWith<BtrfsFileSystemException> { fs.remove("/directory/file/child") }.error)
            assertEquals(BtrfsFileError.INVALID_PATH,
                assertFailsWith<BtrfsFileSystemException> { fs.rename("/directory", "/directory/nested") }.error)
            assertEquals(BtrfsFileError.INVALID_PATH,
                assertFailsWith<BtrfsFileSystemException> { fs.write("../../escape", byteArrayOf(9)) }.error)
            assertEquals(BtrfsFileError.ALREADY_EXISTS,
                assertFailsWith<BtrfsFileSystemException> { fs.createEmpty("/directory/file") }.error)
            assertEquals(BtrfsFileError.TOO_LARGE,
                assertFailsWith<BtrfsFileSystemException> { fs.write("/directory/file", Long.MAX_VALUE, byteArrayOf(9)) }.error)
            assertEquals(BtrfsFileError.TOO_LARGE,
                assertFailsWith<BtrfsFileSystemException> {
                    fs.write("/directory/file", BtrfsFilesystemImage.MAX_SNAPSHOT_DATA_BYTES, byteArrayOf(9))
                }.error)
            assertEquals(BtrfsFileError.TOO_LARGE,
                assertFailsWith<BtrfsFileSystemException> {
                    fs.truncate("/directory/file", BtrfsFilesystemImage.MAX_SNAPSHOT_DATA_BYTES + 1)
                }.error)
            assertEquals(BtrfsFileError.TOO_LARGE,
                assertFailsWith<BtrfsFileSystemException> { fs.truncate("/directory/file", Int.MAX_VALUE.toLong()) }.error)
            assertFailsWith<IllegalArgumentException> { fs.migrate(images.config()) }
            fs.read("/directory/file")[0] = 99
            requireNotNull(fs.snapshot().entries.first { it.path == "directory/file" }.data)[0] = 88
            assertEquals(generation, fs.generation())
            assertContentEquals(byteArrayOf(1, 2, 3), fs.read("/directory/file"))
            assertContentEquals(byteArrayOf(2, 3), fs.read("/directory/file", 1, 10))
            assertContentEquals(ByteArray(0), fs.read("/directory/file", Long.MAX_VALUE, 10))
        } finally {
            fs.drain()
            images.delete()
        }
    }

    /** Hold an actual native-image write to exercise admission independently of scheduler speed. */
    private class HeldVolume(private val delegate: Volume) : Volume by delegate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun write(lba: Long, data: ByteBuffer) {
            entered.complete(Unit)
            release.await()
            delegate.write(lba, data)
        }
    }

    @Test
    fun drain_settles_cancelled_exports_and_queued_writes_but_rejects_unadmitted_work() = runTest {
        val images = Images()
        val fs = images.create(this, queueCapacity = 1)
        val channel = UringChannels.open(this, 8)
        val raw = BtrfsUringFileVolume.open(channel, images.directory.resolve("export.img").toString(),
            blockSize = 4096, capacity = 32768, backendReport = currentNioCapabilityReport())
        val held = HeldVolume(raw)
        try {
            val exporting = async { fs.export(held) }
            held.entered.await()
            val source = byteArrayOf(1, 2, 3)
            val queued = async { fs.write("/saved", source) }
            runCurrent()
            source[0] = 9
            val overflow = async { fs.write("/unadmitted", byteArrayOf(4)) }
            runCurrent()
            overflow.cancel()
            withTimeout(1000) { overflow.join() }
            exporting.cancel()
            val draining = async { fs.drain() }
            runCurrent()
            assertFalse(exporting.isCompleted)
            assertFalse(draining.isCompleted)
            held.release.complete(Unit)
            exporting.join()
            queued.await()
            draining.await()
            withTimeout(1000) {
                assertFailsWith<ClosedSendChannelException> { fs.stat("/") }
            }
            val reopened = images.reopen(this)
            try {
                assertContentEquals(byteArrayOf(1, 2, 3), reopened.read("/saved"))
                assertNull(reopened.stat("/unadmitted"))
                assertEquals(emptyList(), BtrfsFilesystemImage.read(raw).entries)
            } finally { reopened.drain() }
        } finally {
            held.release.complete(Unit)
            fs.drain()
            raw.drain()
            channel.drain()
            images.delete()
        }
    }

    @Test
    fun owner_cancellation_waits_for_admitted_native_image_io_and_closes_the_mount() = runTest {
        val images = Images()
        val owner = SupervisorJob(coroutineContext[Job])
        val ownedScope = CoroutineScope(coroutineContext + owner)
        val fs = images.create(ownedScope)
        val channel = UringChannels.open(this, 8)
        val raw = BtrfsUringFileVolume.open(channel, images.directory.resolve("export.img").toString(),
            blockSize = 4096, capacity = 32768, backendReport = currentNioCapabilityReport())
        val held = HeldVolume(raw)
        try {
            val exporting = async { fs.export(held) }
            held.entered.await()
            owner.cancel()
            runCurrent()
            assertFalse(owner.isCompleted)
            held.release.complete(Unit)
            exporting.await()
            withTimeout(1000) { owner.join(); fs.drain() }
            withTimeout(1000) {
                assertFailsWith<ClosedSendChannelException> { fs.list() }
            }
        } finally {
            held.release.complete(Unit)
            fs.drain()
            raw.drain()
            channel.drain()
            owner.cancel()
            images.delete()
        }
    }
}
