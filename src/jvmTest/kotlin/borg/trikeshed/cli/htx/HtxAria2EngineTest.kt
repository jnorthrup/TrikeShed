package borg.trikeshed.cli.htx

import borg.trikeshed.cas.IpfsBridge
import borg.trikeshed.htx.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.transfer.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.*
import java.security.MessageDigest
import kotlin.test.*

class HtxAria2EngineTest {
    /** A mirror in memory behind the HTX client reactor: each range is answered 206; a range from [held] on waits for [gate]. */
    class Mirror(val file: ByteArray, val held: Long, val gate: CompletableDeferred<Unit>) : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            val range = requireNotNull(request.range) { "the mirror answers ranges only" }
            if (range.startInclusive >= held) gate.await()
            val body = file.copyOfRange(range.startInclusive.toInt(), range.endInclusive.toInt() + 1)
            return state.copy(response = HtxResponse(206, ByteSeries(body),
                htxHeaders("Content-Range" j "bytes ${range.startInclusive}-${range.endInclusive}/${file.size}"))) j emptySeriesOf()
        }
    }

    /** A file in four ranges from a mirror whose last range waits: the row mid-flight, then complete, then the file in the IPFS store. */
    @Test fun rowWhileItRuns(): Unit = runBlocking {
        val file = ByteArray(4 * 65536) { (it % 253).toByte() }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(file).joinToString("") { "%02x".format(it) }
        val gate = CompletableDeferred<Unit>()
        val client = HtxClientReactorElement(Mirror(file, 3L * 65536, gate))
        val url = "http://mirror.invalid/file.bin"
        try {
            // Four connections, so the three ranges before the held one never wait behind it for a permit.
            val engine = HtxAria2Engine(Aria2Options(uris = _l[url], split = 4, maxConnectionPerServer = 4, minSplitSize = 65536, checksum = "sha-1=$sha1"),
                client, JvmFileOperations())
            assertEquals(TransferState.WAITING, engine.row().state)
            val fetching = async(Dispatchers.Default) { engine.fetch() }
            withTimeout(30_000) { while (engine.row().pieces?.have != 3) delay(10) }
            val mid = engine.row()
            assertEquals(TransferState.ACTIVE, mid.state)
            assertEquals(262144L, mid.total); assertEquals(196608L, mid.completed)
            assertEquals(TransferPieces(4, 3, 65536), mid.pieces)
            assertEquals(_l["$url HTTP TCP 196608"], mid.sources.view.map { "${it.address} ${it.protocol} ${it.transport} ${it.completed}" })
            gate.complete(Unit)
            assertContentEquals(file, withTimeout(30_000) { fetching.await() })
            val end = engine.row()
            assertEquals(TransferState.COMPLETE, end.state)
            assertEquals(262144L, end.completed); assertEquals(TransferPieces(4, 4, 65536), end.pieces)
            // The same file again, into the IPFS store: one 256 KiB chunk, so its root is its raw leaf, as Kubo 0.43.1 named these bytes.
            val store = IpfsBridge(CasStore.inMemory())
            val again = HtxAria2Engine(Aria2Options(uris = _l[url], split = 4, minSplitSize = 65536, checksum = "sha-1=$sha1"), client, JvmFileOperations())
            val root = withTimeout(30_000) { again.fetch(store) }
            assertEquals("bafkreifct3jcic6l7wijfcjleo5yc5hfdlavnsyay543ju3gle7laehwiu", root.toString())
            assertContentEquals(file, store.getFile(root))
            assertEquals(root.toString(), again.row().content)
        } finally { gate.complete(Unit); client.close() }
    }
}
