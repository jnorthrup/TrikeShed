package borg.trikeshed.graal.subvm

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.DocumentCurationRecord
import borg.trikeshed.narsese.DocumentCuratorCodec
import borg.trikeshed.narsese.DocumentCuratorElement
import borg.trikeshed.narsese.DocumentCuratorObserver
import borg.trikeshed.narsese.DocumentSource
import borg.trikeshed.narsese.documentModel
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.pointcut.PointcutEvent
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.userspace.nio.DocumentExtent
import borg.trikeshed.userspace.nio.DocumentInputElement
import borg.trikeshed.userspace.nio.Volume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Host-library boundary. Document IO and curator workers are owned in commonMain. */
class DocumentFeed private constructor(
    private val input: DocumentInputElement,
    private val curator: DocumentCuratorElement,
    private val nlp: CoreNlpRuntime,
    private val cas: CasStore,
    private val points: PointcutBlackboardAdapter,
    val routeId: String,
    val job: kotlinx.coroutines.CompletableJob,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<DocumentFeed> {
        suspend fun create(
            scope: CoroutineScope,
            volume: Volume,
            cas: CasStore,
            log: DurableAppendLog,
            bag: BeliefBagElement,
            points: PointcutBlackboardAdapter,
            model: suspend (Prompt) -> ModelResponse,
            modelId: String,
            routeId: String = "document-${UUID.randomUUID()}",
        ): DocumentFeed {
            val job = SupervisorJob(scope.coroutineContext[Job])
            val owner = CoroutineScope(scope.coroutineContext + job)
            val nlp = CoreNlpRuntime()
            val input = DocumentInputElement.create(owner, volume)
            var curator: DocumentCuratorElement? = null
            try {
                curator = DocumentCuratorElement.create(owner, nlp, model, modelId, cas, log, bag,
                    observer = DocumentCuratorObserver { name, correlation, refs ->
                        land(points, name, correlation, mapOf("receipts" to List(refs.size) { refs[it].value }))
                    })
                return DocumentFeed(input, curator, nlp, cas, points, routeId, job).also { it.start() }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try { curator?.drain() } finally {
                        try { input.drain() } finally {
                            nlp.close()
                            job.complete()
                            job.join()
                        }
                    }
                }
                throw failure
            }
        }

        suspend fun create(
            scope: CoroutineScope,
            volume: Volume,
            cas: CasStore,
            log: DurableAppendLog,
            bag: BeliefBagElement,
            points: PointcutBlackboardAdapter,
            brain: BrainClient,
            muxContext: CoroutineContext,
            modelId: String,
        ): DocumentFeed = create(scope, volume, cas, log, bag, points, documentModel(brain, muxContext), modelId)

        private fun land(
            points: PointcutBlackboardAdapter,
            stage: String,
            correlation: String,
            fields: Map<String, Any?> = emptyMap(),
        ) {
            points.accept(PointcutEvent(VmFacet.JVM, "borg.trikeshed.document.Feed.$stage", null,
                "result", fields + mapOf("correlation" to correlation)))
        }
    }

    override val key: CoroutineContext.Key<*> get() = Key
    private val gate = Mutex()
    private val closed = AtomicBoolean(false)

    data class Receipt(val cid: ContentId, val record: DocumentCurationRecord)

    private fun start() {
        CamelRuntime.start(routeId, "direct:$routeId", "log:$routeId?showBody=false",
            processor = CamelRuntime.PayloadProcessor { payload ->
                // This is a synchronous Java Processor callback, not a GraalJS entry.
                runBlocking(job) {
                    val envelope = JsonSupport.parse(payload.body) as? Map<*, *>
                        ?: error("Document exchange must carry a source reference")
                    val originalCid = ContentId(envelope["originalCid"] as String)
                    val name = envelope["name"] as String
                    val mediaType = envelope["mediaType"] as? String
                    val correlation = "$routeId:${payload.seq}"
                    val bytes = cas.get(originalCid) ?: error("Missing original document $originalCid")
                    land(points, "exchange", correlation, mapOf("originalCid" to originalCid.value,
                        "exchangeId" to payload.exchangeId, "sequence" to payload.seq))
                    val extracted = TikaRuntime.extract(bytes, name, mediaType)
                    val textCid = cas.put(extracted.text.encodeToByteArray())
                    land(points, "extraction", correlation, mapOf("originalCid" to originalCid.value,
                        "extractedTextCid" to textCid.value, "characters" to extracted.text.length))
                    val source = DocumentSource(originalCid, textCid, extracted.text, name,
                        extracted.metadata["Content-Type"]?.firstOrNull() ?: mediaType ?: "application/octet-stream",
                        correlation, extracted.metadata)
                    val result = curator.curate(source)
                    CamelRuntime.Reply(result.recordCid.value,
                        mapOf("TrikeShedDocumentCid" to originalCid.value, "TrikeShedCurationCid" to result.recordCid.value))
                }
            },
            observer = CamelRuntime.Observer { exchange ->
                land(points, "tap", "$routeId:${exchange.seq}", mapOf(
                    "preview" to exchange.body, "truncated" to exchange.truncated))
            })
        land(points, "routeStart", routeId)
    }

    /** Serialized CAS access and route requests; NLP/model work fans out inside the curator. */
    suspend fun submit(extent: DocumentExtent): Receipt = gate.withLock {
        check(!closed.get()) { "Document feed is closed" }
        val source = input.read(extent)
        check(cas.put(source.bytes) == source.cid)
        val body = JsonSupport.stringify(mapOf("originalCid" to source.cid.value,
            "name" to extent.name, "mediaType" to extent.mediaType))
        val reply = withContext(Dispatchers.IO) {
            CamelRuntime.request(routeId, CamelRuntime.Request(body))
        }
        val cid = ContentId(reply.body)
        val recordBytes = cas.get(cid) ?: error("Camel returned an unretained curation receipt")
        Receipt(cid, DocumentCuratorCodec.decode(recordBytes))
    }

    /** No new submissions; finish the active exchange before stopping its dependencies. */
    suspend fun drain() = withContext(NonCancellable) {
        closed.set(true)
        gate.withLock {
            try {
                withContext(Dispatchers.IO) { CamelRuntime.stop(routeId) }
                land(points, "routeStop", routeId)
            } finally {
                try { curator.drain() } finally {
                    try { input.drain() } finally {
                        nlp.close()
                        job.complete()
                        job.join()
                    }
                }
            }
        }
    }
}
