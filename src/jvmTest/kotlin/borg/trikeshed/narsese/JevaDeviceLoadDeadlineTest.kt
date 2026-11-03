package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.platform.spi.MatrixSession
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class JevaDeviceLoadDeadlineTest {
    @Test fun deadlineReachesEncoderAndClosesLateSession() {
        val root = Files.createTempDirectory("jeva-device-deadline-")
        val clock = TestTimeSource()
        var closed = false
        var entered = false
        val matrixSession = object : MatrixSession {
            override val identity = "test"
            override fun gemm(m: Int, n: Int, k: Int, a: FloatArray, aOffset: Int, lda: Int,
                              b: FloatArray, bOffset: Int, ldb: Int, transposeB: Boolean,
                              c: FloatArray, cOffset: Int, ldc: Int) = error("not used")
            override fun close() = Unit
        }
        val session = object : EncoderSession {
            override val hiddenSize = 1
            override val matrix = matrixSession
            override val identity = "test"
            override fun encode(tokens: Series<Int>, check: () -> Unit, progress: (Int, Int) -> Unit): MetaSeries<Tensor, Float> = error("not used")
            override fun close() { closed = true }
        }
        val encoder = object : EncoderOperations {
            override fun open(directory: String, check: () -> Unit): EncoderSession {
                entered = true
                check()
                clock += 101.milliseconds
                // A provider may return after its final check; the caller still owns cleanup.
                return session
            }
        }
        try {
            Files.createDirectories(root.resolve("backbone_mlx"))
            Files.writeString(root.resolve("config.yaml"), "model:\n  pooling: last\n")
            Files.writeString(root.resolve("backbone_mlx/tokenizer.json"), "{}")
            Files.write(root.resolve("head.pt"), _a[1.toByte()])
            val config = JevaConfig(model = root.toString(), device = "mlx", timeoutMs = 100)
            val failure = assertFailsWith<JevaDeadline.Expired> {
                JevaInference.loadDevice(config, encoder, JevaDeadline(100, "load", clock))
            }
            assertTrue(entered)
            assertTrue(closed)
            assertEquals("load device backbone", failure.location)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun checkpointReadIsBoundedAndChecksBetweenChunks() {
        val path = Files.createTempFile("jeva-bounded-", ".bin")
        try {
            val bytes = ByteArray(JevaWeights.BUFFER_BYTES + 1) { it.toByte() }
            Files.write(path, bytes)
            assertFailsWith<IllegalArgumentException> { jevaFile(path.toString(), bytes.size - 1) }
            var checks = 0
            class Expired : RuntimeException()
            assertFailsWith<Expired> { jevaFile(path.toString(), bytes.size) { if (++checks == 3) throw Expired() } }
            assertEquals(3, checks)
            assertContentEquals(bytes, jevaFile(path.toString(), bytes.size))
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun checkpointHashMatchesContentIdAndChecksDuringInput() {
        val bytes = ByteArray(JevaWeights.BLOCK_BYTES * 2 + 1) { (it * 17).toByte() }
        assertEquals(ContentId.of(bytes).hex, jevaDigest(bytes) {})
        var checks = 0
        class Expired : RuntimeException()
        assertFailsWith<Expired> { jevaDigest(bytes) { if (++checks == 2) throw Expired() } }
        assertEquals(2, checks)
    }

    @Test fun malformedHeadScanHonorsDeadline() {
        var checks = 0
        class Expired : RuntimeException()
        assertFailsWith<Expired> { JevaHead.parse(ByteArray(8192)) { if (++checks == 2) throw Expired() } }
        assertEquals(2, checks)
    }
}
