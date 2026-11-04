package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.common.Files
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlin.test.*

class MlxVerifiedReaderTest {
    private fun encoded(header: String, dataBytes: Int): ByteArray {
        val text = header.encodeToByteArray()
        return ByteArray(8 + text.size + dataBytes).also { bytes ->
            for (i in 0 until 8) bytes[i] = (text.size.toLong() ushr (i * 8)).toByte()
            text.copyInto(bytes, 8)
        }
    }

    @Test fun headerRejectsRoundedOrNonIntegerTensorNumbersBeforeNativeLoad() {
        val directory = Files.createTempDir("mlx-header-number-")
        val path = "$directory/weights.safetensors"
        try {
            for (header in _l[
                """{"data":{"dtype":"F32","shape":[2.0],"data_offsets":[0,8]}}""",
                """{"data":{"dtype":"F32","shape":[2.0000000000000001],"data_offsets":[0,8]}}""",
                """{"data":{"dtype":"F32","shape":[2],"data_offsets":[0,8e0]}}""",
                """{"data":{"dtype":"F32","shape":[2],"data_offsets":[-0,8]}}""",
                """{"data":{"dtype":"F32","shape":[18446744073709551616],"data_offsets":[0,8]}}""",
            ]) {
                Files.write(path, encoded(header, 8))
                val reader = MlxVerifiedReader(path)
                try { assertFailsWith<IllegalArgumentException>(header) { reader.header(_a["data"]) } }
                finally { reader.close() }
            }
            Files.write(path, encoded("""{"data":{"dtype":"F32","shape":[2],"data_offsets":[0,8]}}""", 8))
            val reader = MlxVerifiedReader(path)
            try { reader.header(_a["data"]) } finally { reader.close() }
        } finally { Files.deleteRecursively(directory) }
    }

    @Test fun invalidTensorHeadersAndMissingNamesAreRejectedBeforeNativeLoad() {
        val directory = Files.createTempDir("mlx-invalid-header-")
        val path = "$directory/weights.safetensors"
        val valid = "\"data\":{\"dtype\":\"F32\",\"shape\":[2],\"data_offsets\":[0,8]}"
        try {
            for (header in _l[
                "{",
                "{$valid,$valid}",
                "{\"data\":{\"dtype\":\"F32\",\"dtype\":\"U32\",\"shape\":[2],\"data_offsets\":[0,8]}}",
                "{\"data\":{\"dtype\":\"bogus\",\"shape\":[2],\"data_offsets\":[0,8]}}",
                "{\"data\":{\"dtype\":\"F32\",\"shape\":[999999999999],\"data_offsets\":[0,8]}}",
                "{\"data\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,8]}}",
                "{\"data\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[4,8]}}",
                "{$valid,\"other\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}",
            ]) {
                Files.write(path, encoded(header, 8))
                val session = MlxMatrixOperations().open()
                try { assertFails { session.load(path, "data") } } finally { session.close() }
            }
            Files.write(path, encoded("{$valid}", 8))
            val session = MlxMatrixOperations().open()
            try {
                assertFails { session.load(path, "missing") }
                val tensor = session.load(path, "data").single()
                assertContentEquals(_a[0f, 0f], FloatArray(2).also { tensor.read(0, it) })
            } finally { session.close() }
        } finally { Files.deleteRecursively(directory) }
    }

    @Test fun admittedHeaderRemainsStableAfterInPlaceMutation() {
        val directory = Files.createTempDir("mlx-header-snapshot-")
        val path = "$directory/weights.safetensors"
        val original = safetensors()
        Files.write(path, original)
        val reader = MlxVerifiedReader(path)
        try {
            reader.header(_a["data"])
            val modified = original.copyOf()
            modified[8] = '!'.code.toByte()
            Files.write(path, modified)
            assertContentEquals(original.copyOfRange(0, original.size - 8), read(reader, 0, original.size - 8))
            assertContentEquals(ByteArray(8), read(reader, original.size - 8, 8))
            assertFailsWith<IllegalStateException> { reader.check() }
        } finally { reader.close(); Files.deleteRecursively(directory) }
    }

    fun safetensors(): ByteArray {
        val header = """{"data":{"dtype":"F32","shape":[2],"data_offsets":[0,8]}}""".encodeToByteArray()
        val bytes = ByteArray(8 + header.size + 8)
        for (i in 0 until 8) bytes[i] = (header.size.toLong() ushr (i * 8)).toByte()
        header.copyInto(bytes, 8)
        bytes[8 + header.size + 2] = 0x80.toByte(); bytes[8 + header.size + 3] = 0x3f
        bytes[8 + header.size + 7] = 0x40
        return bytes
    }

    @Test fun blockDigestMatchesContentId() {
        for (size in _a[1, 55, 56, 64, MlxVerifiedReader.BLOCK_BYTES]) {
            val bytes = ByteArray(size) { (it * 31).toByte() }
            assertEquals(ContentId.of(bytes).hex, mlxBlockDigest(bytes))
        }
    }

    inline fun fixture(block: (String, ByteArray) -> Unit) {
        val directory = Files.createTempDir("mlx-reader-")
        val path = "$directory/weights"
        val bytes = ByteArray(MlxVerifiedReader.BLOCK_BYTES + 29) { (it * 17).toByte() }
        try { Files.write(path, bytes); block(path, bytes) } finally { Files.deleteRecursively(directory) }
    }

    fun read(reader: MlxVerifiedReader, offset: Int, length: Int): ByteArray {
        val bytes = ByteArray(length) { 0x55 }
        bytes.usePinned { reader.read(it.addressOf(0), bytes.size.toULong(), offset.toULong()) }
        return bytes
    }

    @Test fun concurrentReadsAndPathReplacementKeepAdmittedBytes() = runBlocking {
        fixture { path, original ->
            val reader = MlxVerifiedReader(path)
            try {
                val identity = reader.identity
                assertTrue(Files.rename(path, "$path.old"))
                Files.write(path, ByteArray(original.size) { 42 })
                coroutineScope {
                    val calls = (0 until 16).map { index -> async(Dispatchers.Default) {
                        val offset = MlxVerifiedReader.BLOCK_BYTES - 20 + index
                        assertContentEquals(original.copyOfRange(offset, offset + 30), read(reader, offset, 30))
                    } }
                    calls.awaitAll()
                }
                reader.check()
                assertEquals(identity, reader.identity)
                val replacement = MlxVerifiedReader(path)
                try { assertNotEquals(identity, replacement.identity) } finally { replacement.close() }
            } finally { reader.close() }
        }
    }

    @Test fun mutationAndTruncationLatchAndZeroTheWholeRead() {
        fixture { path, original ->
            val reader = MlxVerifiedReader(path)
            try {
                val changed = original.copyOf()
                changed[MlxVerifiedReader.BLOCK_BYTES + 4]++
                Files.write(path, changed)
                assertContentEquals(ByteArray(30), read(reader, MlxVerifiedReader.BLOCK_BYTES - 10, 30))
                assertFailsWith<IllegalStateException> { reader.check() }
                assertContentEquals(ByteArray(8), read(reader, 0, 8))
            } finally { reader.close() }
        }
        fixture { path, _ ->
            val reader = MlxVerifiedReader(path)
            try {
                Files.write(path, ByteArray(8))
                assertContentEquals(ByteArray(16), read(reader, 0, 16))
                assertFailsWith<IllegalStateException> { reader.check() }
            } finally { reader.close() }
        }
    }

    @Test fun admissionCheckInterruptsManifestWithoutRetainingTheFile() {
        fixture { path, _ ->
            var checks = 0
            class Expired : RuntimeException()
            assertFailsWith<Expired> { MlxVerifiedReader(path) { if (++checks == 3) throw Expired() } }
            assertEquals(3, checks)
            // A subsequent reader can acquire and consume the same file after failed admission.
            val reader = MlxVerifiedReader(path)
            try { reader.check(); assertTrue(reader.good()) } finally { reader.close() }
        }
    }

    @Test fun lazyMlxReadCannotExposeChangedBytes() {
        val directory = Files.createTempDir("mlx-safetensors-")
        val path = "$directory/weights.safetensors"
        val bytes = safetensors()
        val session = MlxMatrixOperations().open()
        try {
            Files.write(path, bytes)
            val tensor = session.load(path, "data").single()
            val changed = bytes.copyOf(); changed[changed.lastIndex] = 0x41
            Files.write(path, changed)
            assertFailsWith<IllegalStateException> { tensor.read(0, FloatArray(2), 0, 2) {} }
        } finally { session.close(); Files.deleteRecursively(directory) }
    }

    @Test fun materializedWeightRetainsAdmittedBytesAfterMutation() {
        val directory = Files.createTempDir("mlx-safetensors-")
        val path = "$directory/weights.safetensors"
        val bytes = safetensors()
        val session = MlxMatrixOperations().open()
        try {
            Files.write(path, bytes)
            val tensors = session.load(path, "data")
            session.materialize(tensors)
            val identity = session.fileIdentity
            val changed = bytes.copyOf(); changed[changed.lastIndex] = 0x41
            Files.write(path, changed)
            val values = FloatArray(2)
            tensors.single().read(0, values, 0, 2) {}
            assertContentEquals(_a[1f, 2f], values)
            assertEquals(identity, session.fileIdentity)
        } finally { session.close(); Files.deleteRecursively(directory) }
    }

    @Test fun materializationChecksAfterEachWeight() {
        val directory = Files.createTempDir("mlx-materialize-")
        val path = "$directory/weights.safetensors"
        val session = MlxMatrixOperations().open()
        try {
            Files.write(path, safetensors())
            val tensors = session.load(path, "data")
            var checks = 0
            class Expired : RuntimeException()
            // One preflight poll, one before evaluation, and one after the evaluated weight.
            assertFailsWith<Expired> { session.materialize(tensors) { if (++checks == 3) throw Expired() } }
            assertEquals(3, checks)
        } finally { session.close(); Files.deleteRecursively(directory) }
    }
}
