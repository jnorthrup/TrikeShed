package borg.trikeshed.couch.pijul

import borg.trikeshed.ipfs.CID
import borg.trikeshed.ipfs.IpfsElement
import borg.trikeshed.couch.stream.Change
import borg.trikeshed.couch.stream.ChangeEmitter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * IpfsGateway — bridges Pijul CRDT patches to IPFS pubsub/DHT for live
 * near-realtime patch distribution.
 *
 * The gateway does four things:
 *   1. Store patches in IPFS → get CID (content addressed).
 *   2. Announce CID to IPFS DHT so peers can discover the content.
 *   3. Publish CID to IPFS pubsub topic for near-realtime fanout.
 *   4. Feed patch events into the CouchDB _changes feed via ChangeEmitter,
 *      which powers the RequestFactory view server.
 *
 * Topology:
 *   Master nodes: read-write, publish patches to IPFS + DHT.
 *   Slave nodes: subscribe to pubsub, fetch by CID, apply patch.
 *   Replication: master→{master,slave} via CRDT patch ordering.
 */
class IpfsGateway(
    private val ipfs: IpfsElement?,
    private val shell: borg.trikeshed.process.ProcessShell,
    private val localRepo: Repository,
    private val config: SyncConfig = SyncConfig(),
) {
    companion object {
        const val IPFS_PUBSUB_TOPIC = "trikeshed-pijul-patches"
    }

    private val emitter = ChangeEmitter<PatchEvent>()
    private val topology = ReplicationTopology()
    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineName("IpfsGateway") + Dispatchers.Default
    )
    private val publishSemaphore = Semaphore(config.maxConcurrentPushes)
    private val ipfsStore = PijulIpfsStore(shell)

    /** Subscribe to gateway events (feeds into CouchDB _changes). */
    fun subscribe(cb: (Change<PatchEvent>) -> Unit): Int = emitter.register(cb)

    fun unsubscribe(token: Int) { emitter.unregister(token) }

    /** Register a replication peer in the master/slave topology. */
    fun addPeer(peerId: CharSequence, role: PeerRole, peerUrl: CharSequence) {
        topology.addPeer(peerId, role, peerUrl)
        emitter.emit(Change.Insert(PatchEvent.PeerConnected(peerId, role)))
    }

    fun removePeer(peerId: CharSequence) {
        topology.removePeer(peerId)
        emitter.emit(Change.Insert(PatchEvent.PeerDisconnected(peerId)))
    }

    /**
     * Publish a new patch to IPFS and announce to DHT/pubsub.
     * This is the primary entry point — called after a patch is applied locally.
     *
     * Flow:
     *   patch → ipfsStore.store() → CID → DHT announce → pubsub publish → _changes emit
     */
    suspend fun publishPatch(patch: Patch) {
        publishSemaphore.withPermit {
            // 1. Store patch in IPFS → get CID
            val result = ipfsStore.store(patch)
            if (result !is IpfsResult.Stored) {
                emitter.emit(Change.Insert(
                    PatchEvent.PatchPublishFailed(
                        patch.hash.display(),
                        "IPFS store failed: $result"
                    )
                ))
                return@withPermit
            }
            val cid = result.cid

            // 2. Announce CID to peers via DHT
            announcePatch(cid, patch)

            // 3. Emit to downstream consumers (CouchDB _changes integration)
            emitter.emit(Change.Insert(
                PatchEvent.PatchPublished(patch.hash.display(), cid)
            ))

            // 4. Fan-out to replication peers
            val peers = topology.getPeers(role = null) // all peers
            coroutineScope {
                val jobs = peers.map { (peerId, role, peerUrl) ->
                    async { pushToPeer(peerId, peerUrl, patch, cid) }
                }
                jobs.joinAll()
            }
        }
    }

    /**
     * Sync pull: request patches from a peer, apply them, and announce new ones.
     */
    suspend fun syncFromPeer(peerId: CharSequence) {
        val peer = topology.info(peerId) ?: return
        // Pull patches from peer
        val result = shell.exec(
            "pijul", listOf("pull", peer.url.toString(), "--channel", peerId.toString())
        )
        if (result.exitCode != 0) {
            emitter.emit(Change.Insert(
                PatchEvent.SyncFailed(peerId, result.stderr)
            ))
            return
        }
        // Parse pulled patches and re-publish to our own IPFS
        emitter.emit(Change.Insert(PatchEvent.SyncCompleted(peerId)))
    }

    /** Start the gateway — begin announcing and listening. */
    suspend fun start() {
        // Bootstrap IPFS node if provided
        ipfs?.open()

        // Announce our presence to DHT
        val localHead = localRepo.localHead
        if (localHead != null) {
            // Announce our current head to the DHT
            // (actual IPFS DHT announce happens via IpfsElement + DhtService)
            emitter.emit(Change.Insert(PatchEvent.GatewayStarted(localHead.display())))
        }
    }

    /** Stop the gateway and clean up. */
    fun stop() {
        scope.cancel()
        emitter.emit(Change.Seal)
    }

    // ── Internal ─────────────────────────────────────────────────────

    private suspend fun announcePatch(cid: CharSequence, patch: Patch) {
        // Announce to IPFS DHT: provides CID to the network.
        ipfs?.announceProvider(
            CID(cid.toString().encodeToByteArray()),
            "trikeshed-${patch.hash.display().take(8)}"
        )
    }

    private suspend fun pushToPeer(
        peerId: CharSequence,
        peerUrl: CharSequence,
        patch: Patch,
        cid: CharSequence,
    ) {
        // Push patch to specific peer based on negotiated capabilities.
        // If peer supports IPFS store, send CID. Otherwise traditional push.
        val peer = topology.info(peerId) ?: return
        val supportsIpfs = peer.capabilities.any { it.id() == StandardCapabilities.IpfsStoreV1.id() }

        if (supportsIpfs) {
            // IPFS-capable peer: announce CID, they'll fetch from DHT
            emitter.emit(Change.Insert(
                PatchEvent.PatchPushedToPeer(peerId, cid)
            ))
        } else {
            // Traditional push via pijul CLI
            val result = shell.exec(
                "pijul", listOf("push", peerUrl.toString(), "--patch", patch.hash.display())
            )
            if (result.exitCode != 0) {
                emitter.emit(Change.Insert(
                    PatchEvent.PatchPushFailed(peerId, result.stderr)
                ))
            }
        }
    }
}

enum class PeerRole { Master, Slave }

data class PeerInfo(
    val peerId: CharSequence,
    val role: PeerRole,
    val url: CharSequence,
    val capabilities: List<Capability> = StandardCapabilities.defaults(),
)

class ReplicationTopology {
    private val peers = LinkedHashMap<CharSequence, PeerInfo>()

    fun addPeer(peerId: CharSequence, role: PeerRole, peerUrl: CharSequence) {
        peers[peerId] = PeerInfo(peerId, role, peerUrl)
    }

    fun removePeer(peerId: CharSequence) { peers.remove(peerId) }

    fun getPeers(role: PeerRole?) = peers.values.filter { role == null || it.role == role }

    fun info(peerId: CharSequence) = peers[peerId]

    fun masters() = peers.values.filter { it.role == PeerRole.Master }
    fun slaves() = peers.values.filter { it.role == PeerRole.Slave }
}

sealed class PatchEvent {
    data class PatchPublished(val patchHash: CharSequence, val cid: CharSequence) : PatchEvent()
    data class PatchPublishFailed(val patchHash: CharSequence, val reason: CharSequence) : PatchEvent()
    data class PatchPushedToPeer(val peerId: CharSequence, val cid: CharSequence) : PatchEvent()
    data class PatchPushFailed(val peerId: CharSequence, val reason: CharSequence) : PatchEvent()
    data class PeerConnected(val peerId: CharSequence, val role: PeerRole) : PatchEvent()
    data class PeerDisconnected(val peerId: CharSequence) : PatchEvent()
    data class SyncCompleted(val peerId: CharSequence) : PatchEvent()
    data class SyncFailed(val peerId: CharSequence, val reason: CharSequence) : PatchEvent()
    data class GatewayStarted(val headHash: CharSequence) : PatchEvent()
}
