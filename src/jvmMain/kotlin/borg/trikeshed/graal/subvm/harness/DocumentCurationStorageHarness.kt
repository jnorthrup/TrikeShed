package borg.trikeshed.graal.subvm.harness

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.DocumentFeed
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.module.KanbanModule
import borg.trikeshed.lcnc.DocumentCurationLegos
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.module.ModuleSupervisor
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.DocumentAppendLog
import borg.trikeshed.narsese.DocumentCuratorCodec
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.userspace.nio.DocumentFeedStorage
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.io.File

/** Two-process persistence verification. Model output is explicitly a deterministic fixture. */
object DocumentCurationStorageHarness {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size == 3 && args[0] in setOf("write", "reopen")) {
            "Arguments: write|reopen existing-storage-root repository-root"
        }
        val root = File(args[1]).canonicalPath
        // Repository input discovery only; document and ledger IO below use userspace channels.
        val text = File(args[2], "PRELOAD.md").readText()
        val passage = "Bounded\n  channels carry work through stages and return results or failures."
        val begin = text.indexOf(passage)
        check(begin >= 0)
        val scope = CoroutineScope(currentCoroutineContext())
        val storage = DocumentFeedStorage.open(scope, root, sourceCapacityBytes = 1024 * 1024)
        val bagLog = DocumentAppendLog.open("$root/document-belief-test.wal", root)
        val bag = BeliefBagElement(cas = storage.cas, wal = bagLog, parentJob = scope.coroutineContext[Job])
        val runners = mutableMapOf<String, LcncNodeRunner>()
        val pointcuts = PointcutBlackboardAdapter(ConfixBlackboard())
        val couchStore = CouchStoreFactory.casBacked(storage.cas)
        val routes = ModuleRouteRegistry()
        // Existing application router with a standard supervisor scope; no removed CCEK binding.
        val context = ModuleContext(Couch("curation-verification", couchStore, storage.cas),
            ReteNetwork(), ReteProductionRegistry(), bag, null, ConfixBlackboard(), storage.cas,
            CouchAttachmentGateway(couchStore, storage.cas), routes, scope,
            { System.currentTimeMillis() }, File(root), lcncRunners = runners)
        val modules = ModuleSupervisor(context)
        val server = JvmKanbanServer(moduleRoutes = routes, stateDir = File(root))
        var feed: DocumentFeed? = null
        var modelCalls = 0
        bag.open()
        try {
            modules.attach(KanbanModule())
            val initialBagSize = bag.size
            if (args[0] == "write") check(initialBagSize == 0) { "write phase requires a fresh verification directory" }
            else check(initialBagSize == 1) { "Belief WAL did not reopen its signal" }
            feed = DocumentCurationLegos.create(scope, storage.volume, storage.cas, storage.log, bag, pointcuts,
                model = { prompt ->
                    modelCalls++
                    val source = JsonSupport.parse(prompt.messages[1].content) as Map<*, *>
                    check((source["linguistics"] as Map<*, *>)["status"] == "AVAILABLE")
                    val extracted = source["text"] as String
                    val proposals = if (source["name"] == "fixture:positive") {
                        val quote = "Acme pays Beta."
                        val start = extracted.indexOf(quote)
                        check(start >= 0)
                        listOf(mapOf("subject" to "Acme", "predicate" to "pay", "object" to "Beta",
                            "confidence" to 0.73, "quote" to quote, "begin" to start, "end" to start + quote.length,
                            "polarity" to true, "modality" to "asserted"))
                    } else emptyList()
                    ModelResponse(JsonSupport.stringify(mapOf("format" to "TRIPLET_JSON", "triplets" to proposals)),
                        ModelUsage(-1, -1, -1), "fixture", "fixture")
                }, modelId = "fixture", runners = runners, stagingLba = 0)
            var replayed = 0
            storage.log.replay { _, _ -> replayed++ }
            if (args[0] == "reopen") check(replayed > 0)
            val outputs = linkedMapOf<String, Any?>()
            for ((name, sourceText) in listOf("PRELOAD.md:identified-passage" to passage, "fixture:positive" to "Acme pays Beta.")) {
                val originalCid = ContentId.of(sourceText.encodeToByteArray())
                val source = if (args[0] == "write") mapOf("text" to sourceText, "name" to name, "mediaType" to "text/plain")
                else {
                    check(storage.cas.get(originalCid)?.decodeToString() == sourceText) { "Original CAS bytes did not reopen" }
                    mapOf("originalCid" to originalCid.value, "name" to name, "mediaType" to "text/plain")
                }
                val request = JsonSupport.stringify(mapOf("type" to DocumentCurationLegos.CURATE,
                    "inputs" to mapOf("source" to source)))
                val response = server.routeHttp(("POST /api/lcnc/run HTTP/1.1\r\n" +
                    "Host: verification\r\nContent-Type: application/json\r\n\r\n$request").encodeToByteArray())
                check(response.status == 200) { response.body }
                val envelope = JsonSupport.parse(response.body) as Map<*, *>
                check(envelope["ok"] == true)
                val output = envelope["outputs"] as Map<*, *>
                val receiptCid = ContentId(output["receiptCid"] as String)
                val record = DocumentCuratorCodec.decode(checkNotNull(storage.cas.get(receiptCid)))
                check(record.source.originalCid == originalCid && record.nlp != null)
                check(record.nlp.text == record.source.text)
                if (name == "fixture:positive") {
                    if (args[0] == "write") check(record.submittedReceiptCids.size == 1)
                    else check(record.submittedReceiptCids.size == 0 && record.duplicateReceiptCids.size == 1)
                }
                outputs[name] = output
            }
            feed.drain()
            feed = null
            modules.drainAll()
            bag.drain()
            check(bag.size == 1 && modelCalls == 2)
            storage.drain()
            val io = storage.volume.ioReceipts()
            check(io.any { it.opcode.name == "WRITE" } && io.any { it.opcode.name == "READ" } && io.any { it.opcode.name == "FSYNC" })
            println(JsonSupport.stringify(mapOf(
                "phase" to args[0], "model" to "deterministic fixture; no provider claim",
                "entrypoint" to "JvmKanbanServer.routeHttp -> KanbanModule POST /api/lcnc/run -> document.curate",
                "transport" to "production HTTP parser/handler; no bound network listener in this fixture",
                "source" to mapOf("path" to "PRELOAD.md", "begin" to begin, "end" to begin + passage.length),
                "initialBagSize" to initialBagSize, "finalBagSize" to bag.size, "replayedFrames" to replayed,
                "backend" to storage.volume.backendReport.toReadable(),
                "channel" to storage.volume.channelReport?.let { mapOf("availability" to it.availability,
                    "capabilities" to it.capabilities, "nativeCapabilities" to it.nativeCapabilities) },
                "io" to io.map { mapOf("operation" to it.opcode.name, "offset" to it.byteOffset,
                    "length" to it.byteLength, "userData" to it.userData, "submitted" to it.submitted, "result" to it.res) },
                "outputs" to outputs, "drained" to true)))
        } finally {
            try { feed?.drain() } finally {
                try { modules.drainAll() } finally {
                  try { bag.drain() } finally {
                    try { bagLog.close() } finally { storage.drain() }
                  }
                }
            }
        }
    }
}
