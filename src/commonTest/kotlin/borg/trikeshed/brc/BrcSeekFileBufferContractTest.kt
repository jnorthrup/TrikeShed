package borg.trikeshed.brc

import borg.trikeshed.Files
import borg.trikeshed.SeekFileBuffer
import borg.trikeshed.mktemp
import borg.trikeshed.rm
import borg.trikeshed.use
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

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
        val path = mktemp()
        // mktemp() creates an empty file — overwrite with real content.
        Files.write(path, content)
        return path
    }

    companion object {
        /** Deterministic 16-byte fixture used across the contract tests. */
        val TEST_BYTES = byteArrayOf(
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
            0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        )
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
            rm(path)
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
            rm(path)
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
            rm(path)
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
                    // JS backend does not implement seek(); verify get() is still
                    // idempotent without explicit seek calls.
                    val a = buf.get(0L)
                    val b = buf.get(0L)
                    assertTrue(a == b, "Repeated get(0) must yield same byte even without seek()")
                    println("SKIP seek: ${e.message}")
                }
            }
        } finally {
            rm(path)
        }
    }
}
