package borg.trikeshed.ipns

import borg.trikeshed.context.ElementState
import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchWireRouter
import borg.trikeshed.forge.server.CouchWire
import borg.trikeshed.job.CasStore
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.test.*

class IpnsNodeTest {
    @Test fun cancelledOwnerCannotOpenNode(): Unit = runBlocking {
        val directory = Files.createTempDirectory("ipns-node-cancel-")
        val path = directory.resolve("identity")
        val owner = SupervisorJob(coroutineContext[Job])
        val node = jvmIpnsNode(path.toString(), coroutineContext + owner)
        owner.cancel()
        try {
            assertFailsWith<CancellationException> { node.open() }
            assertEquals(ElementState.CLOSED, node.lifecycleState)
            assertFalse(Files.exists(path))
        } finally { node.close(); owner.join(); Files.delete(directory) }
    }

    @Test fun disconnectedCallerDoesNotAbandonAdmittedWork(): Unit = runBlocking {
        val directory = Files.createTempDirectory("ipns-node-")
        val path = directory.resolve("identity")
        val node = jvmIpnsNode(path.toString(), coroutineContext)
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var completed = false
        try {
            node.open()
            val client = async {
                node.request {
                    assertNotNull(currentCoroutineContext()[IpnsPublisher])
                    assertNotNull(currentCoroutineContext()[IpnsDht])
                    entered.complete(Unit)
                    proceed.await()
                    completed = true
                }
            }
            withTimeout(5000) { entered.await() }
            client.cancelAndJoin()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { node.close() }
            assertFalse(closing.isCompleted)
            assertFails { node.request { error("closed admission") } }
            proceed.complete(Unit)
            withTimeout(5000) { closing.await() }
            assertTrue(completed)
            assertEquals(ElementState.CLOSED, node.lifecycleState)
            assertFalse(Files.exists(directory.resolve("identity.lock")))
        } finally {
            proceed.complete(Unit)
            node.close()
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory.resolve("identity.lock"))
            Files.delete(directory)
        }
    }

    @Test fun failedStartupReleasesPersistentIdentityLease(): Unit = runBlocking {
        val directory = Files.createTempDirectory("ipns-node-failure-")
        val path = directory.resolve("identity")
        val crypto = JvmIpnsCrypto()
        val peers = IpnsDhtAddresses.bootstrap(IpnsDhtAddresses.PUBLIC_BOOTSTRAP.split(',').toSeries())
        val node = IpnsNode(path.toString(), crypto, peers, { error("TLS factory failure") }, coroutineContext)
        try {
            assertFailsWith<IllegalStateException> { node.open() }
            assertEquals(ElementState.CLOSED, node.lifecycleState)
            assertFalse(Files.exists(directory.resolve("identity.lock")))
            IpnsJournal.open(path.toString(), crypto).close()
        } finally { node.close(); Files.deleteIfExists(path); Files.delete(directory) }
    }

    /** Explicit public-network check through the daemon's real HTTP listener and common IPNS node. */
    @Test
    @Timeout(600) // Startup republication plus three independently bounded public DHT operations.
    fun daemonRoutesRepublishAndResolveOnPublicDht(): Unit = runBlocking {
        val path = System.getenv("TRIKESHED_IPNS_TEST_JOURNAL")
        assumeTrue(!path.isNullOrBlank(), "Set TRIKESHED_IPNS_TEST_JOURNAL to an existing public-test journal")
        val node = jvmIpnsNode(path!!, coroutineContext,
            limits = IpnsDhtLimits(rpcTimeoutMillis = 7000, operationTimeoutMillis = 120000))
        val cas = CasStore.inMemory()
        val db = Couch("trike", CouchStoreFactory.casBacked(cas), cas)
        val wire = CouchWire(CouchWireRouter(db, ""), null, this, ipns = node)
        val directory = Files.createTempDirectory("ipns-http-")
        val port = ServerSocket(0).use { it.localPort }
        val serverOwner = SupervisorJob(coroutineContext[Job])
        val serverScope = CoroutineScope(coroutineContext + Dispatchers.Default + serverOwner)
        suspend fun request(method: String, path: String): Pair<Int, Map<*, *>> = withContext(Dispatchers.IO) {
            // The external HTTP client is a fixture; the server and its DHT traffic use TrikeShed I/O.
            val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 2000
                connection.readTimeout = 300000
                val status = connection.responseCode
                val text = (if (status >= 400) connection.errorStream else connection.inputStream)
                    .bufferedReader().use { it.readText() }
                println("IPNS HTTP $method $path: $status $text")
                status to (JsonSupport.parse(text) as Map<*, *>)
            } finally { connection.disconnect() }
        }
        try {
            node.open()
            serverScope.launch {
                JvmKanbanServer(rawRoutes = listOf(wire::route), stateDir = directory.toFile()).run(port, null)
            }
            withTimeout(15000) {
                while (true) {
                    try { if (request("GET", "/api/v0/name/status").first == 200) break }
                    catch (_: java.net.ConnectException) { }
                    delay(50)
                }
            }
            val status = request("GET", "/api/v0/name/status").second
            val value = (status["latest"] as Map<*, *>)["Value"] as String
            val publication = request("POST", "/api/v0/name/publish?arg=$value")
            assertEquals(200, publication.first)
            val published = publication.second
            assertEquals(200, request("POST", "/api/v0/name/republish").first)
            val resolution = request("GET", "/api/v0/name/resolve?arg=${published["Name"]}")
            assertEquals(200, resolution.first)
            val resolved = resolution.second
            assertEquals(published["Name"], resolved["Name"])
            assertEquals(published["Value"], resolved["Path"])
            assertEquals(published["sequence"], resolved["sequence"])
            assertEquals(published["validUntil"], resolved["validUntil"])
        } finally { serverOwner.cancelAndJoin(); node.close(); directory.toFile().deleteRecursively() }
    }
}
