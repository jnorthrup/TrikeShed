package borg.trikeshed.skinnerbox

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

/** One serialized twin owner above immutable persistence batches; it borrows, rather than closes, persistence. */
class MeshTwinElement private constructor(
    private val parent: CoroutineScope,
    private val persistence: MeshPersistence,
    private val localPeerId: String,
    grants: Map<String, TwinGrant>,
    namespaces: Series<String>,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<MeshTwinElement> {
        const val COLLECTION = "twin-batches"
        const val FORMAT = "skinnerbox.twin-batch/v1"
        const val MAX_BATCH = 64

        suspend fun open(scope: CoroutineScope, persistence: MeshPersistence,
            grants: Map<String, TwinGrant>, namespaces: Series<String>, localPeerId: String): MeshTwinElement {
            requireNotNull(scope.coroutineContext[Job]) { "Twin element requires an owning Job" }.ensureActive()
            require(grants.isNotEmpty() && namespaces.size in 1..1024)
            require(grants.all { (peer, grant) -> peer == grant.author }) { "Twin grant identity does not match verified peer" }
            require(localPeerId.isNotBlank()) { "Twin bootstrap reader identity" }
            val element = MeshTwinElement(scope, persistence, localPeerId, grants, namespaces)
            try {
                element.restore()
                element.start()
                return element
            } catch (failure: Throwable) {
                element.input.close()
                element.supervisor.cancelAndJoin()
                throw failure
            }
        }
    }

    override val key: CoroutineContext.Key<*> get() = Key
    private val grants = grants.toMap()
    private val stores = mutableMapOf<String, TwinStore>().also { map ->
        for (namespace in namespaces.view) {
            meshPersistenceToken(namespace)
            require(map.put(namespace, TwinStore()) == null) { "Duplicate twin namespace" }
        }
    }
    private val supervisor = SupervisorJob(parent.coroutineContext[Job])
    private var failure: Throwable? = null
    private class Request(val peer: String, val task: MeshTask) {
        val reply = CompletableDeferred<ByteArray>()
        val settled = CompletableDeferred<Unit>()
        fun fail(cause: Throwable) { reply.completeExceptionally(cause); settled.complete(Unit) }
    }
    private val input = Channel<Request>(32, onUndeliveredElement = { it.fail(CancellationException("Twin admission stopped")) })
    private lateinit var worker: Job

    private fun start() {
        worker = CoroutineScope(parent.coroutineContext + supervisor + this).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (request in input) {
                    // Once admitted, append and view publication settle together even when the caller disconnects.
                    withContext(NonCancellable) {
                        try { request.reply.complete(process(request.peer, request.task)) }
                        catch (cause: Throwable) { request.reply.completeExceptionally(cause) }
                        finally { request.settled.complete(Unit) }
                    }
                    yield()
                }
            } finally {
                input.close()
                while (true) (input.tryReceive().getOrNull() ?: break).fail(CancellationException("Twin owner stopped"))
                supervisor.complete()
            }
        }
    }

    suspend fun execute(peerId: String, task: MeshTask): ByteArray {
        currentCoroutineContext().ensureActive()
        val request = Request(peerId, task)
        val admission = input.trySend(request)
        if (admission.isFailure) {
            request.fail(admission.exceptionOrNull() ?: MeshOverloaded())
            return request.reply.await()
        }
        try { return request.reply.await() }
        finally { withContext(NonCancellable) { request.settled.await() } }
    }

    suspend fun drain() = withContext(NonCancellable) {
        input.close()
        worker.join()
        supervisor.complete()
        supervisor.join()
    }

    private suspend fun process(peer: String, task: MeshTask): ByteArray {
        persistence.authorize(peer, task.namespace)
        val grant = grants[peer] ?: throw MeshPersistenceException(MeshPersistenceError.UNAUTHORIZED, "Peer has no twin source grant")
        val store = stores[task.namespace] ?: throw MeshPersistenceException(MeshPersistenceError.NOT_FOUND, "Twin namespace is not open")
        failure?.let { throw IllegalStateException("Twin publication uncertain; reopen", it) }
        val body = MeshPersistenceCodec.decode(task.payload)
        val reply = when (task.verb) {
            "twin.merge" -> merge(peer, task, body, store, grant)
            "twin.get" -> {
                require(body.keys == setOf("kind", "key")) { "Twin get fields" }
                val key = TwinKey(text(body, "kind"), text(body, "key"))
                val observation = store.get(key) ?: throw MeshPersistenceException(MeshPersistenceError.NOT_FOUND, "Twin is absent")
                mapOf("observation" to observation.bytes(), "contentId" to observation.id.value, "savepoint" to store.savepoint().value)
            }
            "twin.residual" -> {
                require(body.keys.contains("known") && body.keys.all { it in setOf("known", "limit", "after", "savepoint") }) { "Twin residual fields" }
                val raw = body["known"] as? List<*> ?: error("Twin inventory must be a list")
                require(raw.size <= store.maxObservations)
                val known = raw.map { ContentId(it as? String ?: error("Twin inventory CID")) }.toSeries()
                val limit = if ("limit" in body) body["limit"] as? Long ?: error("Twin page integer") else 64L
                require(limit in 1..64)
                val after = body["after"]?.let { ContentId(it as? String ?: error("Twin continuation CID")) }
                val expected = body["savepoint"]?.let { ContentId(it as? String ?: error("Twin savepoint CID")) }
                checkSavepoint(store, expected)
                val page = store.residual(known, limit.toInt(), after = after, expectedSavepoint = expected)
                mapOf("savepoint" to page.savepoint.value, "observations" to page.observations.view.map { it.bytes() }, "after" to page.next?.value)
            }
            "twin.references" -> {
                require(body.keys.all { it in setOf("limit", "after", "savepoint") }) { "Twin reference fields" }
                val limit = if ("limit" in body) body["limit"] as? Long ?: error("Twin reference page integer") else 64L
                require(limit in 1..64)
                val cursor = body["after"]?.let { raw ->
                    val value = raw as? Map<*, *> ?: error("Twin reference cursor")
                    require(value.keys == setOf("kind", "key", "offset")) { "Twin reference cursor fields" }
                    val offset = value["offset"] as? Long ?: error("Twin reference offset")
                    require(offset in 0..TwinJson.MAX_VALUES.toLong()) { "Twin reference offset bound" }
                    TwinReferenceCursor(TwinKey(value["kind"] as? String ?: error("Twin reference kind"),
                        value["key"] as? String ?: error("Twin reference key")), offset.toInt())
                }
                val expected = body["savepoint"]?.let { ContentId(it as? String ?: error("Twin reference savepoint CID")) }
                checkSavepoint(store, expected)
                val page = store.referencePage(limit.toInt(), after = cursor, expectedSavepoint = expected)
                mapOf("savepoint" to page.savepoint.value, "issues" to page.issues.view.map { issue ->
                    mapOf("owner" to mapOf("kind" to issue.owner.kind, "key" to issue.owner.key),
                        "target" to issue.target?.let { mapOf("kind" to it.kind, "key" to it.key) },
                        "failure" to issue.failure.name.lowercase())
                }, "after" to page.next?.let { mapOf("kind" to it.owner.kind, "key" to it.owner.key, "offset" to it.offset.toLong()) })
            }
            "twin.project" -> {
                require(body.keys == setOf("model", "hardware", "continent")) { "Twin projection fields" }
                TwinWorld.project(store, text(body, "model"), text(body, "hardware"), text(body, "continent")).fields()
            }
            else -> error("Unknown twin task")
        }
        return MeshPersistenceCodec.encode(reply)
    }

    private suspend fun merge(peer: String, task: MeshTask, body: Map<String, Any?>,
        store: TwinStore, grant: TwinGrant): Map<String, Any?> {
        require(body.keys == setOf("requestId", "observations")) { "Twin merge fields" }
        val request = text(body, "requestId").also(::meshPersistenceToken)
        val observations = observations(body)
        // Even a retained retry is checked against current source policy.
        for (observation in observations.view) try { grant.authorize(observation) }
        catch (cause: IllegalArgumentException) { throw MeshPersistenceException(MeshPersistenceError.UNAUTHORIZED, "Twin source/key grant refused", cause) }
        val key = requestKey(peer, request)
        val retained = load(peer, task.namespace, key)
        val batch = if (retained != null) {
            val previous = decodeBatch(task.namespace, retained)
            if (previous["author"] != peer || previous["task"] != task.id.value)
                throw MeshPersistenceException(MeshPersistenceError.CONFLICT, "Twin request identity conflict")
            previous
        } else {
            val expected = store.preview(observations, grant)
            mapOf("format" to FORMAT, "namespace" to task.namespace, "author" to peer,
                "requestId" to request, "task" to task.id.value,
                "observations" to observations.view.map { it.bytes() },
                "added" to expected.added.toLong(), "savepoint" to expected.savepoint.value)
        }
        val batchBytes = MeshPersistenceCodec.encode(batch)
        require(batchBytes.size <= MeshPersistenceCodec.OBJECT_BYTES) { "Twin batch byte bound" }
        val put = MeshTask(task.namespace, "object.put", MeshPersistenceCodec.encode(mapOf(
            "requestId" to key, "collection" to COLLECTION, "key" to key,
            "expectedRevision" to null, "value" to batchBytes,
        )))
        val receipt = try { MeshPersistenceCodec.decode(persistence.execute(peer, put), MeshPersistenceCodec.REPLY_BYTES) }
        catch (cause: MeshPersistenceException) {
            if (cause.reason == MeshPersistenceError.IO) failure = cause
            throw cause
        }
        try { store.merge(observations, grant) }
        catch (cause: Throwable) { failure = cause; throw cause }
        return receipt + mapOf("added" to batch["added"], "savepoint" to batch["savepoint"])
    }

    private suspend fun load(peer: String, namespace: String, key: String): ByteArray? = try {
        val reply = MeshPersistenceCodec.decode(persistence.execute(peer, MeshTask(namespace, "object.get",
            MeshPersistenceCodec.encode(mapOf("collection" to COLLECTION, "key" to key)))), MeshPersistenceCodec.REPLY_BYTES)
        reply["value"] as? ByteArray ?: error("Twin retained batch value")
    } catch (absent: MeshPersistenceException) {
        if (absent.reason == MeshPersistenceError.NOT_FOUND) null else throw absent
    }

    private suspend fun restore() {
        for ((namespace, store) in stores) {
            persistence.authorize(localPeerId, namespace)
            val reader = localPeerId
            var after: String? = null
            var records = 0
            do {
                val page = MeshPersistenceCodec.decode(persistence.execute(reader, MeshTask(namespace, "object.list",
                    MeshPersistenceCodec.encode(mapOf("collection" to COLLECTION, "after" to after, "limit" to 64L)))), MeshPersistenceCodec.REPLY_BYTES)
                val rows = page["rows"] as? List<*> ?: error("Twin retained rows")
                for (raw in rows) {
                    require(++records <= persistence.limits.maxRecords) { "Twin replay record bound" }
                    val row = raw as? Map<*, *> ?: error("Twin retained row")
                    val batch = decodeBatch(namespace, row["value"] as? ByteArray ?: error("Twin retained bytes"))
                    val author = text(batch, "author")
                    require(row["key"] == requestKey(author, text(batch, "requestId"))) { "Twin retained request key" }
                    persistence.authorize(author, namespace)
                    store.merge(observations(batch), requireNotNull(grants[author]) { "Retained twin author has no source grant" })
                }
                val next = page["after"] as? String
                if (page["more"] == true) require(next != null && (after == null || next > after)) { "Twin replay cursor did not advance" }
                after = next
            } while (page["more"] == true)
        }
    }

    private fun decodeBatch(namespace: String, bytes: ByteArray): Map<String, Any?> {
        val batch = MeshPersistenceCodec.decode(bytes, MeshPersistenceCodec.OBJECT_BYTES)
        require(batch.keys == setOf("format", "namespace", "author", "requestId", "task", "observations", "added", "savepoint") &&
            batch["format"] == FORMAT && batch["namespace"] == namespace) { "Twin retained batch shape" }
        val request = text(batch, "requestId").also(::meshPersistenceToken)
        require(text(batch, "author").isNotBlank())
        val observations = observations(batch)
        val task = MeshTask(namespace, "twin.merge", MeshPersistenceCodec.encode(mapOf(
            "requestId" to request, "observations" to observations.view.map { it.bytes() },
        )))
        require(batch["task"] == task.id.value) { "Twin retained task identity" }
        val added = batch["added"] as? Long ?: error("Twin retained addition count")
        require(added in 0..observations.size.toLong())
        ContentId(text(batch, "savepoint"))
        return batch
    }

    private fun observations(body: Map<String, Any?>): Series<TwinObservation> {
        val values = body["observations"] as? List<*> ?: error("Twin observations must be a list")
        require(values.size in 1..MAX_BATCH) { "Twin batch count" }
        return values.map { TwinObservation.parse(it as? ByteArray ?: error("Twin observation bytes")) }.toSeries()
    }

    private fun requestKey(peer: String, request: String): String = "twin." + ContentId.of(
        MeshPersistenceCodec.encode(mapOf("author" to peer, "requestId" to request))).hex
    private fun checkSavepoint(store: TwinStore, expected: ContentId?) {
        if (expected != null && expected != store.savepoint()) throw MeshPersistenceException(MeshPersistenceError.CONFLICT, "Twin savepoint changed")
    }
    private fun text(body: Map<String, Any?>, key: String): String = body[key] as? String ?: error("Twin $key must be text")
}
