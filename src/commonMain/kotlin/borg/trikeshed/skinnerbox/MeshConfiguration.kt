package borg.trikeshed.skinnerbox

import borg.trikeshed.context.nuid.contains
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

/** Operator-provisioned enrollment and one pinned ownership epoch per namespace; contains no secrets. */
class MeshConfiguration private constructor(
    val local: MeshPeer,
    peers: Series<MeshPeer>,
    cells: Series<MeshCell>,
    persistenceGrants: Map<String, Set<String>>,
    apiKeys: Series<MeshApiKey>,
    allowedOrigins: Set<String>,
    twinNamespaces: Series<String>,
    twinGrants: Map<String, TwinGrant>,
    encoded: ByteArray,
) {
    private val peerValues = Array(peers.size) { peers[it] }
    private val cellValues = Array(cells.size) { cells[it] }
    private val apiValues = Array(apiKeys.size) { apiKeys[it] }
    private val namespaceValues = Array(twinNamespaces.size) { twinNamespaces[it] }
    private val grantValues = persistenceGrants.mapValues { it.value.toSet() }
    private val originValues = allowedOrigins.toSet()
    private val twinValues = twinGrants.toMap()
    private val encoded = encoded.copyOf()
    val peers: Series<MeshPeer> get() = peerValues.size j { peerValues[it] }
    val cells: Series<MeshCell> get() = cellValues.size j { cellValues[it] }
    val persistenceGrants: Map<String, Set<String>> get() = grantValues.mapValues { it.value.toSet() }
    val apiKeys: Series<MeshApiKey> get() = apiValues.size j { apiValues[it] }
    val allowedOrigins: Set<String> get() = originValues.toSet()
    val twinNamespaces: Series<String> get() = namespaceValues.size j { namespaceValues[it] }
    val twinGrants: Map<String, TwinGrant> get() = twinValues.toMap()
    fun bytes(): ByteArray = encoded.copyOf()

    companion object {
        const val SCHEMA = "skinnerbox.config/v1"
        const val PLAN_SCHEMA = "skinnerbox.plan/v1"
        const val MAX_BYTES = TwinJson.MAX_BYTES
        private val commonFields = setOf("schema", "peers", "persistenceGrants", "apiKeys", "allowedOrigins", "twinNamespaces", "twinGrants")

        /** Strict JSON, including duplicate keys, exact integers, bounds and rejection of unknown fields. */
        fun parse(bytes: ByteArray, localPublicKey: ByteArray): MeshConfiguration {
            val root = document(bytes, SCHEMA, "cells")
            val rawCells = array(root, "cells", 1..64)
            val namespaces = rawCells.map { text(record(it, setOf("namespace", "members", "decisionHex")), "namespace") }
            val common = common(root, localPublicKey, namespaces)
            val cells = rawCells.map { raw ->
                val fields = record(raw, setOf("namespace", "members", "decisionHex"))
                val namespace = text(fields, "namespace")
                val members = strings(fields, "members", 3..3).map { common.peer(it) }.toSeries()
                for (peer in members.view) require(namespace in common.grants[peer.id].orEmpty()) { "Cell member lacks persistence grant" }
                val decision = hex(text(fields, "decisionHex"), 1..MeshPlacement.MAX_DECISION_BYTES)
                val cell = MeshCell(namespace, members, decision)
                binding(cell, common)
                cell
            }.toSeries()
            return MeshConfiguration(common.local, common.peers, cells, common.grants, common.apiKeys,
                common.origins, common.twinNamespaces, common.twinGrants, TwinJson.encode(root))
        }

        /** Explicit provisioning action. Serve consumes the emitted cells without rerunning placement. */
        fun plan(bytes: ByteArray, localPublicKey: ByteArray, nowMillis: Long): MeshConfiguration {
            require(nowMillis >= 0) { "Planning time must be explicit and nonnegative" }
            val root = document(bytes, PLAN_SCHEMA, "placements")
            val requests = array(root, "placements", 1..64)
            val requestFields = setOf("namespace", "verb", "payloadHex", "sourceId", "hosts", "links", "policy", "load")
            val namespaces = requests.map { text(record(it, requestFields), "namespace") }
            val common = common(root, localPublicKey, namespaces)
            val cells = requests.map { raw ->
                val request = record(raw, requestFields)
                val namespace = text(request, "namespace")
                val source = common.peer(text(request, "sourceId"))
                require(namespace in common.grants[source.id].orEmpty()) { "Placement source lacks persistence grant" }
                val task = MeshTask(namespace, text(request, "verb"), hex(text(request, "payloadHex"), 0..MeshTask.MAX_PAYLOAD_BYTES))
                fun evidence(row: Map<String, Any?>): TwinObservation {
                    val author = common.peer(text(row, "authorId"))
                    require(namespace in common.grants[author.id].orEmpty() && namespace in common.twinNamespaces.view) {
                        "Placement evidence author lacks Twin namespace grant"
                    }
                    val grant = requireNotNull(common.twinGrants[author.id]) { "Placement evidence author has no Twin grant" }
                    val observation = TwinObservation.parse(TwinJson.encode(row["observation"], TwinObservation.MAX_BYTES))
                    grant.authorize(observation)
                    return observation
                }
                val hosts = array(request, "hosts", 3..MeshRouting.MAX_PEERS).map { rawHost ->
                    val row = record(rawHost, setOf("peerId", "authorId", "observation"))
                    val peer = common.peer(text(row, "peerId"))
                    require(namespace in common.grants[peer.id].orEmpty()) { "Candidate lacks persistence grant" }
                    val observation = evidence(row)
                    require(observation.source == TwinSource.MEASURED) { "Placement host evidence must be measured" }
                    MeshHost.fromTwin(peer, observation)
                }.toSeries()
                val links = array(request, "links", 0..4096).map { rawLink ->
                    val row = record(rawLink, setOf("authorId", "observation"))
                    MeshLinkQuote.fromTwin(evidence(row)).also { common.peer(it.sourceId); common.peer(it.targetId) }
                }.toSeries()
                val policy = record(request["policy"], setOf("authority", "countries", "maxCandidates"))
                val limit = integer(policy, "maxCandidates").also { require(it in 3..MeshRouting.MAX_CANDIDATES.toLong()) }
                val countries = strings(policy, "countries", 1..256).toSet()
                val load = record(request["load"], setOf("requestBytes", "replicaBytes", "responseBytes", "receiptBytes",
                    "processingUnits", "replicaUnits", "retentionSeconds"))
                val placed = MeshPlacement.select(task, source, hosts, links,
                    MeshPlacementPolicy(meshSubnet(text(policy, "authority")), countries, limit.toInt()),
                    MeshLoad(integer(load, "requestBytes"), integer(load, "replicaBytes"), integer(load, "responseBytes"),
                        integer(load, "receiptBytes"), integer(load, "processingUnits"), integer(load, "replicaUnits"),
                        integer(load, "retentionSeconds")), nowMillis)
                mapOf("namespace" to namespace, "members" to placed.members.view.map { it.peer.id },
                    "decisionHex" to hex(placed.decisionBytes()))
            }
            val result = root.toMutableMap().also {
                it.remove("placements"); it["schema"] = SCHEMA; it["cells"] = cells
            }
            return parse(TwinJson.encode(result), localPublicKey)
        }

        private class Common(val local: MeshPeer, val peers: Series<MeshPeer>, val grants: Map<String, Set<String>>,
            val apiKeys: Series<MeshApiKey>, val origins: Set<String>, val twinNamespaces: Series<String>,
            val twinGrants: Map<String, TwinGrant>) {
            fun peer(id: String): MeshPeer {
                MeshRouting.requireId(id)
                return requireNotNull(peers.view.firstOrNull { it.id == id }) { "Unenrolled configuration peer" }
            }
        }

        private fun common(root: Map<String, Any?>, localPublicKey: ByteArray, namespaces: List<String>): Common {
            require(localPublicKey.size == 32) { "Local public key length" }
            require(namespaces.toSet().size == namespaces.size) { "Duplicate namespace cell" }
            namespaces.forEach { meshSubnet(it); meshPersistenceToken(it) }
            val peers = array(root, "peers", 3..MeshRouting.MAX_PEERS).map { raw ->
                val row = record(raw, setOf("publicKeyHex", "failureDomain", "origin", "subnet"))
                MeshPeer(hex(text(row, "publicKeyHex"), 32..32), text(row, "failureDomain"), text(row, "origin"), meshSubnet(text(row, "subnet")))
            }.toSeries()
            require(peers.view.map { it.id }.toSet().size == peers.size) { "Duplicate enrolled identity" }
            require(peers.view.map { it.origin }.toSet().size == peers.size) { "Duplicate enrolled origin" }
            val local = requireNotNull(peers.view.firstOrNull { it.publicKey.contentEquals(localPublicKey) }) { "Local key is not enrolled" }
            fun peer(id: String): MeshPeer {
                MeshRouting.requireId(id)
                return requireNotNull(peers.view.firstOrNull { it.id == id }) { "Grant peer is not enrolled" }
            }
            val grants = mutableMapOf<String, Set<String>>()
            for (raw in array(root, "persistenceGrants", 1..MeshRouting.MAX_PEERS)) {
                val row = record(raw, setOf("peerId", "namespaces"))
                val peer = peer(text(row, "peerId"))
                val allowed = strings(row, "namespaces", 1..64)
                require(allowed.toSet().size == allowed.size && allowed.all { it in namespaces && (peer.subnet contains meshSubnet(it)) }) {
                    "Persistence grant must name distinct configured cells within peer scope"
                }
                require(grants.put(peer.id, allowed.toSet()) == null) { "Duplicate peer persistence grant" }
            }
            require(namespaces.all { it in grants[local.id].orEmpty() }) { "Local peer requires every configured namespace grant" }
            val apiKeys = array(root, "apiKeys", 0..1024).map { raw ->
                val row = record(raw, setOf("namespace", "tokenHash", "write"))
                val namespace = text(row, "namespace")
                require(namespace in namespaces) { "API grant has no cell" }
                MeshApiKey(namespace, ContentId(text(row, "tokenHash")), boolean(row, "write"))
            }.toSeries()
            require(apiKeys.view.map { it.tokenHash }.toSet().size == apiKeys.size) { "Duplicate API credential digest" }
            val origins = strings(root, "allowedOrigins", 0..32)
            require(origins.toSet().size == origins.size && origins.all { origin ->
                origin.length <= 512 && origin.all { it.code in 33..126 } &&
                    (origin.startsWith("https://") || origin.startsWith("http://127.0.0.1:") || origin.startsWith("http://localhost:")) &&
                    origin.substringAfter("://").none { it in "/?#@" }
            }) { "API CORS origins" }
            val twinNamespaces = strings(root, "twinNamespaces", 0..64)
            require(twinNamespaces.toSet().size == twinNamespaces.size && twinNamespaces.all { it in namespaces }) { "Twin namespace has no cell" }
            val twinGrants = mutableMapOf<String, TwinGrant>()
            for (raw in array(root, "twinGrants", 0..MeshRouting.MAX_PEERS)) {
                val row = record(raw, setOf("peerId", "sources", "scopes"))
                val peer = peer(text(row, "peerId"))
                require(twinNamespaces.any { it in grants[peer.id].orEmpty() }) { "Twin author lacks a Twin persistence namespace" }
                val sources = strings(row, "sources", 1..TwinSource.entries.size)
                require(sources.toSet().size == sources.size) { "Duplicate Twin source" }
                val sourceSet = sources.map { value -> requireNotNull(TwinSource.entries.firstOrNull { it.wire == value }) { "Twin source" } }.toSet()
                val scopes = array(row, "scopes", 1..64).map { rawScope ->
                    val scope = record(rawScope, setOf("kind", "prefix"))
                    val kind = text(scope, "kind"); val prefix = text(scope, "prefix")
                    if (kind != "*") TwinKey(kind, "config")
                    require(prefix.length <= 256 && prefix.none { it.code < 33 || it.code in 127..159 }) { "Twin key prefix bound" }
                    TwinScope(kind, prefix)
                }.toSeries()
                require(scopes.view.toSet().size == scopes.size) { "Duplicate Twin scope" }
                require(twinGrants.put(peer.id, TwinGrant(peer.id, sourceSet, scopes)) == null) { "Duplicate Twin author" }
            }
            require(twinNamespaces.isEmpty() == twinGrants.isEmpty()) { "Twin namespaces and author grants must be configured together" }
            return Common(local, peers, grants.toMap(), apiKeys, origins.toSet(), twinNamespaces.toSeries(), twinGrants.toMap())
        }

        /** A decision belongs to the configured namespace and the same ordered, enrolled N=3 identities. */
        private fun binding(cell: MeshCell, common: Common) {
            val value = CanonicalCbor.decodeMap(cell.decision)
            require(value["format"] == "skinnerbox-placement/v1") { "Unsupported placement decision" }
            val task = value["task"] as? List<*> ?: error("Placement decision task")
            require(task.size == 3 && task[0] == cell.namespace) { "Placement decision namespace mismatch" }
            fun peer(raw: Any?): MeshPeer {
                val row = raw as? List<*> ?: error("Placement peer record")
                require(row.size == 4)
                val publicKey = row[0] as? ByteArray ?: error("Placement peer public key")
                val enrolled = common.peer(ContentId.of(publicKey).hex)
                require(publicKey.contentEquals(enrolled.publicKey) && row[1] == enrolled.failureDomain &&
                    row[2] == enrolled.origin && row[3] == enrolled.subnet.toString()) { "Placement enrollment mismatch" }
                return enrolled
            }
            val source = peer(value["source"])
            val hosts = value["hosts"] as? List<*> ?: error("Placement host records")
            require(hosts.size in 3..MeshRouting.MAX_CANDIDATES)
            val candidates = hosts.map { raw ->
                val row = raw as? List<*> ?: error("Placement host record")
                require(row.size == 10)
                peer(row[0])
            }
            val selected = value["selected"] as? List<*> ?: error("Placement selected members")
            require(selected.size == 3)
            for (i in selected.indices) {
                val index = selected[i] as? Long ?: error("Placement selected index")
                require(index in 0..candidates.size.toLong())
                val selectedPeer = if (index == 0L) source else candidates[index.toInt() - 1]
                require(selectedPeer.id == cell.members[i].id) { "Placement decision member order mismatch" }
            }
        }

        private fun document(bytes: ByteArray, schema: String, body: String): Map<String, Any?> =
            record(TwinJson.parse(bytes), commonFields + body).also { require(it["schema"] == schema) { "Mesh configuration schema" } }

        @Suppress("UNCHECKED_CAST")
        private fun record(value: Any?, fields: Set<String>): Map<String, Any?> {
            val result = value as? Map<String, Any?> ?: error("Configuration object required")
            require(result.keys == fields) { "Configuration fields: expected ${fields.sorted()}" }
            return result
        }
        private fun text(value: Map<String, Any?>, field: String): String = value[field] as? String ?: error("Configuration $field text required")
        private fun integer(value: Map<String, Any?>, field: String): Long =
            (value[field] as? Long)?.takeIf { it >= 0 } ?: error("Configuration $field exact nonnegative integer required")
        private fun boolean(value: Map<String, Any?>, field: String): Boolean = value[field] as? Boolean ?: error("Configuration $field boolean required")
        private fun array(value: Map<String, Any?>, field: String, count: IntRange): List<*> =
            (value[field] as? List<*>)?.also { require(it.size in count) { "Configuration $field count bound" } }
                ?: error("Configuration $field array required")
        private fun strings(value: Map<String, Any?>, field: String, count: IntRange): List<String> =
            array(value, field, count).map { it as? String ?: error("Configuration $field string array required") }
        private fun hex(value: String, count: IntRange): ByteArray {
            require(value.length % 2 == 0 && value.length / 2 in count && value.all { it in "0123456789abcdef" }) { "Configuration lowercase hex bound" }
            return ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
