package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import kotlin.test.*
import kotlinx.coroutines.*
import borg.trikeshed.kif.SumoNixBridge
import borg.trikeshed.userspace.nio.file.spi.*

class NativeHornProfileTest {
    private fun fields(): Map<String, Any?> = _m["binary" j "/operator/mlnix", "binarySha256" j "a".repeat(64),
        "adapter" j "/operator/run.nix", "datalogDirectory" j "/operator/source"]
    @Test fun missingFileCapabilityRefuses(): Unit = runBlocking {
        val export = SumoNixBridge.compile(0, emptyList(), "(p A)", "review-record-observations")
        val query = NativeHornQuery(NativeHornProfile.parse(jsonOf(fields())))
        val failure = assertFailsWith<IllegalStateException> {
            query.execute(_m["inputJson" j export.inputJson, "inputCid" j export.inputCid.value, "frameId" j export.frameId.value])
        }
        assertTrue(failure.message.orEmpty().contains("FileOperations capability missing"))
    }
    @Test fun trustedReadChecksSizeBeforeReading() {
        val memory = InMemoryFileOperations()
        memory.write("/large", ByteArray(8))
        var read = false
        val files = object : FileOperations by memory, BoundedFileReader {
            override fun readBounded(path: String, maxBytes: Int): ByteArray { read = true; return memory.readAllBytes(path) }
        }
        assertFailsWith<IllegalArgumentException> { NativeHornProfile.boundedRead(files, "/large", 4) }
        assertFalse(read)
    }
    @Test fun growthBetweenSizeAndReadRemainsBounded() {
        val memory = InMemoryFileOperations()
        memory.write("/profile", _a[1.toByte(), 2.toByte()])
        val files = object : FileOperations by memory, BoundedFileReader {
            override fun readBounded(path: String, maxBytes: Int): ByteArray {
                memory.write(path, ByteArray(maxBytes + 1))
                throw BoundedReadLimitException(maxBytes)
            }
            override fun readAllBytes(filename: String): ByteArray = error("unbounded read must not be used")
        }
        assertFailsWith<BoundedReadLimitException> { NativeHornProfile.boundedRead(files, "/profile", 4) }
        assertFailsWith<UnsupportedOperationException> { NativeHornProfile.boundedRead(memory, "/profile", 4) }
    }
    @Test fun strictPureOperatorProfile() {
        val profile = NativeHornProfile.parse(jsonOf(fields()))
        assertEquals(45_000L, profile.timeoutMillis)
        assertTrue(profile.environment.isEmpty())
        assertEquals(1000L, NativeHornProfile.parse(jsonOf(fields() + _m["timeoutMillis" j 1000])).timeoutMillis)
        for (extra in s_["environment", "argv", "code", "input"].view)
            assertFailsWith<IllegalArgumentException> { NativeHornProfile.parse(jsonOf(fields() + _m[extra j "forged"])) }
        for (timeout in s_.get<Any?>(null, "1000", 0, 45001, 1.5).view)
            assertFails { NativeHornProfile.parse(jsonOf(fields() + _m["timeoutMillis" j timeout])) }
        assertFailsWith<IllegalArgumentException> { NativeHornProfile.parse(jsonOf(fields() + _m["binary" j "relative/mlnix"])) }
        assertFailsWith<IllegalArgumentException> { NativeHornProfile.parse(jsonOf(fields()).dropLast(1) + ",\"binary\":\"/forged\"}") }
        assertFailsWith<IllegalArgumentException> { NativeHornProfile.parse(" ".repeat(16385)) }
    }
}
