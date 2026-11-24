package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.transfer.*
import kotlin.test.*

class UnixFsFileTest {
    /** The bytes Kubo was given: (i * 7 + i / 1000) mod 256 at offset i. */
    fun content(size: Int) = ByteArray(size) { ((it * 7) + (it / 1000)).toByte() }

    /**
     * Root CIDs written by hand from Kubo 0.43.1, `ipfs add --offline --cid-version=1 -Q`, over the same bytes: one
     * chunk (its raw leaf), three chunks under one node, and 175 chunks under two levels of nodes.
     */
    @Test fun sameRootsAsKubo() {
        assertEquals("bafkreiej6t7vnis52hnqnjgomazwan3v24c7xfxtb6djg4z755qcuhffgi", UnixFsFile.blocks(content(1000)).last().cid.toString())
        assertEquals("bafybeidh42iuxnkp36ctqtqy2zyvqxc7neubdyj4i76iuiybugfjsmrshe", UnixFsFile.blocks(content(600000)).last().cid.toString())
        assertEquals("bafybeieurkti35apgebuk3yinrbaqy5g57k6vwomcnkncixpi25sk73y7m", UnixFsFile.blocks(content(174 * 262144 + 1000)).last().cid.toString())
    }

    /** A file put through the bridge is read back by its root's content id, and is a row of the IPFS side. */
    @Test fun putReadBackAndRow() {
        val bridge = IpfsBridge(CasStore.inMemory())
        val bytes = content(600000)
        val root = bridge.putFile(bytes, "k600000.bin", TransferContext("document", "k600000.bin"))
        assertEquals("bafybeidh42iuxnkp36ctqtqy2zyvqxc7neubdyj4i76iuiybugfjsmrshe", root.toString())
        assertContentEquals(bytes, bridge.getFile(root))
        val wire: Map<String, Any?> = _m["id" j "bafybeidh42iuxnkp36ctqtqy2zyvqxc7neubdyj4i76iuiybugfjsmrshe", "name" j "k600000.bin",
            "protocol" j "ipfs", "state" j "complete", "total" j 600000L, "completed" j 600000L, "down" j 0L, "up" j 0L,
            "pieces" j _m["count" j 3, "have" j 3, "length" j 262144], "sources" j emptyList<Map<String, Any?>>(),
            "context" j _m["kind" j "document", "id" j "k600000.bin"],
            "content" j "bafybeidh42iuxnkp36ctqtqy2zyvqxc7neubdyj4i76iuiybugfjsmrshe", "class" j null, "error" j null]
        assertEquals(_l[wire], bridge.transfers().view.map { it.wire })
    }
}
