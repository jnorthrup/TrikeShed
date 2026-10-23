package borg.trikeshed.btrfs

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Commands reopen the same real volume each time, exercising ownership and publication. */
class BtrfsToolTest {
    @Test
    fun operator_commands_preserve_native_files_across_compression_add_balance_and_export() = runTest {
        val directory = Files.createTempDirectory("btrfs-operator-")
        val state = directory.resolve("state").toString()
        val files = JvmFileOperations()
        val source = directory.resolve("source.bin")
        val payload = ByteArray(20_017) { ((it * 19 + it / 503) and 255).toByte() }
        Files.write(source, payload)
        suspend fun run(command: String, vararg operands: String): String =
            BtrfsTool.run(this, files, listOf(command, state) + operands)
        try {
            val created = run("create", "128", "SPAN", "1", "LZ4")
            assertTrue(created.contains("generation=1 profile=SPAN members=1"), created)
            assertTrue(created.contains("metadata=LZ4"), created)
            run("mkdir", "/documents")
            run("put", source.toString(), "/documents/payload.bin")
            assertEquals("payload.bin", run("ls", "/documents"))

            val initialGet = directory.resolve("initial.bin")
            run("get", "/documents/payload.bin", initialGet.toString())
            assertContentEquals(payload, Files.readAllBytes(initialGet))
            val compressed = run("compression", "ZSTD")
            assertTrue(compressed.contains("metadata=ZSTD"), compressed)
            val added = run("add", "128")
            assertTrue(added.contains("profile=SPAN members=2 bytes=268435456"), added)
            val balanced = run("balance", "RAID1", "2", "256")
            assertTrue(balanced.contains("profile=RAID1 members=2 bytes=268435456"), balanced)
            assertTrue(balanced.contains("metadata=ZSTD"), balanced)

            val finalGet = directory.resolve("balanced.bin")
            run("get", "/documents/payload.bin", finalGet.toString())
            assertContentEquals(payload, Files.readAllBytes(finalGet))
            val nativePath = directory.resolve("native.btrfs")
            run("export", nativePath.toString())
            assertEquals(256L * 1024 * 1024, Files.size(nativePath))
            val channel = UringChannels.open(this, entries = 8)
            try {
                val native = BtrfsUringFileVolume.open(channel, nativePath.toString(), 4096, 65536,
                    create = false, resize = false, backendReport = currentNioCapabilityReport(), readOnly = true)
                try {
                    val snapshot = BtrfsFilesystemImage.read(native)
                    assertContentEquals(payload, snapshot.entries.single { it.path == "documents/payload.bin" }.data)
                    assertEquals(listOf("documents", "documents/payload.bin"), snapshot.entries.map { it.path }.sorted())
                } finally { native.drain() }
            } finally { channel.drain() }
            assertEquals(balanced, run("info"))

            // All refusals leave existing host files and the committed generation intact.
            assertFailsWith<IllegalArgumentException> { run("create", "128") }
            assertFailsWith<IllegalArgumentException> { run("get", "/documents/payload.bin", source.toString()) }
            assertFails { run("export", source.toString()) }
            assertContentEquals(payload, Files.readAllBytes(source))
            assertEquals(balanced, run("info"))

            // A failed host mount relinquishes both element ownership and its manifest lease.
            assertFailsWith<IllegalStateException> {
                BtrfsTool.run(this, files, listOf("mount", state, directory.resolve("mount").toString())) { fs, _, _ ->
                    assertContentEquals(payload, fs.read("/documents/payload.bin"))
                    error("Injected host transport failure")
                }
            }
            assertEquals(balanced, run("info"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun authenticated_live_control_adds_storage_using_the_existing_filesystem_owner() = runBlocking {
        withTimeout(120000) {
            val directory = Files.createTempDirectory("btrfs-live-operator-")
            val state = directory.resolve("state").toString()
            val manifest = directory.resolve("state/volume.manifest").toString()
            val files = JvmFileOperations()
            val original = ByteArray(12_301) { ((it * 29 + it / 137) and 255).toByte() }
            val after = "written through the retained mount after live addition".encodeToByteArray()
            val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            try {
                BtrfsTool.run(this, files, listOf("create", state, "128", "SPAN", "1", "RAW"))
                val backing = BtrfsRaidRebalance.open(this, files, manifest)
                val fs = BtrfsFileSystemElement.mount(this, backing)
                try {
                    fs.write("/original.bin", original)
                    val before = fs.generation()
                    // A separate process-style owner cannot take this lease; live
                    // control must dispatch through the already mounted element.
                    assertFails { BtrfsRaidRebalance.open(this, JvmFileOperations(), manifest) }
                    val control = BtrfsMountControl.open(this, token) { command ->
                        BtrfsTool.runMounted(this, files, state, fs, command)
                    }
                    try {
                        val compressed = BtrfsMountControl.request(control.address, token, listOf("compression", "LZ4"))
                        assertTrue(compressed.contains("metadata=LZ4"), compressed)
                        val added = BtrfsMountControl.request(control.address, token, listOf("add", "128"))
                        assertTrue(added.contains("profile=SPAN members=2 bytes=268435456"), added)
                        assertEquals(before + 2, fs.generation())
                        assertEquals(2, fs.config().members.size)
                        assertContentEquals(original, fs.read("/original.bin"))
                        fs.write("/after.bin", after)
                        val info = BtrfsMountControl.request(control.address, token, listOf("info"))
                        assertTrue(info.contains("generation=${fs.generation()}"), info)
                        assertContentEquals(after, fs.read("/after.bin"))
                    } finally { control.drain() }
                    // Draining the control listener does not own or close the mount.
                    assertContentEquals(original, fs.read("/original.bin"))
                    fs.sync()
                } finally { fs.drain() }

                val reopened = BtrfsFileSystemElement.mount(this, BtrfsRaidRebalance.open(this, files, manifest))
                try {
                    val config = reopened.config()
                    assertEquals(BtrfsVolumeLayout.SPAN, config.layout)
                    assertEquals(2, config.members.size)
                    assertEquals(65536L, config.validate().capacity)
                    assertEquals(BtrfsMetadataCompression.LZ4, reopened.metadataCompression())
                    assertContentEquals(original, reopened.read("/original.bin"))
                    assertContentEquals(after, reopened.read("/after.bin"))
                } finally { reopened.drain() }
            } finally { directory.toFile().deleteRecursively() }
        }
    }
}
