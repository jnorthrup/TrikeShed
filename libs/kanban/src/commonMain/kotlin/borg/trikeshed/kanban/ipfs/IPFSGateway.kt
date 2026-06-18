package borg.trikeshed.kanban.ipfs

import borg.trikeshed.lib.*
import borg.trikeshed.kanban.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * IPFS/Git SHA2 Entity Sharing — distributed kanban via content-addressed kanban cards.
 *
 * Each KanbanTask becomes a content-addressed entity (SHA-256) stored in IPFS.
 * Git commits provide provenance; IPNS provides mutable pointers.
 *
 * Architecture:
 *   KanbanTask → Git commit (SHA2) → IPFS (CID) → IPNS key (/ipns/k2k4r.../board/<boardId>)
 *                    ↓                          ↓
 *              Provenance log            DHT + WebRTC transport
 */

@Serializable data class KanbanEntity(
    val id: KanbanTaskId,
    val sha256: SHA256,           // Git commit hash
    val cid: CID,                 // IPFS content ID
    val ipnsKey: IPNSKey?,        // Mutable pointer
    val payload: KanbanTask,      // The actual task
    val gitCommit: GitCommit?,    // Provenance
    val dependencies: List<Triple<KanbanTaskId, SHA256, CID>>,
)

@JvmInline value class SHA256(val value: String) {
    companion object {
        fun of(bytes: ByteArray): SHA256 = SHA256(
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        )
        fun of(string: String): SHA256 = SHA256.of(string.toByteArray())
    }
}

@JvmInline value class CID(val value: String) {
    companion object {
        fun v1(sha256: SHA256): CID = CID("bafybeic${sha256.value.substring(0, 46)}")
        fun parse(s: String): CID = CID(s)
    }
}

@JvmInline value class IPNSKey(val value: String) {
    companion object {
        fun fromPeerId(peerId: String): IPNSKey = IPNSKey("/ipns/$peerId/board/tshed")
        const val EMPTY = IPNSKey("")
    }
}

@Serializable data class GitCommit(
    val sha: SHA256,
    val author: String,
    val message: String,
    val timestamp: Long,
    val parent: SHA256?,
    val tree: CID,
)

/** IPFS/Git Gateway — bridges kanban store to distributed CAS. */
interface IPFSGateway {
    /** Publish a kanban task to IPFS and register in IPNS. */
    suspend fun publish(task: KanbanTask, author: String): KanbanEntity

    /** Resolve a task by CID or IPNS path. */
    suspend fun resolve(ref: String): KanbanEntity?

    /** Get task by SHA256 (git commit). */
    suspend fun getBySHA256(sha: SHA256): KanbanEntity?

    /** Pin entity to local node for availability. */
    suspend fun pin(cid: CID): Boolean

    /** List all pinned kanban entities. */
    suspend fun listPinned(): List<CID>

    /** Get IPNS record for board. */
    suspend fun getBoardIPNS(boardId: KanbanBoardId): IPNSKey?

    /** Update IPNS record to point to new CID. */
    suspend fun updateIPNS(key: IPNSKey, cid: CID): Boolean
}

/** IPNS Reactor — maintains IPNS records via pubsub. */
class IPNSReactor(
    private val gateway: IPFSGateway,
    private val boardId: KanbanBoardId = KanbanBoardId("tshed"),
) {
    private val ipnsKey = IPNSKey.fromPeerId("local-peer")
    private val updates = Channel<KanbanEntity>(100)

    /** Start reactor — subscribes to IPNS updates and republishes on changes. */
    suspend fun start() {
        updates.consumeEach { entity ->
            gateway.getBoardIPNS(boardId)?.let { current ->
                if (current.value != ipnsKey.value || entity.cid != current) {
                    gateway.updateIPNS(ipnsKey, entity.cid)
                }
            }
        }
    }

    /** Feed entity updates (called from KanbanStore after task mutations). */
    suspend fun feed(entity: KanbanEntity) = updates.send(entity)

    /** Get current board CID from IPNS. */
    suspend fun currentBoardCID(): CID? = gateway.getBoardIPNS(boardId)?.let { gateway.resolve(it.value)?.cid }

    /** Stop reactor. */
    suspend fun stop() = updates.close()
}

/** Git-CAS Bridge — maps git commits to IPFS CIDs. */
class GitCASBridge {
    private val commitToCID = mutableMapOf<SHA256, CID>()
    private val cidToCommit = mutableMapOf<CID, SHA256>()

    /** Register a git commit → CID mapping. */
    fun register(commit: SHA256, cid: CID) {
        commitToCID[commit] = cid
        cidToCommit[cid] = commit
    }

    /** Get CID for git commit. */
    fun cidFor(commit: SHA256): CID? = commitToCID[commit]

    /** Get git commit for CID. */
    fun commitFor(cid: CID): SHA256? = cidToCommit[cid]

    /** Verify entity integrity: CID matches SHA256 of payload. */
    fun verify(entity: KanbanEntity): Boolean {
        val computed = SHA256.of(entity.payload.toString().toByteArray())
        return computed == entity.sha256 && (entity.cid == CID.v1(computed) || commitToCID[entity.sha256] == entity.cid)
    }
}

/** IPFSGateway implementation using IPFS HTTP API. */
class HTTP_IPFSGateway(
    private val apiBase: String = "http://localhost:5001/api/v0",
) : IPFSGateway {

    override suspend fun publish(task: KanbanTask, author: String): KanbanEntity {
        val json = task.toJSON()
        val bytes = json.toByteArray()
        val sha = SHA256.of(bytes)
        val cid = CID.v1(sha)
        val commit = GitCommit(sha, author, "kanban: ${task.title}", System.currentTimeMillis(), null, cid)
        // TODO: actual IPFS add + IPNS publish
        return KanbanEntity(task.id, sha, cid, IPNSKey.fromPeerId("local"), task, commit, emptyList())
    }

    override suspend fun resolve(ref: String): KanbanEntity? = TODO()

    override suspend fun getBySHA256(sha: SHA256): KanbanEntity? = TODO()

    override suspend fun pin(cid: CID): Boolean = TODO()

    override suspend fun listPinned(): List<CID> = TODO()

    override suspend fun getBoardIPNS(boardId: KanbanBoardId): IPNSKey? = TODO()

    override suspend fun updateIPNS(key: IPNSKey, cid: CID): Boolean = TODO()
}

/** KanbanStore extension for IPFS/Git integration. */
fun KanbanStore.ipfsGateway(gateway: IPFSGateway): IPFSReactor = IPNSReactor(gateway)

fun KanbanStore.publishToIPFS(task: KanbanTask, author: String): KanbanEntity = TODO()

/** DSL for configuring IPFS/Git in kanban. */
@DslMarker
annotation class KanbanIPFS

@KanbanIPFS
class KanbanIPFSConfig {
    var gateway: IPFSGateway? = null
    var autoPublish: Boolean = true
    var autoPin: Boolean = true

    fun gateway(g: IPFSGateway) { gateway = g }
    fun autoPublish(enabled: Boolean) { autoPublish = enabled }
    fun autoPin(enabled: Boolean) { autoPin = enabled }

    fun build(): KanbanIPFSModule = KanbanIPFSModule(this)
}

class KanbanIPFSModule private constructor(config: KanbanIPFSConfig) {
    val reactor by lazy { config.gateway?.let { IPNSReactor(it) } }

    suspend fun initialize() {
        reactor?.start()
    }

    suspend fun shutdown() {
        reactor?.stop()
    }
}

fun kanbanIPFS(block: KanbanIPFSConfig.() -> Unit): KanbanIPFSModule = KanbanIPFSConfig().apply(block).build()

/** KanbanTask extension for JSON serialization. */
private fun KanbanTask.toJSON(): String = kotlinx.serialization.json.Json.encodeToString(this)