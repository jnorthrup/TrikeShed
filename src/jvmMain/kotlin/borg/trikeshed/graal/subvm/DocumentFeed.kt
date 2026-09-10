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
import borg.trikeshed.userspace.nio.DocumentBytes
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Host-library boundary. Document IO and curator workers are owned in commonMain. */
class DocumentFeed private constructor(
    private val input: DocumentInputElement,
    private val curator: DocumentCuratorElement,
    private val nlp: CoreNlpRuntime,
    private val cas: CasStore,
    private val points: PointcutBlackboardAdapter,
    private val tikaOptions: TikaRuntime.TikaOptions,
    private val stagingLba: Long?,
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
            tikaOptions: TikaRuntime.TikaOptions = TikaRuntime.TikaOptions(),
            stagingLba: Long? = null,
        ): DocumentFeed {
            require(stagingLba == null || stagingLba in 0..volume.capacity) { "Invalid document staging region" }
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
                return DocumentFeed(input, curator, nlp, cas, points, tikaOptions, stagingLba, routeId, job).also { it.start() }
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
            tikaOptions: TikaRuntime.TikaOptions = TikaRuntime.TikaOptions(),
            stagingLba: Long? = null,
        ): DocumentFeed = create(scope, volume, cas, log, bag, points, documentModel(brain, muxContext), modelId,
            tikaOptions = tikaOptions, stagingLba = stagingLba)

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
    private val observationFailures = AtomicInteger()
    private val observationFailure = AtomicReference<String?>()

    val observationFailureCount: Int get() = observationFailures.get()
    val lastObservationFailure: String? get() = observationFailure.get()

    data class Receipt(val cid: ContentId, val record: DocumentCurationRecord)

    private fun observe(stage: String, correlation: String, fields: Map<String, Any?> = emptyMap()) {
        try {
            land(points, stage, correlation, fields)
        } catch (failure: Exception) {
            observationFailures.incrementAndGet()
            observationFailure.set("$stage ($correlation): ${failure.message}")
        }
    }

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
                    observe("exchange", correlation, mapOf("originalCid" to originalCid.value,
                        "exchangeId" to payload.exchangeId, "sequence" to payload.seq))
                    val extracted = TikaRuntime.extract(bytes, name, mediaType, options = tikaOptions)
                    val textCid = cas.put(extracted.text.encodeToByteArray())
                    observe("extraction", correlation, mapOf("originalCid" to originalCid.value,
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
                observe("tap", "$routeId:${exchange.seq}", mapOf(
                    "preview" to exchange.body, "truncated" to exchange.truncated))
            })
        observe("routeStart", routeId)
    }

    /** Serialized CAS access and route requests; the curator owns NLP-before-model sequencing. */
    suspend fun submit(extent: DocumentExtent): Receipt = gate.withLock {
        check(!closed.get()) { "Document feed is closed" }
        submit(input.read(extent))
    }

    /** Only a caller-reserved staging region may receive HTTP/LCNC source bytes. */
    suspend fun submit(bytes: ByteArray, name: String, mediaType: String? = null): Receipt = gate.withLock {
        check(!closed.get()) { "Document feed is closed" }
        val lba = checkNotNull(stagingLba) { "Document source staging is not configured" }
        require(name.isNotBlank()) { "Document source name must not be blank" }
        submit(input.stage(lba, bytes, name, mediaType))
    }

    /** Re-admit exact retained source bytes through the same staging and extraction path. */
    suspend fun submit(originalCid: ContentId, name: String, mediaType: String? = null): Receipt = gate.withLock {
        check(!closed.get()) { "Document feed is closed" }
        val lba = checkNotNull(stagingLba) { "Document source staging is not configured" }
        require(name.isNotBlank()) { "Document source name must not be blank" }
        val bytes = cas.get(originalCid) ?: error("Missing original document $originalCid")
        check(ContentId.of(bytes) == originalCid) { "Original document CID mismatch" }
        submit(input.stage(lba, bytes, name, mediaType))
    }

    private suspend fun submit(source: DocumentBytes): Receipt {
        check(cas.put(source.bytes) == source.cid)
        val body = JsonSupport.stringify(mapOf("originalCid" to source.cid.value,
            "name" to source.extent.name, "mediaType" to source.extent.mediaType))
        val reply = withContext(Dispatchers.IO) {
            CamelRuntime.request(routeId, CamelRuntime.Request(body))
        }
        val cid = ContentId(reply.body)
        val recordBytes = cas.get(cid) ?: error("Camel returned an unretained curation receipt")
        return Receipt(cid, DocumentCuratorCodec.decode(recordBytes))
    }

    /** No new submissions; finish the active exchange before stopping its dependencies. */
    suspend fun drain() = withContext(NonCancellable) {
        closed.set(true)
        gate.withLock {
            try {
                withContext(Dispatchers.IO) { CamelRuntime.stop(routeId) }
                observe("routeStop", routeId)
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
