package borg.trikeshed.kanban.module

import borg.trikeshed.job.ContentId
import borg.trikeshed.kernel.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class LlvmConfixRunTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    @Test fun nativeSelectionConsumesOptionalPortSpellingsThroughTheActualRoute(): Unit = runBlocking {
        val session = LlvmScalarRunTest.Session(directory, coroutineContext)
        try {
            session.open(LlvmScalarTest.bytes())
            session.ctx.lcncRunners.putAll(PureNodes.registry { 0L })
            val scanner = LlvmConfix.open(checkNotNull(session.kernel), KernelId("scan"),
                LlvmConfixTest.profile(), LlvmScalarTest.files(LlvmConfixTest.bytes()))
            session.ctx.lcncRunners[LlvmConfixContract.TYPE] = scanner.runner(session.cas)
            val json = "{\"quote\":\"é水🙂\",\"page\":12}"
            val program = LcncProgram("native-selection-optional-ports", s_[
                LcncNode("text", "text.value", _m["value" j json]),
                LcncNode("scan", LlvmConfixContract.TYPE, _m["format" j "JSON"]),
                LcncNode("selection", "confix.pickPath", _m["geometry" j "native", "path" j "/"])],
                s_[LcncWire("text", "value", "scan", "data?"), LcncWire("text", "value", "selection", "data?"),
                    LcncWire("scan", "rows", "selection", "rows?"), LcncWire("scan", "ok", "selection", "ok?"),
                    LcncWire("scan", "format", "selection", "format?"), LcncWire("scan", "sourceCid", "selection", "sourceCid?")])
            val receipt = session.request("POST", "/api/lcnc/run", jsonOf(_m["name" j program.name,
                "document" j reify(LcncProgramConfix.toJson(program))]))
            assertEquals("completed", receipt["status"], jsonOf(receipt))
            val selection = ((receipt["outputs"] as Map<*, *>)["selection"] as Map<*, *>)
            assertEquals("native", selection["geometry"])
            assertEquals(ContentId.of(json.encodeToByteArray()).value, selection["sourceCid"])
            val value = selection["value"] as Map<*, *>
            assertEquals("é水🙂", value["quote"])
            assertEquals(12.0, (value["page"] as Number).toDouble())
        } finally { session.close() }
    }

    @Test fun sourceGeometryAndScalarSummarySurviveReopening(): Unit = runBlocking {
        val first = LlvmScalarRunTest.Session(directory, coroutineContext)
        val source = "{\"agreement\":\"Coinbase\",\"revision\":42,\"active\":true}"
        val bitcode = LlvmConfixTest.bytes()
        val scalar = checkNotNull(javaClass.getResourceAsStream("/sulong/add-mlir.bc")).use { it.readAllBytes() }
        lateinit var receipt: Map<String, Any?>
        lateinit var evidence: Map<String, Any?>
        try {
            first.open(scalar)
            val path = directory.resolve("confix.bc").toString()
            val config = directory.resolve("confix-profile.json").toString()
            first.files.write(path, bitcode)
            first.files.write(config, jsonOf(_m["module" j path, "sha256" j ContentId.of(bitcode).hex,
                "abi" j LlvmConfixProfile.ABI, "maxInputBytes" j 65536, "maxRows" j 16384]).encodeToByteArray())
            val scanner = checkNotNull(OroborosLcncWiring.confix(first.ctx, checkNotNull(first.kernel), config))
            first.ctx.lcncRunners[LlvmConfixContract.TYPE] = scanner.runner(first.cas)
            val program = LcncProgram("airgap-confix-source",
                s_[LcncNode("source", LlvmConfixContract.TYPE, _m["format" j "JSON", "data" j source], x = 40.0, y = 80.0),
                    LcncNode("summary", LlvmScalarContract.TYPE, _m["right" j "100"], x = 440.0, y = 80.0)],
                s_[LcncWire("source", "rowCount", "summary", "left?")])
            receipt = first.request("POST", "/api/lcnc/run", jsonOf(_m["name" j program.name,
                "document" j reify(LcncProgramConfix.toJson(program))]))
            assertEquals("completed", receipt["status"])
            val outputs = receipt["outputs"] as Map<*, *>
            val scanned = outputs["source"] as Map<*, *>
            assertEquals(true, scanned["ok"])
            assertEquals(7, (scanned["rowCount"] as Number).toInt())
            assertEquals(true, scanned["sourceRetained"])
            assertEquals(107, ((outputs["summary"] as Map<*, *>)["value"] as Number).toInt())
            val sourceCid = ContentId(scanned["sourceCid"] as String)
            assertEquals(ContentId.of(source.encodeToByteArray()), sourceCid)
            assertContentEquals(source.encodeToByteArray(), first.cas.get(sourceCid))
            evidence = first.request("GET", "/api/lcnc/content?cid=${receipt["invocationsCid"]}")
            val entries = evidence["entries"] as List<*>
            assertEquals(2, entries.size)
            val entry = entries.first() as Map<*, *>
            assertEquals(LlvmConfixContract.TYPE, entry["type"])
            val runtime = entry["runtime"] as Map<*, *>
            assertEquals(ContentId.of(bitcode).value, runtime["moduleCid"])
            assertEquals(true, runtime["guardsVerified"])
            assertEquals(0L, (runtime["liveBytesAfter"] as Number).toLong())
            first.files.mkdirs("build/reports/airgap-confix")
            for ((name, field) in s_["program" j "programCid", "receipt" j "receiptCid", "invocations" j "invocationsCid"].view) {
                val cid = ContentId(receipt[field] as String)
                val retained = checkNotNull(first.cas.get(cid))
                assertEquals(cid, ContentId.of(retained))
                first.files.write("build/reports/airgap-confix/$name.json", retained)
            }
            first.files.write("build/reports/airgap-confix/source.json", checkNotNull(first.cas.get(sourceCid)))
            first.files.write("build/reports/airgap-confix/response-preview.json", jsonOf(receipt).encodeToByteArray())
        } finally { first.close() }
        val second = LlvmScalarRunTest.Session(directory, coroutineContext)
        try {
            second.open()
            assertFalse(LlvmConfixContract.TYPE in second.ctx.lcncRunners)
            assertFalse(LlvmScalarContract.TYPE in second.ctx.lcncRunners)
            val recovered = second.ctx.blackboard.get("lcnc/run/${receipt["runId"]}") as Map<*, *>
            assertEquals(receipt["receiptCid"], recovered["receiptCid"])
            assertEquals(evidence, second.request("GET", "/api/lcnc/content?cid=${receipt["invocationsCid"]}"))
            assertContentEquals(source.encodeToByteArray(), second.cas.get(ContentId.of(source.encodeToByteArray())))
        } finally { second.close() }
    }

    @Test fun failedConfixConfigurationPreservesTheExistingScalarSeat(): Unit = runBlocking {
        val session = LlvmScalarRunTest.Session(directory, coroutineContext)
        try {
            session.open(LlvmScalarTest.bytes())
            val kernel = checkNotNull(session.kernel)
            val before = kernel.seats().view.toList()
            assertNull(OroborosLcncWiring.confix(session.ctx, kernel, null))
            assertFails { OroborosLcncWiring.confix(session.ctx, kernel, "relative.json") }
            val config = directory.resolve("invalid-confix.json").toString()
            session.files.write(config, "{}".encodeToByteArray())
            assertFails { OroborosLcncWiring.confix(session.ctx, kernel, config) }
            assertEquals(before, kernel.seats().view.toList())
            assertEquals(42, session.ctx.lcncRunners.getValue(LlvmScalarContract.TYPE)
                .execute(LcncNode("sum", LlvmScalarContract.TYPE, _m["left" j "20", "right" j "22"]), emptyMap())["value"])
        } finally { session.close() }
    }
}
