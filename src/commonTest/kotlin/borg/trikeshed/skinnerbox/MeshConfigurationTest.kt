package borg.trikeshed.skinnerbox

import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.*

class MeshConfigurationTest {
    private val namespace = "mesh.eu.app"
    private val peers: Series<MeshPeer> = 3 j { index: Int ->
        MeshPeer(Ed25519.publicKey(ByteArray(32) { (index * 32 + it + 1).toByte() }), "zone-$index",
            "http://127.0.0.1:${9100 + index}", Subnet.parse("mesh.eu"))
    }
    private fun hex(value: ByteArray) = value.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value: Any?): Map<String, Any?> = value as Map<String, Any?>
    private fun parsed(value: ByteArray) = objectValue(TwinJson.parse(value))
    private fun draft(): Map<String, Any?> {
        fun observation(key: TwinKey, fields: Map<String, Any?>): Map<String, Any?> = parsed(
            TwinObservation.create(key, TwinSource.MEASURED, "configuration-test-fixture", 100, fields).bytes())
        val hosts = peers.view.map { peer -> mapOf("peerId" to peer.id, "authorId" to peers[0].id,
            "observation" to observation(TwinKey("host", peer.id), mapOf(
                "country_code" to "DE", "qualified" to true, "valid_until_ms" to 1000L,
                "processing_micro_usd_per_unit" to 2L, "storage_micro_usd" to 1L,
                "storage_bytes_per_unit" to 1024L, "storage_seconds_per_unit" to 3600L))) }
        val links = mutableListOf<Map<String, Any?>>()
        for (source in peers.view) for (target in peers.view) if (source.id != target.id) links += mapOf(
            "authorId" to peers[0].id, "observation" to observation(TwinKey("transfer", "${source.id}/${target.id}"), mapOf(
                "source" to source.id, "target" to target.id, "valid_until_ms" to 1000L,
                "ingress_micro_usd" to 1L, "ingress_bytes_per_unit" to 1024L,
                "egress_micro_usd" to 2L, "egress_bytes_per_unit" to 1024L)))
        return mapOf(
            "schema" to MeshConfiguration.PLAN_SCHEMA,
            "peers" to peers.view.map { mapOf("publicKeyHex" to hex(it.publicKey), "failureDomain" to it.failureDomain,
                "origin" to it.origin, "subnet" to it.subnet.toString()) },
            "persistenceGrants" to peers.view.map { mapOf("peerId" to it.id, "namespaces" to listOf(namespace)) },
            "apiKeys" to listOf(mapOf("namespace" to namespace, "tokenHash" to ContentId.of("digest-only-fixture".encodeToByteArray()).value, "write" to true)),
            "allowedOrigins" to listOf("http://localhost:8080"), "twinNamespaces" to listOf(namespace),
            "twinGrants" to peers.view.map { mapOf("peerId" to it.id, "sources" to listOf("measured"),
                "scopes" to listOf(mapOf("kind" to "host", "prefix" to ""), mapOf("kind" to "transfer", "prefix" to ""))) },
            "placements" to listOf(mapOf("namespace" to namespace, "verb" to "epoch", "payloadHex" to "01", "sourceId" to peers[0].id,
                "hosts" to hosts, "links" to links, "policy" to mapOf("authority" to "mesh", "countries" to listOf("DE"), "maxCandidates" to 3L),
                "load" to mapOf("requestBytes" to 128L, "replicaBytes" to 256L, "responseBytes" to 64L, "receiptBytes" to 32L,
                    "processingUnits" to 5L, "replicaUnits" to 1L, "retentionSeconds" to 7200L))),
        )
    }
    private fun plan(value: Map<String, Any?> = draft()) = MeshConfiguration.plan(TwinJson.encode(value), peers[0].publicKey, 200)
    private fun changePlacement(value: Map<String, Any?>, change: (Map<String, Any?>) -> Map<String, Any?>): Map<String, Any?> =
        value + ("placements" to (value["placements"] as List<*>).map { change(objectValue(it)) })
    private fun changeHost(value: Map<String, Any?>, change: (Map<String, Any?>) -> Map<String, Any?>) = changePlacement(value) { request ->
        request + ("hosts" to (request["hosts"] as List<*>).map { raw ->
            val host = objectValue(raw)
            host + ("observation" to change(objectValue(host["observation"])))
        })
    }

    @Test fun planUsesMeasuredQuotesAndRoundTripsOnePinnedEpochForEveryEnrolledPeer() {
        val config = plan()
        assertEquals(3, config.peers.size)
        assertEquals(peers[0].id, config.local.id)
        assertEquals(1, config.cells.size)
        assertEquals(3, config.cells[0].members.size)
        assertEquals(setOf(namespace), config.persistenceGrants.getValue(peers[0].id))
        val decision = CanonicalCbor.decodeMap(config.cells[0].decision)
        assertEquals(200L, decision["selectedAt"])
        assertEquals(1000L, decision["validUntil"])
        assertEquals(3, (decision["hosts"] as List<*>).size)
        assertEquals(6, (decision["links"] as List<*>).size)
        assertFalse(config.bytes().decodeToString().contains("placements"))
        for (peer in peers.view) {
            val loaded = MeshConfiguration.parse(config.bytes(), peer.publicKey)
            assertEquals(peer.id, loaded.local.id)
            assertEquals(config.cells[0].epoch, loaded.cells[0].epoch)
            assertContentEquals(config.bytes(), loaded.bytes())
        }
        val copy = config.bytes(); copy.fill(0)
        assertContentEquals(config.bytes(), plan().bytes())
        // Quote expiry does not change configured ownership or silently elect another writer on serve.
        assertEquals(config.cells[0].epoch, MeshConfiguration.parse(config.bytes(), peers[0].publicKey).cells[0].epoch)
    }

    @Test fun quotesNeedConfiguredAuthorSourceAndNamespaceAuthority() {
        val planned = changeHost(draft()) { it + ("source" to "planned") }
        assertFails { plan(planned) }
        val noHostScope = draft() + ("twinGrants" to listOf(mapOf("peerId" to peers[0].id, "sources" to listOf("measured"),
            "scopes" to listOf(mapOf("kind" to "transfer", "prefix" to "")))))
        assertFails { plan(noHostScope) }
        val otherAuthor = changePlacement(draft()) { request -> request + ("hosts" to (request["hosts"] as List<*>).map {
            objectValue(it) + ("authorId" to "a".repeat(64))
        }) }
        assertFails { plan(otherAuthor) }
        val noTwinNamespace = draft() + mapOf("twinNamespaces" to emptyList<String>(), "twinGrants" to emptyList<Any>())
        assertFails { plan(noTwinNamespace) }
    }

    @Test fun missingPermissionsAndEnrollmentNeverReceiveDefaultGrants() {
        val value = draft()
        val original = value["persistenceGrants"] as List<*>
        assertFails { plan(value + ("persistenceGrants" to original.drop(1))) }
        assertFails { plan(value + ("persistenceGrants" to original.take(2))) }
        val unknownKey = Ed25519.publicKey(ByteArray(32) { 99 })
        assertFails { MeshConfiguration.plan(TwinJson.encode(value), unknownKey, 200) }
        val rawPeers = value["peers"] as List<*>
        assertFails { plan(value + ("peers" to listOf(rawPeers[0], rawPeers[0], rawPeers[2]))) }
        val foreign = rawPeers.mapIndexed { index, raw -> if (index == 2) objectValue(raw) + ("subnet" to "mesh.us") else raw }
        assertFails { plan(value + ("peers" to foreign)) }
        val duplicateOrigin = rawPeers.map { objectValue(it) + ("origin" to peers[0].origin) }
        assertFails { plan(value + ("peers" to duplicateOrigin)) }
    }

    @Test fun unpricedExpiredOrFractionalQuotesCannotBecomeCheap() {
        val noLinks = changePlacement(draft()) { it + ("links" to emptyList<Any>()) }
        assertFails { plan(noLinks) }
        assertFails { MeshConfiguration.plan(TwinJson.encode(draft()), peers[0].publicKey, 1000) }
        val fractional = changeHost(draft()) { observation ->
            observation + ("fields" to (objectValue(observation["fields"]) + ("storage_micro_usd" to TwinNumber.of("0.5"))))
        }
        assertFails { plan(fractional) }
        val wrongUnit = changeHost(draft()) { observation ->
            observation + ("fields" to (objectValue(observation["fields"]) + ("storage_bytes_per_unit" to 0L)))
        }
        assertFails { plan(wrongUnit) }
    }

    @Test fun cellDecisionBindsNamespaceEnrollmentAndOrderedMembers() {
        val config = plan()
        val root = parsed(config.bytes())
        val cell = objectValue((root["cells"] as List<*>).single())
        val members = cell["members"] as List<*>
        assertFails { MeshConfiguration.parse(TwinJson.encode(root + ("cells" to listOf(cell + ("members" to members.reversed())))), peers[0].publicKey) }
        val oldDecision = CanonicalCbor.decodeMap(config.cells[0].decision)
        val task = oldDecision["task"] as List<*>
        val otherDecision = CanonicalCbor.encodeMap(oldDecision + ("task" to listOf("mesh.eu.other", task[1], task[2])))
        assertFails { MeshConfiguration.parse(TwinJson.encode(root + ("cells" to listOf(cell + ("decisionHex" to hex(otherDecision))))), peers[0].publicKey) }
        val changedPeers = (root["peers"] as List<*>).mapIndexed { index, raw ->
            if (index == 0) objectValue(raw) + ("origin" to "http://127.0.0.1:9999") else raw
        }
        assertFails { MeshConfiguration.parse(TwinJson.encode(root + ("peers" to changedPeers)), peers[0].publicKey) }
        val emptyDecision = root + mapOf("cells" to listOf(cell + mapOf("decisionHex" to "a0")))
        assertFails { MeshConfiguration.parse(TwinJson.encode(emptyDecision), peers[0].publicKey) }
    }

    @Test fun strictBoundedSchemaRejectsUnknownFieldsDuplicateJsonAndSecretTokenFields() {
        val root = draft()
        assertFails { plan(root + ("typo" to true)) }
        val raw = TwinJson.encode(root).decodeToString()
        val duplicate = raw.replaceFirst("{", "{\"schema\":\"${MeshConfiguration.PLAN_SCHEMA}\",")
        assertFails { MeshConfiguration.plan(duplicate.encodeToByteArray(), peers[0].publicKey, 200) }
        val token = root + ("apiKeys" to listOf(mapOf("namespace" to namespace, "token" to "unhashed-secret", "write" to true)))
        assertFails { plan(token) }
        assertFails { MeshConfiguration.parse(ByteArray(MeshConfiguration.MAX_BYTES + 1) { 32 }, peers[0].publicKey) }
        val fractionalLoad = changePlacement(root) { it + ("load" to (objectValue(it["load"]) + ("requestBytes" to TwinNumber.of("1.5")))) }
        assertFails { plan(fractionalLoad) }
        val duplicateHash = root + ("apiKeys" to (root["apiKeys"] as List<*>).let { it + it })
        assertFails { plan(duplicateHash) }
    }
}
