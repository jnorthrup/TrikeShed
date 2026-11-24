package borg.trikeshed.ontology

import borg.trikeshed.lib.*
import narchy.spacegraph.*
import narchy.spacegraph.graphics.spi.Rgba
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

/**
 * Homing and hyperarchy navigation on the frozen SUMO image the daemon serves (`sumo/frozen.bin`, in the staged
 * `sumo-frozen` jar). Expected classes are read from the KIF text: `(subclass Downloading Transfer)` and
 * `(subclass Downloading ITProcess)` (Facebook.kif 509-510), `(subclass ITProcess IntentionalProcess)` (engineering.kif
 * 1530), `(subclass IntentionalProcess Process)`, `(subclass Process Physical)`, `(subclass Physical Entity)`,
 * `(subclass Transfer Translocation)`, `(subclass Translocation Motion)`, `(subclass Motion Process)` (Merge.kif), and
 * DataTransfer's children `NetworkCommunication` (ComputerInput.kif 2685), `ComputerBackingUp` and
 * `SoftwareInstallation` (ComputingBrands.kif 2791, 2891).
 */
class SumoHyperarchyTest {
    fun real(): SumoHyperarchy {
        val h = SumoFrozen.hyperarchy
        assumeTrue(h != null, "the frozen SUMO image (${SumoFrozen.RESOURCE}) is not on the test classpath")
        return h!!
    }

    fun SumoHyperarchy.at(name: String): Int = index(name).also { assertTrue(it >= 0, "$name is a class of the image") }

    // ── homing (b) ──

    @Test
    fun homeIsTheSameWhateverElseIsLoadedAndInWhateverOrder() {
        val h = real()
        val probe = s_["Downloading", "Uploading", "NetworkCommunication", "DataTransfer", "ComputerNetwork", "Book", "Entity"]
        // The same hyperarchy given its classes and edges in another order: every class keeps its home.
        val n = h.size
        val renamed = IntArray(n) { (it * 7919L % n).toInt() }.let { perm -> if (perm.toSet().size == n) perm else IntArray(n) { n - 1 - it } }
        val names = Array(n) { "" }; for (c in 0 until n) names[renamed[c]] = h.names[c]
        val pairs = (0 until h.edges.size / 2).reversed().flatMap { e -> _l[renamed[h.edges[2 * e]], renamed[h.edges[2 * e + 1]]] }.toIntArray()
        val shuffled = SumoHyperarchy(names, pairs)
        for (c in 0 until n) assertEquals(h.home(c), shuffled.home(shuffled.index(h.names[c])), h.names[c])
        // Things loaded on the ground, few or many and in either order, move no home; each stands in its class's room.
        val scene = GroundScene(Viewport(1280, 800)); scene.load(h)
        val before = probe α { h.home(h.at(it)) }
        val one = s_[Stand("a", s_["Book"], s_["a"], 1.0, .5, Rgba.WHITE)]
        val many = s_["Text", "Book", "Downloading", "Book", "ComputerNetwork"].let { cs -> cs.size j { i: Int -> Stand("t$i", s_[cs[i]], s_["t$i"], 1.0, .5, Rgba.WHITE) } }
        for (loaded in s_[one, many, many.size j { i: Int -> many[many.size - 1 - i] }].view) {
            scene.stands = loaded
            scene.frame()
            assertEquals(before.view.toList(), (probe α { h.home(h.at(it)) }).view.toList())
            for (s in loaded.view) assertTrue(h.room(h.at(s.path[0])).contains(assertNotNull(scene.at(s.id))), "${s.id} stands in the room of ${s.path[0]}")
        }
        // The homes the report prints.
        for (name in probe.view) println("home $name = ${h.home(h.at(name))}, room ${h.room(h.at(name))}")
    }

    @Test
    fun childHomeLiesInsideParents() {
        val h = real()
        val downloading = h.at("Downloading")
        // ITProcess is four steps from Entity, Transfer five: Downloading's canonical parent is ITProcess.
        assertEquals("ITProcess", h.names[h.tree[downloading]])
        assertEquals(5, h.depth[downloading])
        assertTrue(h.home(h.at("ITProcess")).contains(h.home(downloading)))
        assertTrue(h.home(h.at("DataTransfer")).contains(h.home(h.at("NetworkCommunication"))))
        for (c in 0 until h.size) {
            val p = h.tree[c]
            if (p >= 0) assertTrue(h.home(p).contains(h.home(c)), "${h.names[c]} inside ${h.names[p]}")
            assertTrue(h.home(c).contains(h.room(c)), "${h.names[c]}'s room inside its home")
        }
        for (r in h.roots) assertTrue(h.ground.contains(h.home(r)))
        assertTrue(h.ground.contains(h.untyped))
        // An induced class takes a home inside the room of the class it is placed under.
        assertTrue(h.room(h.at("NetworkCommunication")).contains(h.home(s_["NetworkCommunication", "bittorrent"])))
        assertTrue(h.home(s_["NetworkCommunication", "bittorrent"]).contains(h.home(s_["NetworkCommunication", "bittorrent", "tcp"])))
    }

    @Test
    fun differentClassesHaveDifferentHomes() {
        val h = real()
        assertEquals(h.size, (0 until h.size).map { h.home(it) }.toSet().size)
        assertEquals(h.size, (0 until h.size).map { h.room(it) }.toSet().size)
        assertNotEquals(h.home(h.at("Downloading")), h.home(h.at("Uploading")))
        val protocols = s_["bittorrent", "ipfs", "http"] α { h.home(s_["NetworkCommunication", it]) }
        assertEquals(3, protocols.view.toSet().size)
    }

    @Test
    fun thingWithNoClassIsAtTheUntypedHome() {
        val h = real()
        assertEquals(h.untyped, h.home(emptySeriesOf<String>()))
        val scene = GroundScene(Viewport(1280, 800)); scene.load(h)
        scene.stands = s_[Stand("none", emptySeriesOf(), s_["none"], 1.0, null, Rgba.WHITE), Stand("typed", s_["Book"], s_["typed"], 1.0, null, Rgba.WHITE)]
        assertTrue(h.untyped.contains(assertNotNull(scene.at("none"))))
        assertFalse(h.untyped.contains(assertNotNull(scene.at("typed"))))
        for (c in 0 until h.size) assertFalse(h.room(c).contains(h.untyped.x, h.untyped.y), "the untyped room is no class's room")
    }

    // ── navigation (c) ──

    @Test
    fun upFromDownloadingOffersBothParents() {
        val h = real(); val n = HyperarchyNavigation(h)
        n.goto(h.at("Downloading"))
        assertEquals(_l["Transfer", "ITProcess"], n.parents().map { h.names[it] })
        assertTrue(n.up(h.at("Transfer")))
        assertEquals("Transfer", h.names[n.current])
        assertTrue(n.back())
        assertTrue(n.up(h.at("ITProcess")))
        assertEquals(_l["Entity", "Physical", "Process", "IntentionalProcess", "ITProcess"], n.names)
        assertFalse(n.up(h.at("DataTransfer")))
    }

    @Test
    fun downFromDataTransferReachesNetworkCommunication() {
        val h = real(); val n = HyperarchyNavigation(h)
        n.goto(h.at("DataTransfer"))
        assertEquals(_l["ComputerBackingUp", "NetworkCommunication", "SoftwareInstallation"], n.children().map { h.names[it] })
        assertTrue(n.down(h.at("NetworkCommunication")))
        assertEquals(_l["DataTransfer", "NetworkCommunication"], n.names.takeLast(2))
        assertFalse(n.down(h.at("Book")))
    }

    @Test
    fun siblingsStepInAFixedOrder() {
        val h = real(); val n = HyperarchyNavigation(h)
        n.goto(h.at("Downloading"))
        // reached by ITProcess: its children in name order
        assertEquals(_l["Downloading", "SoftwareInstallation", "SoftwareUnInstallation", "Uploading"], n.siblings().map { h.names[it] })
        val seen = ArrayList<String>()
        repeat(4) { assertTrue(n.sibling(1)); seen.add(h.names[n.current]) }
        assertEquals(_l["SoftwareInstallation", "SoftwareUnInstallation", "Uploading", "Downloading"], seen)
        assertTrue(n.sibling(-1)); assertEquals("Uploading", h.names[n.current])
        // reached by DataTransfer, NetworkCommunication steps among DataTransfer's children
        n.goto(h.at("DataTransfer")); n.down(h.at("NetworkCommunication"))
        assertTrue(n.sibling(1)); assertEquals("SoftwareInstallation", h.names[n.current])
    }

    @Test
    fun backRetraces() {
        val h = real(); val n = HyperarchyNavigation(h)
        val visited = ArrayList<List<Int>>()
        visited.add(n.path)
        n.goto(h.at("DataTransfer")); visited.add(n.path)
        n.down(h.at("NetworkCommunication")); visited.add(n.path)
        n.sibling(1); visited.add(n.path)
        n.up(h.at("ITProcess")); visited.add(n.path)
        n.down(h.at("Downloading")); visited.add(n.path)
        n.up(h.at("Transfer")); visited.add(n.path)
        for (k in visited.size - 2 downTo 0) { assertTrue(n.back()); assertEquals(visited[k], n.path) }
        assertFalse(n.back())
    }

    @Test
    fun pathReadsBackFromTheUrl() {
        val h = real(); val n = HyperarchyNavigation(h)
        n.goto(h.at("DataTransfer")); n.down(h.at("NetworkCommunication"))
        val hash = GroundView(GroundCamera(1.0, 2.0, 3.0, 30.0, 45.0), n.names).encode()
        val view = assertNotNull(GroundView.decode(hash))
        val read = HyperarchyNavigation(h)
        assertTrue(read.restore(view.path))
        assertEquals(n.path, read.path)
        assertEquals(_l["Entity", "Physical", "Process", "InternalChange", "ComputerProcess", "DataTransfer", "NetworkCommunication"], read.names)
        assertFalse(read.restore(_l["Entity", "Physical", "Book"]))
        assertEquals(n.path, read.path)
    }
}
