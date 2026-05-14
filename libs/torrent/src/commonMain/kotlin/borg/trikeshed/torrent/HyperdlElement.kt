package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.HyperDownloader
import borg.trikeshed.htx.client.HyperDLSwitches
import borg.trikeshed.htx.client.HtxElement
import kotlinx.coroutines.*

/**
 * Hyperdl — root choreographer for all download subsystems (Pattern A CCEK).
 *
 * Choreography:
 *   HyperdlElement (root SupervisorJob)
 *   ├── HyperDownloader      — HTTP/FTP multi-segment via HTX-TLS
 *   ├── TorrentElement       — BitTorrent swarm supervisor
 *   ├── KademliaDht          — trackerless peer discovery (DHT)
 *   ├── StreamingTorrent     — seekable media client
 *   └── TorrentMediaServer   — HTTP Range server for ffplay/ffmpeg
 *
 * All children share [supervisor] as their parentJob. Cancellation
 * propagates downward; sibling failures don't kill siblings (SupervisorJob).
 */
class HyperdlElement(
    private val htxElement: HtxElement,
    val switches: HyperDLSwitches = HyperDLSwitches(),
    parentJob: Job? = null,
) : AsyncContextElement(parentJob = parentJob) {
    companion object Key : AsyncContextKey<HyperdlElement>()
    override val key: AsyncContextKey<HyperdlElement> get() = Key

    // ── Child elements (choreographed) ────────────────────────────

    private val hyperdl = HyperDownloader(htxElement, switches)
    val dht: KademliaDht = KademliaDht(parentJob = supervisor)

    private var torrentElement: TorrentElement? = null
    private var streaming: StreamingTorrent? = null
    private var mediaServer: TorrentMediaServer? = null
    private var rpcServer: HyperdlRpcServer? = null

    // ── Download state ────────────────────────────────────────────

    private val activeDownloads = LinkedHashMap<CharSequence, DownloadTask>()
    private val gidCounter = AtomicLong(1)

    data class DownloadTask(
        val gid: CharSequence,
        val uri: CharSequence,
        val status: CharSequence = "active",
        val totalLength: Long = 0,
        val completedLength: Long = 0,
        val downloadSpeed: Long = 0,
        val uploadSpeed: Long = 0,
        val files: List<DownloadFile> = emptyList(),
    )

    data class DownloadFile(
        val path: CharSequence,
        val length: Long,
        val completedLength: Long = 0,
        val selected: Boolean = true,
    )

    // ── Lifecycle ─────────────────────────────────────────────────

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        hyperdl.open()
        dht.open()
        state = ElementState.ACTIVE
    }

    /** Open the aria2-compatible RPC server on [port]. */
    suspend fun openRpcServer(port: Int = 6800) {
        requireState(ElementState.ACTIVE)
        val rpc = HyperdlRpcServer(
            hyperdl = hyperdl,
            htx = htxElement,
            parentJob = supervisor,
        )
        rpcServer = rpc
        rpc.open()
        supervisor.launch {
            // RPC server runs until rpcServer.close() or supervisor cancellation
            rpc.serve(port)
        }
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            torrentElement?.close()
            streaming?.close()
            mediaServer?.close()
            rpcServer?.close()
            activeDownloads.clear()
            hyperdl.close()
            dht.close()
            supervisor.cancel()
            super.close()
        }
    }

    // ── Torrent lifecycle ─────────────────────────────────────────

    /** Start a torrent download. Creates and opens TorrentElement + StreamingTorrent. */
    suspend fun startTorrent(torrent: TorrentMetainfo): TorrentElement {
        requireState(ElementState.ACTIVE)
        val te = TorrentElement(torrent, parentJob = supervisor)
        te.open()
        torrentElement = te

        // Also create streaming client if media file detected
        val name = torrent.name.lowercase()
        if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") ||
            name.endsWith(".avi") || name.endsWith(".mov") || name.endsWith(".mp3")
        ) {
            val st = StreamingTorrent(te, parentJob = supervisor)
            st.open()
            streaming = st
            val ms = TorrentMediaServer(st, torrent.name, parentJob = supervisor)
            ms.open()
            mediaServer = ms
        }
        return te
    }

    /** Get the media server (if started). Use for ffplay/ffmpeg integration. */
    fun mediaServer(): TorrentMediaServer? = mediaServer

    // ── HyperDL download API ──────────────────────────────────────

    fun addUri(uri: CharSequence, options: Map<CharSequence, CharSequence> = emptyMap()): CharSequence {
        val gid = gidCounter.getAndIncrement().toString(16)
        val task = DownloadTask(gid = gid, uri = uri, status = "active")
        activeDownloads[gid] = task
        return gid
    }

    fun addTorrent(torrent: TorrentMetainfo, uris: List<CharSequence> = emptyList()): CharSequence {
        val gid = gidCounter.getAndIncrement().toString(16)
        val task = DownloadTask(gid = gid, uri = torrent.announce, status = "active")
        activeDownloads[gid] = task
        return gid
    }

    fun tellStatus(gid: CharSequence): DownloadTask? = activeDownloads[gid]
    fun pause(gid: CharSequence): DownloadTask? =
        activeDownloads[gid]?.copy(status = "paused")?.also { activeDownloads[gid] = it }
    fun unpause(gid: CharSequence): DownloadTask? {
        val task = activeDownloads[gid] ?: return null
        if (task.status == "paused") activeDownloads[gid] = task.copy(status = "active")
        return activeDownloads[gid]
    }
    fun remove(gid: CharSequence): Boolean = activeDownloads.remove(gid) != null
    fun tellActive(): List<DownloadTask> = activeDownloads.values.filter { it.status == "active" }
    fun tellWaiting(offset: Int = 0, num: Int = 100): List<DownloadTask> =
        activeDownloads.values.filter { it.status == "waiting" }.drop(offset).take(num)
    fun tellStopped(offset: Int = 0, num: Int = 100): List<DownloadTask> =
        activeDownloads.values.filter { it.status in listOf("complete", "error") }.drop(offset).take(num)
    fun getGlobalStat(): Map<CharSequence, Long> = mapOf(
        "downloadSpeed" to activeDownloads.values.sumOf { it.downloadSpeed },
        "uploadSpeed" to activeDownloads.values.sumOf { it.uploadSpeed },
        "numActive" to activeDownloads.values.count { it.status == "active" }.toLong(),
        "numWaiting" to activeDownloads.values.count { it.status == "waiting" }.toLong(),
        "numStopped" to activeDownloads.values.count { it.status in listOf("complete", "error", "removed") }.toLong(),
    )
}

internal class AtomicLong(initial: Long) {
    private var value: Long = initial
    fun getAndIncrement(): Long = value++
}
