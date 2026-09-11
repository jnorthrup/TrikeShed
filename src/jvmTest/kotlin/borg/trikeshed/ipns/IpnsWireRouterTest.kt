package borg.trikeshed.ipns

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchWireRouter
import borg.trikeshed.couch.WireReply
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.j
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HTTP contract tests with the real publisher/journal/record validator and an in-memory protocol peer. */
class IpnsWireRouterTest {
    val value = "/ipfs/${IpnsCid.raw("route fixture".encodeToByteArray())}"

    @Test fun missingElementsInputsMethodsAndBlockRouteRemainDistinct(): Unit = runTest {
        val router = router()
        suspend fun request(method: String, path: String, body: ByteArray = byteArrayOf()) =
            assertNotNull(router.handle(method, path, body))
        assertEquals(503, request("GET", "/api/v0/name/status").status)
        assertEquals(503, request("POST", "/api/v0/name/publish?arg=$value").status)
        assertEquals(503, request("POST", "/api/v0/name/republish").status)
        val name = JvmIpnsCrypto().generate().name
        assertEquals(503, request("GET", "/api/v0/name/resolve?arg=$name").status)
        assertEquals(400, request("POST", "/api/v0/name/publish").status)
        assertEquals(400, request("POST", "/api/v0/name/publish?arg=/ipfs/not-a-cid").status)
        assertEquals(400, request("GET", "/api/v0/name/resolve?arg=invalid").status)
        assertEquals(405, request("GET", "/api/v0/name/publish?arg=$value").status)
        assertEquals(405, request("POST", "/api/v0/name/status").status)
        val bytes = ByteArray(256) { it.toByte() }
        val stored = request("POST", "/api/v0/block/put", bytes)
        assertEquals(200, stored.status)
        val key = json(stored)["Key"]
        assertContentEquals(bytes, request("GET", "/api/v0/block/get?arg=$key").bytes)
        assertEquals(200, request("GET", "/trike").status)
    }

    @Test fun publishResolveRepublishAndStatusUseActualResults(): Unit = runTest {
        val node = Node(coroutineContext)
        try {
            node.publisher.open()
            val initial = json(node.request("GET", "/api/v0/name/status"))
            assertNull(initial["latest"])
            assertEquals("ACTIVE", initial["lifecycle"])
            assertEquals(0, node.puts)
            val published = node.request("POST", "/api/v0/name/publish?arg=$value")
            val result = json(published)
            assertEquals(200, published.status)
            assertEquals(node.publisher.name.toString(), result["Name"])
            assertEquals(value, result["Value"])
            assertEquals("0", result["sequence"])
            assertEquals(1, (result["acknowledgements"] as Number).toInt())
            assertEquals(true, result["complete"])
            val original = node.publisher.lastReport!!.record.bytes
            val republished = node.request("POST", "/api/v0/name/republish")
            assertEquals(200, republished.status)
            assertContentEquals(original, node.publisher.lastReport!!.record.bytes)
            for (method in arrayOf("GET", "POST")) {
                val resolved = node.request(method, "/api/v0/name/resolve?arg=/ipns/${node.publisher.name}")
                assertEquals(200, resolved.status)
                assertEquals(value, json(resolved)["Path"])
                assertEquals(true, json(resolved)["quorumReached"])
            }
            val beforeStatus = node.puts
            val status = json(node.request("GET", "/api/v0/name/status"))
            assertEquals(value, (status["latest"] as Map<*, *>)["Value"])
            assertEquals(true, (status["lastReport"] as Map<*, *>)["complete"])
            assertNull(status["lastFailure"])
            assertEquals(beforeStatus, node.puts)
        } finally { node.close() }
    }

    @Test fun localAssignmentAndBelowQuorumRecordsNeverReturnSuccess(): Unit = runTest {
        val node = Node(coroutineContext, replicas = 2, quorum = 2)
        try {
            node.publisher.open()
            val published = node.request("POST", "/api/v0/name/publish?arg=$value")
            val result = json(published)
            assertEquals(503, published.status)
            assertEquals("ipns_replication_incomplete", result["error"])
            assertEquals(1, (result["acknowledgements"] as Number).toInt())
            assertEquals(2, (result["replicaTarget"] as Number).toInt())
            assertNotNull(node.publisher.latest) // Durable assignment exists but did not satisfy replication.
            val resolved = node.request("GET", "/api/v0/name/resolve?arg=${node.publisher.name}")
            assertEquals(503, resolved.status)
            val resolution = json(resolved)
            assertEquals("ipns_quorum_incomplete", resolution["error"])
            assertEquals(value, resolution["candidatePath"])
            assertFalse(resolution.containsKey("Path"))
            assertEquals(1, (resolution["validResponses"] as Number).toInt())
            node.rejectPut = true
            val next = "/ipfs/${IpnsCid.raw("next fixture".encodeToByteArray())}"
            val rejected = node.request("POST", "/api/v0/name/publish?arg=$next")
            assertEquals(503, rejected.status)
            assertEquals("1", json(rejected)["sequence"])
            assertEquals(next, json(rejected)["Value"])
            assertEquals(0, (json(rejected)["acknowledgements"] as Number).toInt())
            assertEquals(1, (json(rejected)["failures"] as List<*>).size)
            assertEquals(false, (json(node.request("GET", "/api/v0/name/status"))["lastReport"] as Map<*, *>)["complete"])
        } finally { node.close() }
    }

    @Test fun malformedPublishDoesNotAdmitWorkAndRepublishNeedsARecord(): Unit = runTest {
        val node = Node(coroutineContext)
        try {
            node.publisher.open()
            assertEquals(400, node.request("POST", "/api/v0/name/republish").status)
            for (argument in arrayOf("https://example.com", "/ipns/not-a-name", "/ipfs/", "$value%00"))
                assertEquals(400, node.request("POST", "/api/v0/name/publish?arg=$argument").status)
            assertEquals(0, node.puts)
            assertNull(node.publisher.latest)
            val target = JvmIpnsCrypto().generate().name
            val linked = node.request("POST", "/api/v0/name/publish?arg=/ipns/$target/child")
            assertEquals(200, linked.status)
            assertEquals("/ipns/$target/child", json(linked)["Value"])
        } finally { node.close() }
    }

    @Test fun childTimeoutIsUnavailableButRequestCancellationPropagates(): Unit = runTest {
        val timed = Node(coroutineContext, rpcTimeout = 10, operationTimeout = 10)
        try {
            timed.publisher.open()
            timed.delayMillis = 100
            val response = timed.request("POST", "/api/v0/name/publish?arg=$value")
            assertEquals(503, response.status)
            assertEquals("ipns_unavailable", json(response)["error"])
            assertNotNull(timed.publisher.lastFailure)
            timed.delayMillis = 0
            assertEquals(200, timed.request("POST", "/api/v0/name/publish?arg=$value").status)
        } finally { timed.close() }
        val cancelled = Node(coroutineContext)
        try {
            cancelled.publisher.open()
            cancelled.delayMillis = 10_000
            var returned = false
            val request = launch {
                cancelled.request("GET", "/api/v0/name/resolve?arg=${cancelled.publisher.name}")
                returned = true
            }
            runCurrent()
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertFalse(returned)
            assertEquals(0, cancelled.dialer.active)
        } finally { cancelled.close() }
    }

    fun router(): CouchWireRouter {
        val cas = CasStore.inMemory()
        return CouchWireRouter(Couch("trike", CouchStoreFactory.casBacked(cas), cas), "projects/trikeshed/")
    }

    @Suppress("UNCHECKED_CAST")
    fun json(reply: WireReply): Map<String, Any?> = JsonSupport.parse(reply.bytes.decodeToString()) as Map<String, Any?>

    inner class Node(context: CoroutineContext, replicas: Int = 1, quorum: Int = 1,
                     rpcTimeout: Long = 1000, operationTimeout: Long = 10_000) {
        // Temporary-directory creation/cleanup is test harness setup; journal I/O uses the common uring facade.
        val directory = Files.createTempDirectory("ipns-router-")
        val path = directory.resolve("identity")
        val crypto = JvmIpnsCrypto()
        val journal = IpnsJournal.open(path.toString(), crypto)
        val router = router()
        var puts = 0
        var rejectPut = false
        var delayMillis = 0L
        var stored: IpnsDhtRecord? = null
        val dialer = IpnsDhtTest.CodecDialer { _, request ->
            if (delayMillis > 0) delay(delayMillis)
            when (request.type) {
                IpnsDhtType.PUT_VALUE -> {
                    puts++
                    check(!rejectPut) { "Peer rejects storage" }
                    stored = request.record
                    request
                }
                IpnsDhtType.GET_VALUE -> IpnsDhtMessage(request.type, request.key, stored)
                else -> IpnsDhtMessage(request.type)
            }
        }
        val dht = IpnsDht(dialer, crypto, 1 j { IpnsDhtTest().peer(1) }, IpnsDhtLimits(replication = replicas,
            minValidResponses = quorum, allowPrivateAddresses = true, rpcTimeoutMillis = rpcTimeout,
            operationTimeoutMillis = operationTimeout))
        val publisher = IpnsPublisher(journal, dht, crypto, context)

        suspend fun request(method: String, path: String): WireReply = withContext(publisher + dht) {
            assertNotNull(router.handle(method, path, byteArrayOf()))
        }

        suspend fun close() {
            try { publisher.close() }
            finally { Files.deleteIfExists(path); Files.deleteIfExists(directory.resolve("identity.lock")); Files.delete(directory) }
        }
    }
}
