package borg.trikeshed.skinnerbox

import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.htx.*
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** Three actual HTTP peers and three independently reopened durable Couch/CAS directories. */
class MeshNodeIntegrationTest {
    @Test fun remoteIngressCustodyRecoveryLookupAndEpochConflict() = runBlocking<Unit> {
        withTimeout(90_000) {
            val files = JvmFileOperations()
            val directory = Files.createTempDirectory("mesh-three-peer-").toString()
            val htx = openHtxElement()
            val listeners = arrayOfNulls<MeshListener>(3)
            val persistence = arrayOfNulls<MeshPersistence>(3)
            val nodes = arrayOfNulls<MeshNode>(3)
            val surfaces = arrayOfNulls<MeshDataSurface>(3)
            val token = "mesh-runtime-integration-credential-0001"
            val credentials = s_[MeshApiKey.fromToken("tenant.rows", token)]
            val owner = this
            fun listen(index: Int, address: String = "127.0.0.1:0"): MeshListener =
                MeshListener.open(owner, address) { method, path, headers, body ->
                    checkNotNull(nodes[index]).exchange.route()(method, path, body, null)
                        ?: checkNotNull(surfaces[index]).handle(method, path, headers, body)
                }
            try {
                for (index in 0..2) listeners[index] = listen(index)
                val peers = (0..2).map { index ->
                    MeshPeer(meshIdentity(31 + index).publicKey, "rack-$index",
                        "http://${checkNotNull(listeners[index]).address}", Subnet.parse("tenant"))
                }.toTypedArray()
                val enrolled: Series<MeshPeer> = peers.size j { index: Int -> peers[index] }
                val cell = MeshCell("tenant.rows", enrolled, CanonicalCbor.encodeMap(mapOf("decision" to "original")))
                val grants = peers.associate { it.id to setOf("tenant.rows") }
                val limits = MeshExchangeLimits(taskTimeoutMillis = 6000, rpcTimeoutMillis = 7000)
                suspend fun start(index: Int, membership: MeshCell = cell) {
                    val store = MeshPersistence.open(owner, FileCasStore(files, "$directory/$index/cas"),
                        files, "$directory/$index/db", grants)
                    persistence[index] = store
                    val node = MeshNode(peers[index], meshIdentity(31 + index), enrolled, s_[membership], store,
                        coroutineContext + htx, exchangeLimits = limits)
                    nodes[index] = node
                    surfaces[index] = MeshDataSurface(credentials, node::submit)
                }
                suspend fun stop(index: Int) {
                    listeners[index]?.drain(); listeners[index] = null
                    nodes[index]?.drain(); nodes[index] = null
                    persistence[index]?.drain(); persistence[index] = null
                    surfaces[index] = null
                }
                for (index in 0..2) start(index)

                // Invalid construction must not leave an uncompleted supervisor attached to this owner.
                val children = coroutineContext[Job]!!.children.toSet()
                assertFailsWith<IllegalArgumentException> {
                    MeshNode(peers[0], meshIdentity(40), enrolled, s_[cell], persistence[0]!!, coroutineContext + htx)
                }
                assertEquals(children, coroutineContext[Job]!!.children.toSet())

                suspend fun http(index: Int, method: HtxMethod, path: String, body: String = "",
                    request: String? = null): HtxResponse {
                    val headers = mutableListOf("Authorization" j "Bearer $token", "Content-Type" j "application/json")
                    request?.let { headers.add("Idempotency-Key" j it) }
                    return htx.request(parseHtxRequest(peers[index].origin + path, method = method,
                        body = ByteSeries(body.encodeToByteArray())).copy(headers = headers.toTypedArray().toSeries(),
                        timeoutMs = 15_000, maxResponseBytes = MeshEnvelope.MAX_WIRE_BYTES + 4096))
                }
                fun header(response: HtxResponse, name: String): String? = response.headers.view
                    .firstOrNull { it.a.equals(name, ignoreCase = true) }?.b
                fun put(request: String, key: String, value: String): MeshTask = MeshTask("tenant.rows", "row.put",
                    CanonicalCbor.encodeMap(mapOf("requestId" to request, "collection" to "tasks", "key" to key,
                        "expectedRevision" to null, "value" to value.encodeToByteArray())))

                val document = "{\"id\":\"first\",\"status\":\"ready\"}"
                val created = http(1, HtxMethod.POST, "/rest/v1/tasks", document, "first-request")
                assertEquals(201, created.status)
                val read = http(2, HtxMethod.GET, "/rest/v1/tasks?id=eq.first")
                assertEquals(200, read.status)
                assertTrue(read.body.toArray().decodeToString().contains("ready"))
                // Retry through another ingress preserves the stored event's original author and custody packet.
                val replayed = http(2, HtxMethod.POST, "/rest/v1/tasks", document, "first-request")
                assertEquals(201, replayed.status)
                assertEquals(header(created, "ETag"), header(replayed, "ETag"))
                assertEquals(header(created, "X-Mesh-Sequence"), header(replayed, "X-Mesh-Sequence"))
                val conflict = put("first-request", "first", "{\"id\":\"first\",\"status\":\"changed\"}")
                assertEquals(MeshPersistenceError.CONFLICT,
                    assertFailsWith<MeshPersistenceException> { nodes[1]!!.submit(conflict) }.reason)
                assertEquals(409, http(2, HtxMethod.POST, "/rest/v1/tasks",
                    "{\"id\":\"first\",\"status\":\"changed\"}", "first-request").status)

                val lookupKey = ContentId.of("routing".encodeToByteArray())
                val lookup = MeshPersistenceCodec.decode(nodes[1]!!.submit(MeshTask("tenant.rows", "mesh.lookup",
                    CanonicalCbor.encodeMap(mapOf("key" to lookupKey.value)))))
                assertEquals(cell.epoch.value, lookup["epoch"])
                val ids = lookup["peers"] as List<*>
                assertEquals(peers.map { it.id }.toSet(), ids.toSet())
                assertEquals(ids, ids.map { it as String }.sortedWith { a, b -> MeshRouting.compareDistance(lookupKey, a, b) })

                val offlineAddress = listeners[2]!!.address
                stop(2)
                val pendingTask = put("pending-request", "pending", "{\"id\":\"pending\",\"status\":\"durable\"}")
                val pending = assertFailsWith<MeshCustodyPending> { nodes[1]!!.submit(pendingTask) }
                assertEquals(2, pending.holders)
                assertEquals(cell.epoch, pending.epoch)
                assertEquals(true, MeshPersistenceCodec.decode(pending.result)["durable"])
                // The local commit is readable while a missing third custodian is honestly reported.
                assertEquals(200, http(1, HtxMethod.GET, "/rest/v1/tasks?id=eq.pending").status)
                val successor = put("successor-request", "successor", "{\"id\":\"successor\"}")
                val blocked = assertFailsWith<MeshPriorCustodyPending> { nodes[1]!!.submit(successor) }
                assertEquals(pending.record, blocked.record)
                assertEquals("pending-request", blocked.priorRequestId)
                assertEquals(404, http(1, HtxMethod.GET, "/rest/v1/tasks?id=eq.successor").status)
                // The same request remains explicitly committed, while a different request remains unadmitted.
                assertEquals(pending.record,
                    assertFailsWith<MeshCustodyPending> { nodes[1]!!.submit(pendingTask) }.record)
                val restartAddress = listeners[0]!!.address
                stop(0)
                listeners[0] = listen(0, restartAddress)
                start(0)
                assertEquals(pending.record,
                    assertFailsWith<MeshPriorCustodyPending> { nodes[1]!!.submit(successor) }.record)

                listeners[2] = listen(2, offlineAddress)
                start(2)
                // An identical retry from the restored peer obtains fresh proofs without another mutation.
                val completed = nodes[2]!!.submit(pendingTask)
                assertContentEquals(pending.result, completed)
                val receipt = MeshPersistenceCodec.decode(completed)
                assertEquals(true, MeshPersistenceCodec.decode(nodes[1]!!.submit(successor))["durable"])
                assertEquals(peers[1].id, MeshPersistenceCodec.decode(receipt["event"] as ByteArray)["peer"])
                for (index in 0..2) {
                    val held = MeshPersistenceCodec.decode(persistence[index]!!.execute(peers[index].id,
                        MeshTask("tenant.rows", "replica.read", CanonicalCbor.encodeMap(mapOf("contentId" to pending.record.value)))))
                    assertEquals(pending.record, ContentId.of(held["value"] as ByteArray))
                }

                // Reopen the primary with a different declared epoch. Existing durable ownership wins.
                val primaryAddress = listeners[0]!!.address
                stop(0)
                val changed = MeshCell("tenant.rows", enrolled, CanonicalCbor.encodeMap(mapOf("decision" to "changed")))
                listeners[0] = listen(0, primaryAddress)
                start(0, changed)
                val request = MeshTask("tenant.rows", "row.get", CanonicalCbor.encodeMap(mapOf("collection" to "tasks", "key" to "first")))
                assertEquals(MeshPersistenceError.CONFLICT,
                    assertFailsWith<MeshPersistenceException> { nodes[0]!!.submit(request) }.reason)
                stop(0)
                listeners[0] = listen(0, primaryAddress)
                start(0)
                val recovered = nodes[1]!!.submit(request)
                assertTrue((MeshPersistenceCodec.decode(recovered)["value"] as ByteArray).decodeToString().contains("ready"))
            } finally {
                withContext(NonCancellable) {
                    for (listener in listeners) listener?.drain()
                    for (node in nodes) node?.drain()
                    for (store in persistence) store?.drain()
                    htx.close()
                    files.deleteRecursively(directory)
                }
            }
        }
    }

    @Test fun postCommitCapacityFailurePreservesResultAndBlocksTheNextMutation() = runBlocking<Unit> {
        withTimeout(90_000) {
            // Two records permit bind+event but no local retention; four also permit retention but no ACK.
            for (recordLimit in listOf(2, 4)) {
                val files = JvmFileOperations()
                val directory = Files.createTempDirectory("mesh-postcommit-$recordLimit-").toString()
                val htx = openHtxElement()
                val nodes = arrayOfNulls<MeshNode>(3)
                val stores = arrayOfNulls<MeshPersistence>(3)
                val listeners = arrayOfNulls<MeshListener>(3)
                val owner = this
                fun listen(index: Int, address: String = "127.0.0.1:0") = MeshListener.open(owner, address) { method, path, _, body ->
                    checkNotNull(nodes[index]).exchange.route()(method, path, body, null)
                }
                try {
                    for (index in 0..2) listeners[index] = listen(index)
                    val peers = Array(3) { index -> MeshPeer(meshIdentity(51 + index).publicKey, "rack-$index",
                        "http://${listeners[index]!!.address}", Subnet.parse("tenant")) }
                    val enrolled: Series<MeshPeer> = peers.size j { index: Int -> peers[index] }
                    val cell = MeshCell("tenant.rows", enrolled, CanonicalCbor.encodeMap(mapOf("decision" to "capacity")))
                    val grants = peers.associate { it.id to setOf("tenant.rows") }
                    suspend fun start(index: Int, maximum: Int) {
                        val store = MeshPersistence.open(owner, FileCasStore(files, "$directory/$index/cas"), files,
                            "$directory/$index/db", grants, MeshPersistenceLimits(maxRecords = maximum))
                        stores[index] = store
                        nodes[index] = MeshNode(peers[index], meshIdentity(51 + index), enrolled, s_[cell], store,
                            coroutineContext + htx)
                    }
                    for (index in 0..2) start(index, if (index == 0) recordLimit else 32)
                    fun put(id: String) = MeshTask("tenant.rows", "row.put", CanonicalCbor.encodeMap(mapOf(
                        "requestId" to id, "collection" to "tasks", "key" to id, "expectedRevision" to null,
                        "value" to "{\"id\":\"$id\"}".encodeToByteArray())))
                    fun get(id: String) = MeshTask("tenant.rows", "row.get", CanonicalCbor.encodeMap(mapOf(
                        "collection" to "tasks", "key" to id)))
                    val original = put("original")
                    val pending = assertFailsWith<MeshCustodyPending> { nodes[1]!!.submit(original) }
                    assertEquals(if (recordLimit == 2) 2 else 3, pending.holders)
                    assertEquals(true, MeshPersistenceCodec.decode(pending.result)["durable"])
                    assertTrue((MeshPersistenceCodec.decode(nodes[1]!!.submit(get("original")))["value"] as ByteArray)
                        .decodeToString().contains("original"))
                    val blocked = assertFailsWith<MeshPriorCustodyPending> { nodes[1]!!.submit(put("successor")) }
                    assertEquals(pending.record, blocked.record)
                    assertEquals(MeshPersistenceError.NOT_FOUND,
                        assertFailsWith<MeshPersistenceException> { nodes[1]!!.submit(get("successor")) }.reason)
                    assertContentEquals(pending.result,
                        assertFailsWith<MeshCustodyPending> { nodes[1]!!.submit(original) }.result)
                    val address = listeners[0]!!.address
                    listeners[0]!!.drain(); listeners[0] = null
                    nodes[0]!!.drain(); nodes[0] = null
                    stores[0]!!.drain(); stores[0] = null
                    listeners[0] = listen(0, address)
                    start(0, 32)
                    assertContentEquals(pending.result, nodes[1]!!.submit(original))
                    assertEquals(true, MeshPersistenceCodec.decode(nodes[1]!!.submit(put("successor")))["durable"])
                } finally {
                    withContext(NonCancellable) {
                        for (listener in listeners) listener?.drain()
                        for (node in nodes) node?.drain()
                        for (store in stores) store?.drain()
                        htx.close()
                        files.deleteRecursively(directory)
                    }
                }
            }
        }
    }

}
