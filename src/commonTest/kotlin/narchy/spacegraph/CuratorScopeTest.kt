package narchy.spacegraph

import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.DrawItem
import kotlin.test.*

class CuratorScopeTest {
    @Test fun trailViewsRetainOrderAcrossIntakeRunSelectionAndReplay() {
        val scope = CuratorScope()
        fun drain(nodes: List<Int>, runs: List<Int>, kinds: List<Int>, reset: Boolean = false) {
            scope.drain(mapOf(
                "now" to 1000, "epoch" to 1, "reset" to reset, "nodesFrom" to 0,
                "nodeKeys" to List(4) { "node/$it" }, "nodeTypes" to List(4) { "scope" },
                "nodeParents" to List(4) { -1 }, "runs" to listOf("first", "second"),
                "t" to nodes.map { 1000 }, "kind" to kinds, "run" to runs,
                "node" to nodes, "note" to nodes.map { null },
            ), 1000.0)
        }
        drain(listOf(0, 0, 2, 1, 3, -1, 2), listOf(0, 0, 1, 0, 0, 0, 0),
            listOf(CuratorScope.BEGIN, CuratorScope.END, CuratorScope.END, CuratorScope.RING,
                CuratorScope.SKIP, CuratorScope.END, CuratorScope.END))
        fun trail(n: Int) = scope.trail(n).view.toList()
        assertEquals(listOf(0, 1, 2), trail(10))
        val retained = scope.trail(10)
        assertEquals(listOf(1, 2), trail(2))
        assertEquals(emptyList(), trail(0))
        scope.followRun = 1
        assertEquals(listOf(2), trail(10))
        scope.followRun = 2
        assertEquals(emptyList(), trail(10))
        scope.followRun = 0
        drain(listOf(3), listOf(0), listOf(CuratorScope.END))
        assertEquals(listOf(0, 1, 2, 3), trail(10))
        assertEquals(listOf(0, 1, 2), retained.view.toList(), "an issued view must not be rebound to later intake")
        drain(List(8) { 1 }, List(8) { 0 }, List(8) { CuratorScope.END }, reset = true)
        assertEquals(listOf(1), trail(10), "same head/run after reset must not reuse the previous ring")
        assertFailsWith<IllegalArgumentException> { scope.trail(-1) }
    }

    @Test fun wrappedHistoryRetainsExactlyTheRingForTrailStoryAndSpikes() {
        val scope = CuratorScope(Viewport(1000, 700))
        val capacity = 1 shl 16
        val count = capacity * 2 + 7
        scope.drain(mapOf(
            "now" to count, "nodesFrom" to 0, "nodeKeys" to listOf("node/0", "node/1", "node/2"),
            "nodeTypes" to List(3) { "scope" }, "nodeParents" to List(3) { -1 },
            "runs" to listOf("first", "second"), "t" to List(count) { count },
            "kind" to List(count) { CuratorScope.END }, "run" to List(count) { it % 2 },
            "node" to List(count) { it % 3 }, "note" to List(count) { it.toString() },
        ), count.toDouble())
        scope.followRun = 0
        val retained = ((count - capacity) until count).filter { it % 2 == 0 }
        for (size in listOf(1, 3, 300, capacity + 1))
            assertEquals(retained.takeLast(size).map { it % 3 }, scope.trail(size).view.toList())
        scope.selected = 1
        val story = scope.panel()!!["story"] as List<*>
        assertEquals(((count - capacity) until count).filter { it % 3 == 1 }.takeLast(8).asReversed().map { it.toString() },
            story.map { it.toString().substringAfterLast(" — ") })
        val hud = scope.frame(count.toDouble()).items.view.filterIsInstance<DrawItem.Text>().single { it.entityId == "hud" }
        assertTrue(hud.text.contains("${capacity}/s"), "the sparkline counts only retained ring slots")
    }

    @Test fun relationVisibilityFollowsTheCurrentFrameAfterPanGrowthAndReplay() {
        val scope = CuratorScope(Viewport(1000, 700))
        val library = mapOf(
            "now" to 1000, "epoch" to 1, "head" to 1, "nodesFrom" to 0,
            "nodeKeys" to listOf("book.curate", "book.curate/test", "book.curate/test/a", "book.curate/test/a/0", "book.curate/test/b", "book.curate/test/b/0"),
            "nodeTypes" to listOf("program", "scope", "book.section", "book.statement", "book.section", "book.statement"),
            "nodeParents" to listOf(-1, 0, 1, 2, 1, 4),
            "runs" to listOf("curate"), "t" to listOf(1000), "kind" to listOf(CuratorScope.LINK),
            "run" to listOf(0), "node" to listOf(3), "note" to listOf("5|subject"),
        )
        scope.drain(library, 1000.0)
        scope.snapPercent = 0.0
        scope.pin()
        val queued = scope.frame(-5_000.0).items.view.filterIsInstance<DrawItem.Path>()
            .first { it.entityId.startsWith("lw-rsubject-") }
        assertEquals(.58, assertNotNull(queued.stroke).alpha, 1e-12,
            "a frame queued before intake cannot amplify a fresh relation past its initial opacity")
        fun relation() = scope.frame(20_000.0).items.view.any {
            it is DrawItem.Path && it.entityId.startsWith("lw-rsubject-")
        }
        assertTrue(relation(), "the expired relation is drawn because both endpoints are on screen")
        scope.pan(1_000_000.0, 1_000_000.0)
        assertFalse(relation(), "previous frame membership must not keep offscreen strands visible")
        scope.pan(-1_000_000.0, -1_000_000.0)
        assertTrue(relation())
        scope.drain(mapOf("nodesFrom" to 6, "nodeKeys" to listOf("another-run"), "nodeTypes" to listOf("program"), "nodeParents" to listOf(-1)), 20_000.0)
        assertTrue(relation(), "growing the ordinal index retains the existing visible relation")
        scope.drain(library + mapOf("reset" to true, "epoch" to 2), 1000.0)
        assertTrue(relation(), "replay rebuilds membership from the new frame")
    }

    private fun feed(scope: CuratorScope, notes: List<String?>, types: List<String>? = null,
                     reset: Boolean = false, kind: Int = CuratorScope.END) {
        scope.drain(buildMap {
            put("now", 1000)
            put("epoch", 1)
            put("reset", reset)
            put("head", scope.cursor + notes.size)
            if (types != null) {
                put("nodesFrom", 0)
                put("nodeKeys", types.indices.map { "node/$it" })
                put("nodeTypes", types)
                put("nodeParents", types.map { -1 })
            }
            put("runs", listOf("curate"))
            put("t", notes.map { 1000 })
            put("kind", notes.map { kind })
            put("run", notes.map { 0 })
            put("node", notes.indices.toList())
            put("note", notes)
        }, 1000.0)
    }

    @Test fun categoriesRetainIndependentMasks() {
        val scope = CuratorScope()
        val notes = CuratorScope.FORCES.map { "subject · $it · predicate · object" } +
            listOf(null, "unstructured", "subject · unknown · predicate", null, null, null)
        feed(scope, notes, List(7) { "book.statement" } + listOf("concept", "book.section", "scope"))
        for (mask in 0..255) {
            scope.hidden = mask
            for (i in notes.indices) {
                val bit = when (i) { in 0..3 -> i; 7 -> CuratorScope.CONCEPTS; 8 -> CuratorScope.SECTIONS; else -> -1 }
                assertEquals(bit < 0 || mask and (1 shl bit) == 0, scope.visible(i), "mask=$mask node=$i")
            }
        }
    }

    @Test fun noteReplacementNullAndLinksKeepCurrentForce() {
        val scope = CuratorScope()
        feed(scope, listOf("subject · must · act"), listOf("book.statement"))
        scope.hidden = 1
        assertFalse(scope.visible(0))
        scope.selected = 0
        val mustColor = scope.panel()!!["glyphColor"]
        feed(scope, listOf(null))
        assertFalse(scope.visible(0))
        feed(scope, listOf("0|may act"), kind = CuratorScope.LINK)
        assertFalse(scope.visible(0))
        feed(scope, listOf("subject · may · act"))
        assertTrue(scope.visible(0))
        assertNotEquals(mustColor, scope.panel()!!["glyphColor"])
        scope.hidden = 1 shl 2
        assertFalse(scope.visible(0))
        feed(scope, listOf("subject · unknown · act"))
        assertTrue(scope.visible(0))
    }

    @Test fun replayDoesNotReuseThePreviousOrdinalForce() {
        val scope = CuratorScope()
        feed(scope, listOf("subject · must · act"), listOf("book.statement"))
        scope.hidden = 1
        feed(scope, listOf(null), listOf("book.statement"), reset = true)
        assertTrue(scope.visible(0))
        feed(scope, listOf("subject · may · act"))
        scope.hidden = 1 shl 2
        assertFalse(scope.visible(0))
    }

    @Test fun panelRetainsUnchangedHistoryAndInvalidatesOnIntake() {
        val scope = CuratorScope()
        feed(scope, listOf("subject · must · act", "object"), listOf("book.statement", "concept"))
        scope.selected = 0
        val first = scope.panel()!!
        assertSame(first, scope.panel())
        feed(scope, emptyList())
        assertSame(first, scope.panel(), "idle polling must not rescan history")
        scope.drain(mapOf("runs" to listOf("renamed")), 1100.0)
        val renamed = scope.panel()!!
        assertNotSame(first, renamed)
        assertTrue((renamed["story"] as List<*>).first().toString().startsWith("renamed:"))
        feed(scope, listOf("1|subject"), kind = CuratorScope.LINK)
        val linked = scope.panel()!!
        assertEquals(1, linked["degree"])
        assertEquals(listOf("→ subject → 1"), linked["relations"])
        scope.hovered = 1
        assertEquals("node/1", scope.panel()!!["key"])
        scope.hovered = -1
        assertEquals("node/0", scope.panel()!!["key"])
        feed(scope, listOf("replacement"), listOf("book.section"), reset = true)
        scope.selected = 0
        val replayed = scope.panel()!!
        assertEquals("book.section", replayed["type"])
        assertEquals(0, replayed["degree"])
        assertTrue((replayed["story"] as List<*>).single().toString().endsWith("replacement"))
    }

    @Test fun panelFollowingRetainsHeadUntilAnotherNodeProduces() {
        val scope = CuratorScope()
        feed(scope, listOf("one", "two"), listOf("scope", "scope"))
        val followed = scope.panel()!!
        assertEquals("node/1", followed["key"])
        assertSame(followed, scope.panel())
        scope.selected = 0
        assertEquals("node/0", scope.panel()!!["key"])
        scope.selected = -1
        assertEquals("node/1", scope.panel()!!["key"])
        scope.followRun = 1
        assertNull(scope.panel(), "a different followed run must not reuse the previous head")
        scope.followRun = -1
        assertEquals("node/1", scope.panel()!!["key"])
        feed(scope, listOf("new"))
        assertEquals("node/0", scope.panel()!!["key"])
    }
}
