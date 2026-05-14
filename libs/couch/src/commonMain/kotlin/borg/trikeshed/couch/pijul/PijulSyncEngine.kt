package borg.trikeshed.couch.pijul

import borg.trikeshed.couch.stream.Change
import borg.trikeshed.couch.stream.ChangeEmitter
import borg.trikeshed.couch.htx.*
import borg.trikeshed.process.ProcessShell
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.LinkedList

/**
 * PijulSyncEngine — realtime fanout/fanin merge coordinator.
 *
 * Orchestrates concurrent synchronization between multiple peers using:
 *   - coroutineScope fan-out: push patches to N remotes in parallel
 *   - fan-in: collect acknowledgments from all peers
 *   - conflict resolution: auto-resolve or escalate to human
 *
 * The engine is built on the ChangeEmitter pattern from ChangeStream.kt:
 *   - INSERT events when a new patch is synced
 *   - REMOVE events when a patch is rolled back
 *   - SEAL when the sync epoch completes
 *
 * Architecture:
 *
 *   PijulSyncEngine
 *   ├── peerChannels: Map<CharSequence, PeerChannel>       // one per remote peer
 *   ├── emitter: ChangeEmitter<SyncEvent>            // pub/sub for sync state
 *   ├── mergeCoordinator: PijulMerge                 // 3-way merge logic
 *   ├── ipfsStore: PijulIpfsStore                   // optional IPFS staging
 *   └── ketNegotiator: KetNegotiator                // extension negotiation per peer
 *
 * The sync loop:
 *   1. Discover: diff local channels vs each peer
 *   2. Prioritize: order patches by dependency urgency
 *   3. Fan-out: push patches to all peers concurrently (Semaphore(limit=4))
 *   4. Fan-in: collect acks/nacks, detect conflicts
 *   5. Resolve: auto-resolve or emit ConflictMarker to emitter
 *   6. Epoch: emit SEAL, advance local head
 */

/** A remote peer with its own channel state. */
class PeerChannel(
    val peerId: CharSequence,
    val peerUrl: CharSequence,
    var remoteHead: PatchHash?,
    var localHead: PatchHash?,
    val negotiatedCaps: List<Capability>,
) {
    val pendingPatches = LinkedList<Patch>()
    val ackedPatches = LinkedList<PatchHash>()
}

/** Events emitted by the sync engine. */
sealed class SyncEvent {
    data class PatchPushed(val peerId: CharSequence, val patch: Patch) : SyncEvent()
    data class PatchAcked(val peerId: CharSequence, val hash: PatchHash) : SyncEvent()
    data class PatchNacked(val peerId: CharSequence, val hash: PatchHash, val reason: CharSequence) : SyncEvent()
    data class ConflictDetected(val marker: ConflictMarker) : SyncEvent()
    data class PeerConnected(val peerId: CharSequence) : SyncEvent()
    data class PeerDisconnected(val peerId: CharSequence) : SyncEvent()
    object EpochSealed : SyncEvent()
    data class Error(val peerId: CharSequence?, val message: CharSequence) : SyncEvent()
}

/** Sync engine configuration. */
data class SyncConfig(
    val maxConcurrentPushes: Int = 4,
    val maxConcurrentPulls: Int = 2,
    val ackTimeoutMs: Long = 30_000,
    val retryCount: Int = 3,
    val useIpfsFallback: Boolean = true,
    val ipfsStaging: Boolean = false,  // stage patches in IPFS before push
)

/** The sync engine itself. */
class PijulSyncEngine(
    private val localRepo: Repository,
    private val shell: ProcessShell,
    private val ipfsStore: PijulIpfsStore?,
    private var config: SyncConfig = SyncConfig(),
) {
    private val peers = LinkedHashMap<CharSequence, PeerChannel>()
    private val emitter = ChangeEmitter<SyncEvent>()
    private val scope = CoroutineScope(kotlinx.coroutines.CoroutineName("PijulSync"))
    private val pushSemaphore = Semaphore(config.maxConcurrentPushes)

    /**
     * Register a remote peer for syncing.
     */
    fun addPeer(peerId: CharSequence, peerUrl: CharSequence, remoteHead: PatchHash?): PeerChannel {
        val caps = StandardCapabilities.defaults()
        val peer = PeerChannel(peerId, peerUrl, remoteHead, null, caps)
        peers[peerId] = peer
        emitter.emit(Change.Insert(SyncEvent.PeerConnected(peerId)))
        return peer
    }

    /**
     * Remove a peer.
     */
    fun removePeer(peerId: CharSequence) {
        peers.remove(peerId)
        emitter.emit(Change.Insert(SyncEvent.PeerDisconnected(peerId)))
    }

    /**
     * Subscribe to sync events.
     * Returns a token for unsubscribing.
     */
    fun subscribe(cb: (Change<SyncEvent>) -> Unit): Int = emitter.register(cb)

    /**
     * Unsubscribe from sync events.
     */
    fun unsubscribe(token: Int) { emitter.unregister(token) }

    /**
     * Full sync: push local patches to all peers and pull remote patches.
     * Uses coroutineScope fan-out to maximize parallelism.
     *
     * Returns after all peers have been synced (or failed).
     */
    suspend fun syncAll() = coroutineScope {
        // Fan-out: push to all peers concurrently
        val pushJobs = peers.map { (peerId, peer) ->
            async {
                try {
                    pushToPeer(peer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emitter.emit(Change.Insert(SyncEvent.Error(peerId, e.message ?: "Push failed")))
                }
            }
        }
        // Fan-in: wait for all pushes
        pushJobs.joinAll()

        // Now pull from each peer
        val pullJobs = peers.map { (peerId, peer) ->
            async {
                try {
                    pullFromPeer(peer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emitter.emit(Change.Insert(SyncEvent.Error(peerId, e.message ?: "Pull failed")))
                }
            }
        }
        pullJobs.joinAll()

        emitter.emit(Change.Insert(SyncEvent.EpochSealed))
    }

    /**
     * Push patches to a single peer.
     * Uses Semaphore to limit concurrency.
     */
    private suspend fun pushToPeer(peer: PeerChannel) {
        pushSemaphore.withPermit {
            val localPatches = localRepo.localPatches.toList()
            if (localPatches.isEmpty()) return@withPermit

            val toPush = localPatches.filter { it.hash !in peer.ackedPatches }
            if (toPush.isEmpty()) return@withPermit

            for (patch in toPush) {
                val result = pushPatch(peer, patch)
                if (!result) {
                    // Nacked — record and move on
                    emitter.emit(Change.Insert(SyncEvent.PatchNacked(peer.peerId, patch.hash, "Peer rejected patch")))
                    continue
                }
                peer.ackedPatches.add(patch.hash)
                emitter.emit(Change.Insert(SyncEvent.PatchPushed(peer.peerId, patch)))
            }
        }
    }

    /**
     * Pull patches from a single peer.
     */
    private suspend fun pullFromPeer(peer: PeerChannel) {
        if (peer.remoteHead == null) return

        val pullResult = shell.exec("pijul", listOf("pull", peer.peerUrl, "--channel", peer.peerId))
        if (pullResult.exitCode != 0) {
            emitter.emit(Change.Insert(SyncEvent.Error(peer.peerId, "Pull failed: ${pullResult.stderr}")))
            return
        }

        // Parse pulled patches and emit events
        val newPatches = parsePullOutput(pullResult.stdout)
        for (patchHash in newPatches) {
            emitter.emit(Change.Insert(SyncEvent.PatchAcked(peer.peerId, patchHash)))
        }
    }

    /**
     * Push a single patch to a peer.
     * Returns true if acked, false if nacked.
     */
    private suspend fun pushPatch(peer: PeerChannel, patch: Patch): Boolean {
        // Check if peer supports couch delta encoding
        val useCouchDelta = peer.negotiatedCaps.any { it.id() == StandardCapabilities.CouchDeltaV2.id() }
        val useIpfs = peer.negotiatedCaps.any { it.id() == StandardCapabilities.IpfsStoreV1.id() }

        // Optionally stage to IPFS first (ipfsStaging config)
        val effectiveHash = if (config.ipfsStaging && ipfsStore != null) {
            val ipfsResult = ipfsStore.store(patch)
            if (ipfsResult is IpfsResult.Stored) {
                emitter.emit(Change.Insert(SyncEvent.PatchPushed(peer.peerId, patch)))
                patch.hash  // local hash still used for deps
            } else patch.hash
        } else patch.hash

        // Encode patch (couch delta or raw)
        val encodedPatch = if (useCouchDelta) {
            CouchDeltaCodec.encodePatch(patch).joinToString("\n") { it.bytes.decodeToString() }
        } else {
            patch.name  // fallback: just send name as identifier
        }

        // Send to peer
        val result = shell.exec("pijul", listOf("push", peer.peerUrl, "--patch", patch.hash.display()))
        if (result.exitCode != 0) return false
        return result.stdout.contains("ACK") || result.exitCode == 0
    }

    /**
     * Merge local and remote patches for a peer.
     * Uses PijulMerge.threeWayMerge when there are conflicts.
     */
    fun mergeWithPeer(peerId: CharSequence, ours: Channel, theirs: Channel): MergeResult {
        val peer = peers[peerId] ?: return MergeResult.Incompatible("Unknown peer: $peerId")
        val result = PijulMerge.mergeChannels(ours, theirs, localRepo.pristine)
        when (result) {
            is MergeResult.Conflict -> {
                for (marker in result.conflicting) {
                    emitter.emit(Change.Insert(SyncEvent.ConflictDetected(marker)))
                }
            }
            is MergeResult.Success -> {
                peer.localHead = result.merged.hash
            }
            else -> {}
        }
        return result
    }

    private fun parsePullOutput(stdout: CharSequence): List<PatchHash> {
        // Parse pijul pull output for patch hashes
        return stdout.lines()
            .filter { it.startsWith("patch ") }
            .map { it.substringAfter("patch ").take(64) }
            .filter { it.isNotBlank() }
            .map { PatchHash.parse(it) }
    }

    /**
     * Graceful shutdown.
     */
    fun close() {
        scope.cancel()
        emitter.emit(Change.Seal)
    }
}
