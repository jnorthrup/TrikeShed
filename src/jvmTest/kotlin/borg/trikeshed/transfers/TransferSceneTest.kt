package borg.trikeshed.transfers

import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoFrozen
import borg.trikeshed.ontology.SumoHyperarchy
import borg.trikeshed.parse.reify
import narchy.spacegraph.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.hypot
import kotlin.test.*

/**
 * The transfers scene from rows in their wire form, on the frozen SUMO image's ground: the rollup, where each transfer,
 * source and protocol territory stands, and the link for each context kind. Every expected value is worked by hand
 * from [ROWS].
 */
class TransferSceneTest {
    companion object {
        /** Four rows written by hand: bytes and rates small enough to add up by eye. */
        const val ROWS = """[
          {"id": "a", "name": "commentaries.txt", "protocol": "bittorrent", "state": "active", "total": 1000, "completed": 250, "down": 40, "up": 5,
           "pieces": {"count": 4, "have": 1, "length": 256},
           "sources": [{"address": "203.0.113.7:51413", "protocol": "bittorrent", "transport": "tcp", "state": "active", "down": 30, "up": 5, "completed": 200},
                       {"address": "198.51.100.23:6881", "protocol": "bittorrent", "transport": "utp", "state": "active", "down": 10, "up": 0, "completed": 50}],
           "context": {"kind": "book", "id": "commentaries"}, "content": null, "class": "Book", "error": null},
          {"id": "b", "name": "plate.png", "protocol": "http", "state": "complete", "total": 500, "completed": 500, "down": 0, "up": 0, "pieces": null,
           "sources": [{"address": "https://mirror.example.org/plate.png", "protocol": "http", "transport": "tls", "state": "complete", "down": 0, "up": 0, "completed": 500}],
           "context": {"kind": "run", "id": "run-1"}, "content": "bafkreisample", "class": "ImageFile", "error": null},
          {"id": "c", "name": "meta.jsonl", "protocol": "ipfs", "state": "waiting", "total": null, "completed": 0, "down": 0, "up": 0, "pieces": null, "sources": [],
           "context": {"kind": "node", "id": "[\"ingest.stages\", \"fetch\"]"}, "content": null, "class": null, "error": null},
          {"id": "d", "name": "vol2.pdf", "protocol": "http", "state": "error", "total": 2000, "completed": 100, "down": 0, "up": 0, "pieces": null,
           "sources": [{"address": "https://mirror.example.net/vol2.pdf", "protocol": "http", "transport": "tcp", "state": "error", "down": 0, "up": 0, "completed": 100}],
           "context": {"kind": "document", "id": "projects/law/notes.md"}, "content": null, "class": "Book", "error": "HTTP 404"}
        ]"""
    }

    val rows: Series<TransferRow> = (reify(ROWS) as List<*>).map { TransferRow.of(it as Map<*, *>) }.toSeries()

    @Test
    fun rollupAddsUpTheRows() {
        val r = TransferRollup.of(rows)
        // completed 250 + 500 + 0 + 100; totals known for a, b, d: 1000 + 500 + 2000; c's is unknown
        assertEquals(4, r.transfers); assertEquals(850.0, r.completed); assertEquals(3500.0, r.total); assertEquals(1, r.untotalled)
        assertEquals(40.0, r.down); assertEquals(5.0, r.up)
        assertEquals(1, r.waiting); assertEquals(1, r.active); assertEquals(0, r.paused); assertEquals(1, r.complete); assertEquals(1, r.failed)
        assertEquals(_l["Transfers · 4", "850B done of 3.4K (+1 of unknown size)", "↓ 40B/s ↑ 5B/s", "waiting 1 · active 1 · paused 0 · complete 1 · failed 1"],
            TransferGround.figure(rows).view.toList())
        assertEquals(_l["Transfers · none", "no transport reports a transfer"], TransferGround.figure(emptySeriesOf()).view.toList())
        val territories = TransferGround.territories(rows)
        assertEquals(_l["protocol/bittorrent", "protocol/bittorrent/tcp", "protocol/bittorrent/utp", "protocol/http", "protocol/http/tcp", "protocol/http/tls", "protocol/ipfs"],
            territories.view.map { it.id })
        assertEquals(_l["bittorrent", "1 transfer · 250B of 1000B (25.0%)", "↓ 40B/s ↑ 5B/s"], territories[0].label.view.toList())
        assertEquals(_l["http over tcp", "1 source", "↓ 0B/s ↑ 0B/s"], territories[4].label.view.toList())
        // progress: a column filled to the verified share; a complete transfer full; an unknown total, no share
        assertEquals(_l[.25, 1.0, null, .05], rows.view.map { TransferGround.share(it) })
        assertEquals(_l[.25, 1.0, null, .05], TransferGround.stands(rows).view.map { it.share })
        assertEquals(_l[4, 0, 0, 0], TransferGround.stands(rows).view.map { it.marks })
    }

    @Test
    fun transfersStandAtTheirClassHomesWithTheirSourcesBeside() {
        val h: SumoHyperarchy? = SumoFrozen.hyperarchy
        assumeTrue(h != null, "the frozen SUMO image (${SumoFrozen.RESOURCE}) is not on the test classpath")
        h!!
        val scene = GroundScene(Viewport(1280, 800)); scene.load(h)
        TransferGround.show(scene, rows)
        val book = h.room(h.index("Book"))
        val a = assertNotNull(scene.at("a")); val d = assertNotNull(scene.at("d"))
        assertTrue(book.contains(a), "a stands in Book's room"); assertTrue(book.contains(d), "d stands in Book's room")
        assertTrue(hypot(a.x - d.x, a.y - d.y) >= a.r + d.r, "two transfers in one room stand apart")
        assertTrue(h.room(h.index("ImageFile")).contains(assertNotNull(scene.at("b"))))
        assertTrue(h.untyped.contains(assertNotNull(scene.at("c"))), "c has no class: the untyped room")
        for (address in s_["203.0.113.7:51413", "198.51.100.23:6881"].view) {
            val s = assertNotNull(scene.at("a/$address"))
            assertTrue(hypot(s.x - a.x, s.y - a.y) + s.r <= a.r, "$address beside its transfer")
        }
        val network = h.room(h.index(TransferGround.NETWORK))
        val bittorrent = assertNotNull(scene.at("protocol/bittorrent"))
        assertTrue(network.contains(bittorrent))
        assertTrue(bittorrent.contains(assertNotNull(scene.at("protocol/bittorrent/tcp"))))
        assertEquals(_l["a" j "protocol/bittorrent", "b" j "protocol/http", "c" j "protocol/ipfs", "d" j "protocol/http"].map { it.pair },
            TransferGround.links(rows).view.map { it.pair })
        // The frame draws: the figure's first line among its texts.
        scene.fit()
        val texts = scene.frame().items.view.filterIsInstance<narchy.spacegraph.graphics.spi.DrawItem.Text>().map { it.text }
        assertTrue("Transfers · 4" in texts, texts.take(12).toString())
    }

    @Test
    fun aLinkForEachContextKind() {
        val byKind = rows.view.associate { it.context!!.kind to TransferLinks.context(it.context!!) }
        assertEquals(TransferLinks.KINDS.view.toSet(), byKind.keys)
        assertEquals("/curator", byKind["book"])
        assertEquals("/panels", byKind["run"])
        assertEquals("/harness#x=0.0&y=0.0&z=1.0&focus=node%3A%5B%22ingest.stages%22%2C+%22fetch%22%5D", byKind["node"])
        // the node's context id is the pair the board writes for the node it focuses
        assertEquals("node:" + rows[2].context!!.id, borg.trikeshed.landscape.LandscapeNavigation.node("ingest.stages", "fetch"))
        assertEquals("/documents#projects/law%2Fnotes.md", byKind["document"])
        assertEquals("/transfers?transfer=a", TransferLinks.transfer("a"))
        assertEquals("/transfers?transfer=a+b%2Fc", TransferLinks.transfer("a b/c"))
        assertEquals(_l["a"], TransferLinks.matching(rows, "book", "commentaries").view.map { it.id })
        assertEquals(_l["d"], TransferLinks.matching(rows, "document", "projects/law/notes.md").view.map { it.id })
        assertNull(TransferLinks.context(TransferContext("document", "no-project-part")))
    }
}
