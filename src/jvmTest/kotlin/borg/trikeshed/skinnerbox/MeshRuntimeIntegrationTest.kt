package borg.trikeshed.skinnerbox

import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.htx.*
import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.ipns.IpnsKeyPair
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.nio.file.Files
import kotlin.test.*

/** The real launch composition, including strict priced config, startup refusal and lease cleanup. */
class MeshRuntimeIntegrationTest {
    private val seed = ByteArray(32) { (it + 91).toByte() }
    private val identity = IpnsKeyPair(Ed25519.publicKey(seed), seed)
    private val token = "mesh-runtime-test-credential-not-for-deployment"
    private val namespace = "tenant.rows"
    private val peers: Series<MeshPeer> = 3 j { index: Int ->
        val key = if (index == 0) identity.publicKey else Ed25519.publicKey(ByteArray(32) { (it + index).toByte() })
        MeshPeer(key, "fixture-domain-$index", "http://127.0.0.1:${9200 + index}", Subnet.parse("tenant"))
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun config(at: Long = 200): MeshConfiguration {
        val hosts: Series<MeshHost> = 3 j { index: Int -> MeshHost(peers[index], "DE", ContentId.of("host-$index".encodeToByteArray()),
            MeshHostQuote(100, 1000, if (index == 0) 0 else 100, MeshStorageRate(0, 1, 1)), true) }
        val links = mutableListOf<MeshLinkQuote>()
        for (a in peers.view) for (b in peers.view) if (a.id != b.id) links += MeshLinkQuote(a.id, b.id,
            ContentId.of((a.id + b.id).encodeToByteArray()), 100, 1000, MeshByteRate(0, 1), MeshByteRate(0, 1))
        val planned = MeshPlacement.select(MeshTask(namespace, "epoch", byteArrayOf(1)), peers[0], hosts, links.toSeries(),
            MeshPlacementPolicy(Subnet.parse("tenant"), setOf("DE"), 3), MeshLoad(1, 1, 1, 1, 1, 0, 1), at)
        assertEquals(peers[0].id, planned.executor.peer.id)
        val value = mapOf(
            "schema" to MeshConfiguration.SCHEMA,
            "peers" to peers.view.map { mapOf("publicKeyHex" to hex(it.publicKey), "failureDomain" to it.failureDomain,
                "origin" to it.origin, "subnet" to it.subnet.toString()) },
            "cells" to listOf(mapOf("namespace" to namespace, "members" to planned.members.view.map { it.peer.id },
                "decisionHex" to hex(planned.decisionBytes()))),
            "persistenceGrants" to peers.view.map { mapOf("peerId" to it.id, "namespaces" to listOf(namespace)) },
            "apiKeys" to listOf(mapOf("namespace" to namespace, "tokenHash" to MeshApiKey.fromToken(namespace, token).tokenHash.value, "write" to true)),
            "allowedOrigins" to emptyList<String>(), "twinNamespaces" to emptyList<String>(), "twinGrants" to emptyList<Any>(),
        )
        return MeshConfiguration.parse(TwinJson.encode(value), identity.publicKey)
    }

    @Test fun launcherAuthenticatesDrainsAndReopensWhileRejectingActiveLeaseAndChangedEpoch() = runBlocking<Unit> {
        withTimeout(45_000) {
            val files = JvmFileOperations()
            val directory = Files.createTempDirectory("mesh-launch-").toString()
            val configuration = config()
            val stop = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<String>()
            val client = openHtxElement()
            var launched: Deferred<String>? = null
            var reopened: MeshRuntime? = null
            val state = "$directory/state"
            suspend fun get(address: String, credential: String? = null): HtxResponse {
                val headers: Series<Twin<String>> = if (credential == null) emptySeries() else s_["Authorization" j "Bearer $credential"]
                return client.request(parseHtxRequest("http://$address/rest/v1/tasks?limit=1").copy(
                    headers = headers, timeoutMs = 5_000, maxResponseBytes = 4096))
            }
            try {
                files.write("$directory/config.json", configuration.bytes())
                files.write("$directory/key.seed", seed)
                val public = TwinJson.parse(MeshTool.run(this, files, listOf("identity", "$directory/key.seed"), stop).encodeToByteArray()) as Map<*, *>
                assertEquals(setOf("peerId", "publicKey"), public.keys)
                assertEquals(peers[0].id, public["peerId"])
                files.write("$directory/oversize.seed", ByteArray(33))
                assertFails { MeshTool.run(this, files, listOf("identity", "$directory/oversize.seed"), stop) }
                val running = async {
                    MeshTool.run(this, files, listOf("serve", "$directory/config.json", "$directory/key.seed", state, "127.0.0.1:0"), stop) {
                        ready.complete(it)
                    }
                }
                launched = running
                val notice = select<String> {
                    ready.onAwait { it }
                    running.onAwait { error("Server stopped before readiness: $it") }
                }
                val address = notice.substringAfter(" listening at ")
                assertEquals(401, get(address).status)
                assertEquals(401, get(address, "incorrect-credential").status)
                val accepted = get(address, token)
                assertEquals(200, accepted.status)
                assertEquals("[]", accepted.body.toArray().decodeToString())
                var duplicate: MeshRuntime? = null
                try {
                    assertFails { duplicate = MeshRuntime.open(this, files, configuration, identity, state, "127.0.0.1:0") }
                } finally { duplicate?.drain() }
                stop.complete(Unit)
                assertEquals("Skinnerbox drained", running.await())
                var changed: MeshRuntime? = null
                try {
                    val failure = assertFailsWith<MeshPersistenceException> {
                        changed = MeshRuntime.open(this, files, config(201), identity, state, "127.0.0.1:0")
                    }
                    assertEquals(MeshPersistenceError.CONFLICT, failure.reason)
                } finally { changed?.drain() }
                // Both normal shutdown and failed startup must release the writer lease.
                val active = MeshRuntime.open(this, files, configuration, identity, state, "127.0.0.1:0")
                reopened = active
                assertNull(active.twins)
                assertEquals(200, get(active.listener.address, token).status)
                active.drain()
                active.drain()
                reopened = null
            } finally {
                withContext(NonCancellable) {
                    stop.complete(Unit)
                    launched?.cancelAndJoin()
                    reopened?.drain()
                    client.close()
                    files.deleteRecursively(directory)
                }
            }
        }
    }
}
