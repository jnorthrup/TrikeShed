package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class LlvmConfixTest {
    companion object {
        fun bytes() = checkNotNull(LlvmConfixTest::class.java.getResourceAsStream("/sulong/confix.bc")).use { it.readAllBytes() }
        fun profile(limit: Int = 65536) = LlvmConfixProfile(Path.of("/operator/add.bc"), ContentId.of(bytes()).hex, limit, 16384)
        fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun oracle(format: Syntax, source: ByteArray): List<Map<String, Any?>> {
            val series = source.size j { index: Int -> source[index] }
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
            val recorded = mutableListOf<Map<String, Any?>>()
            for ((format, source) in fixtures.view) {
                val result = scanner.scan(format, source)
                assertEquals(true, result["ok"], "$format ${source.decodeToString()}: $result")
                assertEquals(oracle(format, source), result["rows"], "$format ${source.decodeToString()}")
                recorded.add(_m["format" j format.name, "sourceBase64" j Base64.getEncoder().encodeToString(source),
                    "native" j result, "referenceRows" j oracle(format, source), "matches" j true])
                assertEquals(ContentId.of(source).value, result["sourceCid"])
                val runtime = result["runtime"] as Map<*, *>
                assertEquals(0L, runtime["liveBytesAfter"])
                assertEquals(true, runtime["guardsVerified"])
            }
            val files = JvmFileOperations()
            files.mkdirs("build/reports/airgap-confix")
            files.write("build/reports/airgap-confix/corpus.json", jsonOf(recorded).encodeToByteArray())
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
                assertEquals(emptyList<Map<String, Any?>>(), result["rows"])
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

    @Test fun yamlNumericDisagreementIsRetainedWithoutRetagging(): Unit = runBlocking {
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("confix-test"))
        try {
            val scanner = LlvmConfix.open(kernel, KernelId("scan"), profile(), LlvmScalarTest.files(bytes()))
            val source = "v: 1e++5".encodeToByteArray()
            val result = scanner.scan(Syntax.YAML, source)
            val reference = oracle(Syntax.YAML, source)
            assertEquals(true, result["ok"])
            val rows = result["rows"] as List<*>
            assertEquals("IoDouble", (rows.last() as Map<*, *>)["tag"])
            assertEquals("IoString", reference.last()["tag"])
            assertNotEquals(reference, rows)
            val files = JvmFileOperations()
            files.mkdirs("build/reports/airgap-confix")
            files.write("build/reports/airgap-confix/yaml-disagreement.json", jsonOf(_m[
                "source" j source.decodeToString(), "native" j result, "referenceRows" j reference,
                "matches" j false, "scope" j "YAML number recognition differs across native and JVM semantics"
            ]).encodeToByteArray())
        } finally { kernel.drain() }
    }

    @Test fun strictProfilesAndFailedOpeningLeaveNoSeat(): Unit = runBlocking {
        val fields = _m["module" j "/operator/add.bc", "sha256" j ContentId.of(bytes()).hex,
            "abi" j LlvmConfixProfile.ABI, "maxInputBytes" j 65536, "maxRows" j 16384]
        assertEquals(16384, LlvmConfixProfile.parse(jsonOf(fields)).maxRows)
        for ((name, value) in s_.get<Join<String, Any?>>("maxRows" j 16385, "maxInputBytes" j 65537,
            "maxInputBytes" j 1.5, "module" j "relative.bc", "abi" j "unknown", "nativeAccess" j true).view)
            assertFails { LlvmConfixProfile.parse(jsonOf(fields + _m[name j value])) }
        assertFails { LlvmConfixProfile.parse(jsonOf(fields).dropLast(1) + ",\"maxRows\":1}") }
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("confix-test"))
        try {
            assertFails { LlvmConfix.open(kernel, KernelId("scan"), profile(), LlvmScalarTest.files(LlvmScalarTest.bytes())) }
            val wrong = LlvmScalarTest.bytes()
            assertFails { LlvmConfix.open(kernel, KernelId("scan"), LlvmConfixProfile(Path.of("/operator/add.bc"),
                ContentId.of(wrong).hex, 1024, 128), LlvmScalarTest.files(wrong)) }
            assertEquals(0, kernel.seats().size)
            assertEquals(true, LlvmConfix.open(kernel, KernelId("scan"), profile(), LlvmScalarTest.files(bytes()))
                .scan(Syntax.JSON, "{}".encodeToByteArray())["ok"])
        } finally { kernel.drain() }
    }

    @Test fun admittedCancellationSettlesBeforeEvidenceAndClose(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val observer = object : KernelPointcut {
            override fun dispatch(turn: KernelTurn) {
                if (turn.name == "confix.scan" && !entered.isCompleted) {
                    entered.complete(Unit); check(release.await(15, TimeUnit.SECONDS))
                }
            }
            override fun complete(receipt: KernelReceipt) = Unit
            override fun close() = Unit
        }
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("confix-test"))
        try {
            val scanner = LlvmConfix.open(kernel, KernelId("scan"), profile(), LlvmScalarTest.files(bytes()), observer)
            val invocation = LcncInvocationLedger.Invocation(mutableMapOf())
            val caller = async(invocation) { scanner.scan(Syntax.JSON, "{}".encodeToByteArray()) }
            withTimeout(15_000) { entered.await() }
            val secondSource = "[]".encodeToByteArray()
            val second = scanner.submit(Syntax.JSON, secondSource)
            secondSource.fill(0)
            caller.cancel()
            val drain = async(start = CoroutineStart.UNDISPATCHED) { scanner.drain() }
            assertFalse(caller.isCompleted)
            assertFalse(drain.isCompleted)
            assertFalse("runtime" in invocation.values)
            assertFails { scanner.submit(Syntax.JSON, ByteArray(0)) }
            release.countDown()
            assertFailsWith<CancellationException> { caller.await() }
            caller.join()
            val copied = second.await().getOrThrow()
            assertEquals(ContentId.of("[]".encodeToByteArray()).value, copied["sourceCid"])
            assertEquals(oracle(Syntax.JSON, "[]".encodeToByteArray()), copied["rows"])
            withTimeout(15_000) { drain.await() }
            assertEquals(0L, (invocation.values["runtime"] as Map<*, *>)["liveBytesAfter"])
            assertEquals(0, kernel.seats().size)
        } finally { release.countDown(); kernel.drain() }
    }

    @Test fun copiedGeometryRemainsReadableAfterContextClose(): Unit = runBlocking {
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("confix-test"))
        val source = "{\"rows\":[1,2,3]}".encodeToByteArray()
        val expected = oracle(Syntax.JSON, source)
        val result = try {
            LlvmConfix.open(kernel, KernelId("scan"), profile(), LlvmScalarTest.files(bytes())).scan(Syntax.JSON, source)
        } finally { kernel.drain() }
        repeat(3) { assertEquals(expected, result["rows"]) }
        assertTrue(jsonOf(result).contains("IoArray"))
    }
}
