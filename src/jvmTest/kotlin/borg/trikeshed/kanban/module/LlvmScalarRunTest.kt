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

    class Session(directory: Path, parent: CoroutineContext) {
        val files = JvmFileOperations()
        val cas = FileCasStore(files, directory.resolve("cas").toString())
        val owner = SupervisorJob(parent[Job])
        val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch("llvm-demo", store, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas),
            ModuleRouteRegistry(), scope, { System.currentTimeMillis() }, directory.toFile())
        val kernel = KernelElement(scope.coroutineContext, KernelId("llvm-demo"))
        var handle: ModuleHandle? = null
        suspend fun open(bitcode: ByteArray? = null) {
            if (bitcode != null) ctx.lcncRunners[LlvmScalarContract.TYPE] = LlvmScalar.open(kernel,
                KernelId("scalar"), LlvmScalarTest.profile(bitcode), LlvmScalarTest.files(bitcode)).runner()
            handle = KanbanModule().open(ctx)
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
            finally { try { kernel.drain() } finally { owner.cancelAndJoin() } }
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
                s_[LcncNode("sum", LlvmScalarContract.TYPE, _m["left" j "19", "right" j "23"]),
                    LcncNode("total", LlvmScalarContract.TYPE, _m["right" j "-2"])],
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
            first.files.write("build/reports/sulong/receipt.json", jsonOf(receipt).encodeToByteArray())
            first.files.write("build/reports/sulong/invocations.json", jsonOf(evidence).encodeToByteArray())
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
}
