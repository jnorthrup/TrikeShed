package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.Syntax
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class LlvmConfixTest {
    companion object {
        fun bytes() = checkNotNull(LlvmConfixTest::class.java.getResourceAsStream("/sulong/confix.bc")).use { it.readAllBytes() }
        fun profile(limit: Int = 65536) = LlvmConfixProfile(Path.of("/operator/add.bc"), ContentId.of(bytes()).hex, limit, 16384)
        fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun oracle(format: Syntax, source: ByteArray): List<Map<String, Any?>> {
            val series = source.size j { source[it] }
            val flat = when (format) {
                Syntax.JSON -> format.scan0(series).b
                Syntax.CBOR -> format.scanCbor0(series).b
                Syntax.YAML -> format.scanYaml0(series).b
            }
            return (flat.spans.size j { row: Int -> _m.get<String, Any?>(
                "open" j flat.spans[row].a, "close" j flat.spans[row].b,
                "tag" j flat.tags[row].name, "depth" j flat.depths[row],
                "children" j flat.childOf(row).view.toList()) }).view.toList()
        }
    }

    @Test fun upstreamScannersMatchRetainedKotlinGeometry(): Unit = runBlocking {
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("confix-test"))
        try {
            val scanner = LlvmConfix.open(kernel, KernelId("scan"), profile(), LlvmScalarTest.files(bytes()))
            val fixtures = s_[
                Syntax.JSON j "{\"agreement\":\"Coinbase\",\"terms\":[true,null,42]}".encodeToByteArray(),
                Syntax.JSON j "[\"é水🙂\",\"escaped \\\"quote\\\"\",-+-]".encodeToByteArray(),
                Syntax.JSON j "{\"k\":1,\"k\":2}".encodeToByteArray(),
                Syntax.CBOR j hex("a261618301f5f6616263636174"),
                Syntax.CBOR j hex("9f010218ff20ff"),
                Syntax.CBOR j hex("430001ff"),
                Syntax.YAML j "name: Coinbase\nactive: true\ncount: 42\n".encodeToByteArray(),
                Syntax.YAML j "- alpha\n- beta\n".encodeToByteArray(),
                Syntax.YAML j "name: 水\r\ncount: 4\r\n".encodeToByteArray(),
            ]
            for ((format, source) in fixtures.view) {
                val result = scanner.scan(format, source)
                assertEquals(true, result["ok"], "$format ${source.decodeToString()}: $result")
                assertEquals(oracle(format, source), result["rows"], "$format ${source.decodeToString()}")
                assertEquals(ContentId.of(source).value, result["sourceCid"])
                val runtime = result["runtime"] as Map<*, *>
                assertEquals(0L, runtime["liveBytesAfter"])
                assertEquals(true, runtime["guardsVerified"])
            }
            for (format in Syntax.entries) assertEquals(oracle(format, ByteArray(0)), scanner.scan(format, ByteArray(0))["rows"])
        } finally { kernel.drain() }
    }

    @Test fun faultsNeverPublishPartialGeometryAndReleaseTheirBuffers(): Unit = runBlocking {
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("confix-test"))
        try {
            val scanner = LlvmConfix.open(kernel, KernelId("scan"), profile(), LlvmScalarTest.files(bytes()))
            for ((source, code) in s_[hex("1a00") j 1, hex("1c") j 2, hex("fa00") j 3, hex("5b00000000fffffff7") j 4].view) {
                val result = scanner.scan(Syntax.CBOR, source)
                assertEquals(false, result["ok"])
                assertEquals(code, (result["fault"] as Map<*, *>)["code"])
                assertEquals(emptyList(), result["rows"])
                assertEquals(0L, (result["runtime"] as Map<*, *>)["liveBytesAfter"])
            }
            val room = scanner.scan(Syntax.JSON, "[1,2]".encodeToByteArray(), rowCapacity = 1)
            assertEquals(5, (room["fault"] as Map<*, *>)["code"])
            val backwards = scanner.scan(Syntax.YAML, "- # comment: value".encodeToByteArray())
            assertEquals(4, (backwards["fault"] as Map<*, *>)["code"])
            repeat(32) { assertEquals(true, scanner.scan(Syntax.JSON, "{}".encodeToByteArray())["ok"]) }
        } finally { kernel.drain() }
    }

    @Test fun admissionBoundsAndOperatorAuthorityAreEnforced(): Unit = runBlocking {
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("confix-test"))
        try {
            val scanner = LlvmConfix.open(kernel, KernelId("scan"), profile(8), LlvmScalarTest.files(bytes()))
            assertFails { scanner.scan(Syntax.JSON, ByteArray(9)) }
            assertFails { scanner.scan(Syntax.JSON, ByteArray(0), rowCapacity = 0) }
            val runner = scanner.runner()
            val node = LcncNode("scan", LlvmConfixContract.TYPE, _m["format" j "JSON", "data" j "{}"])
            assertFails { runner.execute(node.copy(params = node.params + _m["module" j "/untrusted.bc"]), emptyMap()) }
            assertFails { runner.execute(node, _m["data" j "{}", "data?" j "[]"]) }
            assertFails { runner.execute(node, _m["data" j null]) }
            assertEquals(true, runner.execute(node, emptyMap())["ok"])
            scanner.drain()
            assertFails { scanner.scan(Syntax.JSON, "{}".encodeToByteArray()) }
        } finally { kernel.drain() }
    }
}
