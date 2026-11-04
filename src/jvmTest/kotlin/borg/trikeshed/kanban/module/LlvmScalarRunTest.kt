package borg.trikeshed.kanban.module

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import borg.trikeshed.kernel.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class LlvmScalarRunTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    class Session(val directory: Path, parent: CoroutineContext) {
        val files = JvmFileOperations()
        val cas = FileCasStore(files, directory.resolve("cas").toString())
        val owner = SupervisorJob(parent[Job])
        val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch("llvm-demo", store, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas),
            ModuleRouteRegistry(), scope, { System.currentTimeMillis() }, directory.toFile())
        var kernel: KernelElement? = null
        var handle: ModuleHandle? = null
        suspend fun open(bitcode: ByteArray? = null) {
            if (bitcode != null) {
                val owned = checkNotNull(OroborosLcncWiring.llvm(ctx, profile(bitcode)))
                kernel = owned.a
                ctx.lcncRunners[LlvmScalarContract.TYPE] = owned.b.runner()
            }
            handle = KanbanModule().open(ctx)
        }
        fun profile(bitcode: ByteArray, hash: String = ContentId.of(bitcode).hex): String {
            val path = directory.resolve("module.bc").toString()
            val config = directory.resolve("llvm-profile.json").toString()
            files.write(path, bitcode)
            files.write(config, jsonOf(_m["module" j path, "sha256" j hash, "export" j "add",
                "abi" j LlvmScalarProfile.ABI]).encodeToByteArray())
            return config
        }
        suspend fun request(method: String, path: String, body: String = ""): Map<String, Any?> {
            val response = withTimeout(30_000) {
                requireNotNull(ctx.routes.match(path)?.route(method, path, "$method $path HTTP/1.1\r\n\r\n$body", null))
            }
            assertEquals(200, response.status, response.body)
            return reifyMap(response.body)
        }
        suspend fun close() = withContext(NonCancellable) {
            try { handle?.drain(); handle?.close() }
            finally { try { kernel?.drain() } finally { owner.cancelAndJoin() } }
        }
    }

    @Test fun mlirKernelProducesDurableLcncReceiptThatReopensWithoutExecution(): Unit = runBlocking {
        val bitcode = checkNotNull(javaClass.getResourceAsStream("/sulong/add-mlir.bc")).use { it.readAllBytes() }
        val first = Session(directory, coroutineContext)
        lateinit var receipt: Map<String, Any?>
        lateinit var evidence: Map<String, Any?>
        try {
            first.open(bitcode)
            val program = LcncProgram("llvm-mlir-demo",
                s_[LcncNode("sum", LlvmScalarContract.TYPE, _m["left" j "19", "right" j "23"], x = 40.0, y = 80.0),
                    LcncNode("total", LlvmScalarContract.TYPE, _m["right" j "-2"], x = 420.0, y = 80.0)],
                s_[LcncWire("sum", "value", "total", "left?")])
            receipt = first.request("POST", "/api/lcnc/run", jsonOf(_m["name" j program.name,
                "document" j reify(LcncProgramConfix.toJson(program))]))
            assertEquals("completed", receipt["status"])
            val outputs = receipt["outputs"] as Map<*, *>
            assertEquals(42, ((outputs["sum"] as Map<*, *>)["value"] as Number).toInt())
            assertEquals(40, ((outputs["total"] as Map<*, *>)["value"] as Number).toInt())
            for (key in s_["programCid", "receiptCid", "invocationsCid"].view) ContentId(receipt[key] as String)
            evidence = first.request("GET", "/api/lcnc/content?cid=${receipt["invocationsCid"]}")
            val entries = evidence["entries"] as List<*>
            assertEquals(2, entries.size)
            val entry = entries.first() as Map<*, *>
            assertEquals(LlvmScalarContract.TYPE, entry["type"])
            assertEquals("completed", entry["status"])
            val runtime = entry["runtime"] as Map<*, *>
            assertEquals(ContentId.of(bitcode).value, runtime["moduleCid"])
            assertEquals(true, runtime["verifyBitcode"])
            assertEquals("unobserved", runtime["internalProgress"])
            val command = first.request("GET", "/api/lcnc/content?cid=${receipt["receiptCid"]}")
            assertEquals(receipt["runId"], (command["lcncRun"] as Map<*, *>)["runId"])
            first.files.mkdirs("build/reports/sulong")
            for ((name, field) in s_["program" j "programCid", "receipt" j "receiptCid", "invocations" j "invocationsCid"].view) {
                val cid = ContentId(receipt[field] as String)
                val retained = checkNotNull(first.cas.get(cid))
                assertEquals(cid, ContentId.of(retained))
                first.files.write("build/reports/sulong/$name.json", retained)
            }
            first.files.write("build/reports/sulong/response-preview.json", jsonOf(receipt).encodeToByteArray())
        } finally { first.close() }

        val second = Session(directory, coroutineContext)
        try {
            second.open()
            assertFalse(LlvmScalarContract.TYPE in second.ctx.lcncRunners)
            val recovered = second.ctx.blackboard.get("lcnc/run/${receipt["runId"]}") as Map<*, *>
            assertEquals(receipt["receiptCid"], recovered["receiptCid"])
            assertEquals(receipt["invocationsCid"], recovered["invocationsCid"])
            assertEquals(evidence, second.request("GET", "/api/lcnc/content?cid=${receipt["invocationsCid"]}"))
            val command = second.request("GET", "/api/lcnc/content?cid=${receipt["receiptCid"]}")
            assertEquals("completed", (command["lcncRun"] as Map<*, *>)["status"])
        } finally { second.close() }
    }

    @Test fun failedConfiguredBootstrapReleasesItsRootOwner(): Unit = runBlocking {
        val session = Session(directory, coroutineContext)
        try {
            assertNull(OroborosLcncWiring.llvm(session.ctx, null))
            val children = session.owner.children.count()
            assertFails { OroborosLcncWiring.llvm(session.ctx, "relative.json") }
            assertFails { OroborosLcncWiring.llvm(session.ctx, session.profile(LlvmScalarTest.bytes(), "0".repeat(64))) }
            assertEquals(children, session.owner.children.count())
            assertFalse(LlvmScalarContract.TYPE in session.ctx.lcncRunners)
        } finally { session.close() }
    }
}
