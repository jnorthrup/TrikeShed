package borg.trikeshed.btrfs

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandles
import java.nio.file.Files
import java.util.Optional
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BtrfsMacFuseMountTest {
    @Test
    fun native_session_bindings_use_the_declared_pointer_size_and_void_abis() {
        Arena.ofShared().use { arena ->
            val target = NativeFunctions()
            val linker = Linker.nativeLinker()
            val symbols = mutableMapOf<String, MemorySegment>()
            fun function(name: String, method: String, descriptor: FunctionDescriptor) {
                val handle = MethodHandles.lookup().findVirtual(NativeFunctions::class.java, method, descriptor.toMethodType()).bindTo(target)
                symbols[name] = linker.upcallStub(handle, descriptor, arena)
            }
            function("fuse_version", "version", FunctionDescriptor.of(JAVA_INT))
            function("fuse_mount", "mount", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
            function("fuse_new", "create", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS))
            function("fuse_loop", "loop", FunctionDescriptor.of(JAVA_INT, ADDRESS))
            function("fuse_exit", "unary", FunctionDescriptor.ofVoid(ADDRESS))
            function("fuse_unmount", "unmount", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
            function("fuse_destroy", "unary", FunctionDescriptor.ofVoid(ADDRESS))
            function("fuse_opt_free_args", "unary", FunctionDescriptor.ofVoid(ADDRESS))
            val native = BtrfsMacFuseNative(SymbolLookup { Optional.ofNullable(symbols[it]) })
            val arguments = native.arguments(arena)
            assertEquals(3, arguments.get(JAVA_INT, 0))
            assertEquals(0, arguments.get(JAVA_INT, 16))
            val point = arena.allocateFrom("/mount")
            val operations = arena.allocate(BtrfsMacFuseCallbacks.OPERATIONS_BYTES, 8)
            assertEquals(29, native.version())
            val channel = native.mount(point, arguments)
            val fuse = native.create(channel, arguments, operations)
            assertEquals(0, native.loop(fuse))
            native.exit(fuse)
            native.unmount(point, channel)
            native.destroy(fuse)
            native.freeArguments(arguments)
            assertEquals(BtrfsMacFuseCallbacks.OPERATIONS_BYTES, target.operationsBytes)
            assertEquals(4, target.voidCalls)
        }
    }

    @Test
    fun unsupported_hosts_and_nonempty_mountpoints_fail_before_native_loading() {
        assertFailsWith<IllegalArgumentException> { BtrfsMacFuseMount.requireHost("Linux", "amd64") }
        assertFailsWith<IllegalArgumentException> { BtrfsMacFuseMount.requireHost("Mac OS X", "x86_64") }
        val directory = Files.createTempDirectory("trikeshed-fuse-mountpoint-")
        try {
            val files = JvmFileOperations()
            assertEquals(directory.toString(), BtrfsMacFuseMount.validateMountPoint(files, directory.toString()))
            Files.writeString(directory.resolve("existing"), "retain")
            assertFailsWith<IllegalArgumentException> { BtrfsMacFuseMount.validateMountPoint(files, directory.toString()) }
            assertEquals("retain", Files.readString(directory.resolve("existing")))
            assertFailsWith<IllegalArgumentException> { BtrfsMacFuseMount.validateMountPoint(files, "/") }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** Exercise the actual native upcall/downcall ABI; this does not claim an OS mount on Linux. */
    @Test
    fun native_callbacks_modify_the_common_filesystem_and_return_darwin_errors() = runBlocking {
        val directory = Files.createTempDirectory("trikeshed-fuse-callbacks-")
        val scope = CoroutineScope(coroutineContext + Dispatchers.Default)
        val backing = BtrfsRaidRebalance.create(scope, JvmFileOperations(), directory.resolve("volume.manifest").toString(),
            BtrfsRaidImageConfig(BtrfsVolumeLayout.SINGLE, 4096, 1,
                s_[BtrfsRaidImageMember(1u, directory.resolve("initial.img").toString(), 32768)])) {
            BtrfsFilesystemImage.write(it, emptyList(), "fuse-test")
        }
        val filesystem = BtrfsFileSystemElement.mount(scope, backing)
        try {
            withContext(Dispatchers.IO) {
                Arena.ofShared().use { arena ->
                    val callbacks = BtrfsMacFuseCallbacks(filesystem, 501, 20)
                    val table = callbacks.operations(arena)
                    val linker = Linker.nativeLinker()
                    fun operation(index: Long, vararg arguments: MemoryLayout) = linker.downcallHandle(
                        table.getAtIndex(ADDRESS, index), FunctionDescriptor.of(JAVA_INT, *arguments))
                    val getattr = operation(0, ADDRESS, ADDRESS)
                    val mkdir = operation(4, ADDRESS, JAVA_SHORT)
                    val unlink = operation(5, ADDRESS)
                    val rmdir = operation(6, ADDRESS)
                    val chmod = operation(10, ADDRESS, JAVA_SHORT)
                    val truncate = operation(12, ADDRESS, JAVA_LONG)
                    val read = operation(15, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS)
                    val write = operation(16, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS)
                    val statfs = operation(17, ADDRESS, ADDRESS)
                    val create = operation(32, ADDRESS, JAVA_SHORT, ADDRESS)
                    val utimens = operation(36, ADDRESS, ADDRESS)
                    val root = arena.allocateFrom("/")
                    val folder = arena.allocateFrom("/directory")
                    val file = arena.allocateFrom("/directory/file")
                    val stat = arena.allocate(BtrfsMacFuseCallbacks.STAT_BYTES, 8)
                    val info = arena.allocate(BtrfsMacFuseCallbacks.FILE_INFO_BYTES, 8).fill(0)
                    val buffer = arena.allocate(32)
                    val space = arena.allocate(BtrfsMacFuseCallbacks.STATVFS_BYTES, 8)
                    val malformed = arena.allocateFrom(JAVA_BYTE, '/'.code.toByte(), 0xc0.toByte(), 0xaf.toByte(), 0)
                    val unterminated = arena.allocate(4096).fill('x'.code.toByte())
                    assertEquals(-22, mkdir.invokeExact(malformed, 0x1c0.toShort()) as Int)
                    assertEquals(-22, mkdir.invokeExact(unterminated, 0x1c0.toShort()) as Int)
                    assertEquals(emptyList(), filesystem.list("/"))
                    assertEquals(-2, getattr.invokeExact(file, stat) as Int)
                    assertEquals(0, mkdir.invokeExact(folder, 0x1c0.toShort()) as Int)
                    assertEquals(0, create.invokeExact(file, 0x180.toShort(), info) as Int)
                    assertEquals(-17, create.invokeExact(file, 0x180.toShort(), info) as Int)
                    assertEquals(1, info.get(JAVA_INT, 20) and 1)
                    val bytes = arena.allocateFrom(JAVA_BYTE, *"hello".encodeToByteArray())
                    assertEquals(5, write.invokeExact(file, bytes, 5L, 2L, info) as Int)
                    assertEquals(7, read.invokeExact(file, buffer, 32L, 0L, info) as Int)
                    assertContentEquals(byteArrayOf(0, 0, 104, 101, 108, 108, 111), buffer.asSlice(0, 7).toArray(JAVA_BYTE))
                    assertEquals(0, getattr.invokeExact(file, stat) as Int)
                    assertEquals(0x8180, stat.get(JAVA_SHORT, 4).toInt() and 0xffff)
                    assertEquals(501, stat.get(JAVA_INT, 16))
                    assertEquals(20, stat.get(JAVA_INT, 20))
                    assertEquals(7L, stat.get(JAVA_LONG, 96))
                    assertEquals(8L, stat.get(JAVA_LONG, 104))
                    assertTrue(stat.get(JAVA_LONG, 48) > 0)
                    assertEquals(0L, stat.get(JAVA_LONG, 32))
                    assertEquals(0, chmod.invokeExact(file, 0x1a4.toShort()) as Int)
                    assertEquals(0x81a4u, assertNotNull(filesystem.stat("/directory/file")).mode)
                    assertEquals(0x41c0u, assertNotNull(filesystem.stat("/directory")).mode)
                    val times = arena.allocate(32, 8)
                    times.set(JAVA_LONG, 0, 1_600_000_000)
                    times.set(JAVA_LONG, 8, 123)
                    times.set(JAVA_LONG, 16, 1_700_000_000)
                    times.set(JAVA_LONG, 24, 456)
                    assertEquals(0, utimens.invokeExact(file, times) as Int)
                    assertEquals(0, getattr.invokeExact(file, stat) as Int)
                    assertEquals(1_600_000_000L, stat.get(JAVA_LONG, 32))
                    assertEquals(123L, stat.get(JAVA_LONG, 40))
                    assertEquals(1_700_000_000L, stat.get(JAVA_LONG, 48))
                    assertEquals(456L, stat.get(JAVA_LONG, 56))
                    times.set(JAVA_LONG, 8, -2)
                    times.set(JAVA_LONG, 24, -1)
                    assertEquals(0, utimens.invokeExact(file, times) as Int)
                    assertEquals(0, getattr.invokeExact(file, stat) as Int)
                    assertEquals(1_600_000_000L, stat.get(JAVA_LONG, 32))
                    assertTrue(stat.get(JAVA_LONG, 48) > 1_700_000_000L)
                    times.set(JAVA_LONG, 24, 1_000_000_000)
                    assertEquals(-22, utimens.invokeExact(file, times) as Int)
                    assertEquals(0, truncate.invokeExact(file, 4L) as Int)
                    assertContentEquals(byteArrayOf(0, 0, 104, 101), filesystem.read("/directory/file"))
                    assertEquals(-21, unlink.invokeExact(folder) as Int)
                    assertEquals(-20, rmdir.invokeExact(file) as Int)
                    assertEquals(-66, rmdir.invokeExact(folder) as Int)
                    assertEquals(0, statfs.invokeExact(root, space) as Int)
                    assertEquals(4096L, space.get(JAVA_LONG, 0))
                    assertEquals(4096L, space.get(JAVA_LONG, 8))
                    assertEquals(32768L, space.get(JAVA_LONG, 16))
                    assertEquals(12287L, space.get(JAVA_LONG, 24))
                    assertEquals(12287L, space.get(JAVA_LONG, 32))
                    assertEquals(0L, space.get(JAVA_LONG, 40))
                    assertEquals(2L, space.get(JAVA_LONG, 72))
                    assertEquals(255L, space.get(JAVA_LONG, 80))

                    val names = DirectoryBuffer(1)
                    val fillerDescriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG)
                    val filler = linker.upcallStub(MethodHandles.lookup().findVirtual(DirectoryBuffer::class.java,
                        "fill", fillerDescriptor.toMethodType()).bindTo(names), fillerDescriptor, arena)
                    val readdir = operation(26, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)
                    assertEquals(0, readdir.invokeExact(folder, MemorySegment.NULL, filler, 0L, info) as Int)
                    assertEquals(listOf("."), names.names)
                    assertEquals(1L, names.nextOffset)
                    names.capacity = 3
                    assertEquals(0, readdir.invokeExact(folder, MemorySegment.NULL, filler, names.nextOffset, info) as Int)
                    assertEquals(listOf(".", "..", "file"), names.names)
                    assertEquals(3L, names.nextOffset)
                    assertEquals(0, unlink.invokeExact(file) as Int)
                    assertEquals(0, rmdir.invokeExact(folder) as Int)
                    assertEquals(-2, getattr.invokeExact(file, stat) as Int)
                }
            }
        } finally {
            filesystem.drain()
            directory.toFile().deleteRecursively()
        }
    }

    class DirectoryBuffer(var capacity: Int) {
        val names = mutableListOf<String>()
        var nextOffset = 0L
        fun fill(buffer: MemorySegment, name: MemorySegment, stat: MemorySegment, offset: Long): Int {
            if (names.size == capacity) return 1
            names += name.reinterpret(1024).getString(0)
            nextOffset = offset
            return 0
        }
    }

    class NativeFunctions {
        var operationsBytes = 0L
        var voidCalls = 0
        fun version(): Int = 29
        fun mount(point: MemorySegment, arguments: MemorySegment): MemorySegment = point
        fun create(channel: MemorySegment, arguments: MemorySegment, operations: MemorySegment, size: Long, user: MemorySegment): MemorySegment {
            operationsBytes = size
            return channel
        }
        fun loop(fuse: MemorySegment): Int = 0
        fun unary(value: MemorySegment) { voidCalls++ }
        fun unmount(point: MemorySegment, channel: MemorySegment) { voidCalls++ }
    }
}
