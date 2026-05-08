package borg.trikeshed.brc

import borg.trikeshed.*
import borg.trikeshed.collections._a
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrcSeekFileBufferContractTest {

    /**
     * Creates a temp file with known content for read-back tests.
     * Uses the cross-platform [Files.write] / [mktemp] / [rm] surface so
     * JVM, posix-native, JS, and Wasm/JS targets all behave consistently.
     *
     * On WASM/browser the backing store is localStorage-based (see
     * WasmBrowserSeekHandle / writeBlob); the test data is written
     * before opening a [SeekFileBuffer] for read.
     *
     * Returns the temp file path.  Callers should [rm] it in a finally-block.
     */
    private fun createTestFixture(content: ByteArray = TEST_BYTES): String {
        val path = Files.createTempDir("trikeshed")
        // mktemp() creates an empty file — overwrite with real content.
        Files.write(path, content)
        return path
    }

    companion object {
        /** Deterministic 16-byte fixture used across the contract tests. */
        val TEST_BYTES = _a [
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
            0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        ]
    }

    @Test
    fun seekFileBufferUseExtensionCompiles() {
        val path = createTestFixture()
        try {
            val result = SeekFileBuffer(path).use { buf ->
                buf.isOpen()
            }
            assertTrue(result, "SeekFileBuffer must report isOpen() = true inside use{}")
        } finally {
            Files.deleteRecursively(path)
        }
    }

    @Test
    fun seekFileBufferSizePositiveWhenFileExists() {
        val path = createTestFixture()
        try {
            SeekFileBuffer(path).use { buf ->
                assertTrue(buf.size() > 0, "SeekFileBuffer.size() must be positive for non-empty file")
            }
        } finally {
            Files.deleteRecursively(path)
        }
    }

    @Test
    fun seekFileBufferGetFirstByteMatchesKnownContent() {
        val path = createTestFixture()
        try {
            SeekFileBuffer(path).use { buf ->
                val first = buf.get(0L)
                assertEquals(
                    TEST_BYTES[0], first,
                    "First byte must match the known fixture content"
                )
            }
        } finally {
            Files.deleteRecursively(path)
        }
    }

    @Test
    fun seekFileBufferSeekAndFetchIsIdempotent() {
        val path = createTestFixture()
        try {
            SeekFileBuffer(path).use { buf ->
                try {
                    buf.seek(0L)
                    val a = buf.get(0L)
                    buf.seek(0L)
                    val b = buf.get(0L)
                    assertTrue(a == b, "Repeated seek(0) + get(0) must yield same byte")
                } catch (e: UnsupportedOperationException) {
                    val a = buf.get(0L)
                    val b = buf.get(0L)
                    assertTrue(a == b, "Repeated get(0) must yield same byte even without seek()")
                    println("SKIP seek: ${e.message}")
                }
            }
        } finally {
            Files.deleteRecursively(path)
        }
    }

    @Test
    fun seekFileBufferReadvWritesIntoCallerOwnedRegionWindow() {
        val path = createTestFixture("0123456789".encodeToByteArray())
        try {
            SeekFileBuffer(path).use { buf ->
                val firstBacking = ByteBuffer.wrap("_____".encodeToByteArray())
                val secondBacking = ByteBuffer.wrap("____".encodeToByteArray())
                val first = ByteRegion(firstBacking, 1, 4)
                val second = ByteRegion(secondBacking)

                val counts = buf.readv(
                    2 j { i: Int ->
                        if (i == 0) 2L j first else 6L j second
                    }
                )

                assertEquals(3, counts[0])
                assertEquals(4, counts[1])
                assertEquals('_'.code.toByte(), firstBacking.get(0))
                assertEquals('_'.code.toByte(), firstBacking.get(4))
                assertEquals("234", first.asByteSeries().asString())
                assertEquals("6789", second.asByteSeries().asString())
            }
        } finally {
            Files.deleteRecursively(path)
        }
    }

    @Test
    fun seekHandlePreadWritesIntoCallerOwnedRegionWindow() {
        val path = createTestFixture("abcdefghij".encodeToByteArray())
        val handle = platformSeekHandle()
        val fd = handle.open(path, readOnly = true)
        try {
            val backing = ByteBuffer.wrap("______".encodeToByteArray())
            val region = ByteRegion(backing, 1, 5)

            val count = handle.pread(fd, region, 3L)

            assertEquals(4, count)
            assertEquals('_'.code.toByte(), backing.get(0))
            assertEquals('_'.code.toByte(), backing.get(5))
            assertEquals("defg", region.asByteSeries().asString())
        } finally {
            handle.close(fd)
            Files.deleteRecursively(path)
        }
    }

    @Test
    fun seekHandlePwriteAndReadUseByteSeriesAndByteRegionWindows() {
        val path = createTestFixture("0123456789".encodeToByteArray())
        val handle = platformSeekHandle()
        val fd = handle.open(path, readOnly = false)
        try {
            val writeBacking = ByteBuffer.wrap("_WXYZ_".encodeToByteArray())
            val writeSeries = ByteRegion(writeBacking, 1, 5).asByteSeries()

            val written = handle.pwrite(fd, writeSeries, 2L)
            assertEquals(4, written)

            val readBacking = ByteBuffer.wrap("______".encodeToByteArray())
            val readRegion = ByteRegion(readBacking, 1, 5)
            handle.seek(fd, 1L)
            val read = handle.read(fd, readRegion)

            assertEquals(4, read)
            assertEquals('_'.code.toByte(), readBacking.get(0))
            assertEquals('_'.code.toByte(), readBacking.get(5))
            assertEquals("1WXY", readRegion.asByteSeries().asString())
            assertEquals("01WXYZ6789", Files.readAllBytes(path).decodeToString())
        } finally {
            handle.close(fd)
            Files.deleteRecursively(path)
        }
    }
}
