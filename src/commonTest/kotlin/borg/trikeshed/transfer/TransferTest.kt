package borg.trikeshed.transfer

import borg.trikeshed.lib.*
import kotlin.test.*

class TransferTest {
    /**
     * The archive.org torrent mid-flight, selected down to its djvu.txt: three of the seven pieces it spans held, a
     * peer and a web seed feeding it, the peer also fed by it. The row on the wire is written out by hand.
     */
    @Test fun torrentMidFlight() {
        val row = Transfer("719c7a3130d5cbc4e3749f03d54658914c801777", "dictionaryof_blac_1891_00_20160211",
            TransferProtocol.BITTORRENT, TransferState.ACTIVE, 7340032, 3145728, 524288, 16384, TransferPieces(1274, 3, 1048576),
            s_[TransferSource("31.200.249.237:31852", TransferProtocol.BITTORRENT, TransferTransport.TCP, TransferState.ACTIVE, 262144, 16384, 1048576),
                TransferSource("https://archive.org/download/", TransferProtocol.HTTP, TransferTransport.TLS, TransferState.ACTIVE, 262144, 0, 2097152)],
            TransferContext("book", "dictionaryof_blac_1891_00_20160211"), null, null, null)
        val wire: Map<String, Any?> = _m["id" j "719c7a3130d5cbc4e3749f03d54658914c801777", "name" j "dictionaryof_blac_1891_00_20160211",
            "protocol" j "bittorrent", "state" j "active", "total" j 7340032L, "completed" j 3145728L, "down" j 524288L, "up" j 16384L,
            "pieces" j _m["count" j 1274, "have" j 3, "length" j 1048576],
            "sources" j _l[
                _m["address" j "31.200.249.237:31852", "protocol" j "bittorrent", "transport" j "tcp", "state" j "active",
                    "down" j 262144L, "up" j 16384L, "completed" j 1048576L],
                _m["address" j "https://archive.org/download/", "protocol" j "http", "transport" j "tls", "state" j "active",
                    "down" j 262144L, "up" j 0L, "completed" j 2097152L]],
            "context" j _m["kind" j "book", "id" j "dictionaryof_blac_1891_00_20160211"],
            "content" j null, "class" j null, "error" j null]
        assertEquals(wire, row.wire)
    }
}
