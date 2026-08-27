package borg.trikeshed.lcnc

import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the LCNC mating flow: from compatibility filtering through node/
 * wire creation and Confix round-trip persistence. Covers the same code path
 * that panels.html's `showMateMenu()` → `POST /api/panels/<name>/mate` and
 * PatchWire's `GET /api/lcnc/mating-options` execute.
 */
class LcncMatingTest {

    // ── compatibility filtering ──────────────────────────────────────────

    @Test
    fun compatibleTypesMatchesByKindAcrossContracts() {
        // timer.tick (kind=trigger) → http.get accepts trigger? (kind=trigger)
        val timer = LcncNode("n1", "timer")
        val program = LcncProgram("t", listOf(timer).toSeries(), emptySeriesOf())
        val candidates = LcncMating.compatibleTypes(program, "n1", "tick")
        assertTrue(candidates.any { it.type == "http.get" && it.inputPort == "trigger?" },
            "timer tick should mate with http.get trigger?: $candidates")
        assertTrue(candidates.any { it.type == "graal.vitals" && it.inputPort == "trigger?" },
            "timer tick should mate with graal.vitals trigger?: $candidates")
    }

    @Test
    fun compatibleTypesFindsJsonSinks() {
        // list.groupBy.groups (kind=json) → pick, display, dom.board.groups, etc.
        val gby = LcncNode("n1", "list.groupBy")
        val program = LcncProgram("t", listOf(gby).toSeries(), emptySeriesOf())
        val candidates = LcncMating.compatibleTypes(program, "n1", "groups")
        assertTrue(candidates.any { it.type == "pick" && it.inputPort == "x" },
            "groupBy groups should mate with pick.x: $candidates")
        assertTrue(candidates.any { it.type == "dom.board" && it.inputPort == "groups" },
            "groupBy groups should mate with dom.board.groups: $candidates")
        assertTrue(candidates.any { it.type == "display" && it.inputPort == "x" },
            "groupBy groups should mate with display.x: $candidates")
    }

    @Test
    fun compatibleTypesReturnsEmptyForUnknownSourceType() {
        val ghost = LcncNode("n1", "nonexistent.type")
        val program = LcncProgram("t", listOf(ghost).toSeries(), emptySeriesOf())
        assertTrue(LcncMating.compatibleTypes(program, "n1", "anything").isEmpty(),
            "unknown source type should yield zero candidates")
    }

    @Test
    fun compatibleTypesReturnsEmptyForUnknownSourcePort() {
        val timer = LcncNode("n1", "timer")
        val program = LcncProgram("t", listOf(timer).toSeries(), emptySeriesOf())
        assertTrue(LcncMating.compatibleTypes(program, "n1", "nonexistent").isEmpty(),
            "unknown source port should yield zero candidates")
    }

    @Test
    fun compatibleTypesReturnsEmptyWhenKindMismatch() {
        // timer.tick is trigger, display.x expects json → no match
        val timer = LcncNode("n1", "timer")
        val program = LcncProgram("t", listOf(timer).toSeries(), emptySeriesOf())
        val candidates = LcncMating.compatibleTypes(program, "n1", "tick")
        assertTrue(candidates.none { it.type == "display" },
            "trigger-kind tick must not mate with display (json-kind): $candidates")
    }

    // ── mate operation ───────────────────────────────────────────────────

    @Test
    fun mateCreatesNewNodeAndWireWithCorrectPositions() {
        val timer = LcncNode("n1", "timer", x = 30.0, y = 60.0)
        val program = LcncProgram("test", listOf(timer).toSeries(), emptySeriesOf())
        val mated = LcncMating.mate(program, "n1", "tick", "http.get", 300.0, 60.0)

        // New node created with target type at drop position
        val newNode = (0 until mated.program.nodes.size).map { mated.program.nodes[it] }
            .firstOrNull { it.type == "http.get" }
        assertTrue(newNode != null, "mate must create http.get node")
        assertEquals(300.0, newNode!!.x, "new node x = drop x")
        assertEquals(60.0, newNode.y, "new node y = drop y")

        // Wire connects source output to target input
        assertEquals("n1", mated.wire.fromNode)
        assertEquals("tick", mated.wire.fromPort)
        assertEquals(newNode.id, mated.wire.toNode)
        assertEquals("trigger?", mated.wire.toPort)
    }

    @Test
    fun mateAddsMatingPointToControls() {
        val timer = LcncNode("n1", "timer")
        val program = LcncProgram("test", listOf(timer).toSeries(), emptySeriesOf())
        val mated = LcncMating.mate(program, "n1", "tick", "http.get", 300.0, 60.0)

        val points = mated.program.controls.matingPoints
        assertEquals(1, points.size, "one mating point added")
        // Last node should be http.get (the newly created target)
        val lastNode = (0 until mated.program.nodes.size).map { mated.program.nodes[it] }
            .last()
        assertEquals("http.get", lastNode.type,
            "mating point references the new target node")
        assertTrue(points[0].id.contains("n1") && points[0].id.contains("tick"),
            "mating point id encodes source: ${points[0].id}")
    }

    @Test
    fun mateRejectsUnknownSourcePort() {
        val timer = LcncNode("n1", "timer")
        val program = LcncProgram("test", listOf(timer).toSeries(), emptySeriesOf())
        assertFailsWith<IllegalArgumentException> {
            LcncMating.mate(program, "n1", "nonexistent", "http.get", 300.0, 60.0)
        }
    }

    @Test
    fun mateRejectsIncompatibleTargetType() {
        // timer.tick (trigger) cannot mate with display (expects json, not trigger)
        val timer = LcncNode("n1", "timer")
        val program = LcncProgram("test", listOf(timer).toSeries(), emptySeriesOf())
        assertFailsWith<IllegalStateException> {
            LcncMating.mate(program, "n1", "tick", "display", 300.0, 60.0)
        }
    }

    @Test
    fun mateRejectsDuplicateInputConnection() {
        // Build a program where http.get already receives a wire on its trigger? input.
        // n1 (timer) → n2 (http.get) on trigger?. Then try to mate n3 → http.get.trigger?
        // (different source, same target type+port). The target-side check must block it.
        val timer1 = LcncNode("n1", "timer")
        val timer2 = LcncNode("n3", "timer")
        val httpGet = LcncNode("n2", "http.get")
        // Wire n1.tick → n2.trigger? (http.get already has trigger? occupied)
        val wire = LcncWire("n1", "tick", "n2", "trigger?")
        val program = LcncProgram("test", listOf(timer1, httpGet, timer2).toSeries(), listOf(wire).toSeries())

        assertFailsWith<IllegalArgumentException> {
            // n3.tick is unique (source-side passes), but http.get.trigger? is already
            // occupied (target-side catches it). One wire per ONE-cardinality input.
            LcncMating.mate(program, "n3", "tick", "http.get", 300.0, 60.0)
        }
    }

    @Test
    fun mateRejectsDoubleWiringSourceOutput() {
        // http.get.json has ONE cardinality — can only fan out to one wire.
        // Set up: http.get already wired to pick.x, then try to wire http.get.json to display.x.
        val httpGet = LcncNode("n1", "http.get")
        val pick = LcncNode("n2", "pick")
        val wire = LcncWire("n1", "json", "n2", "x")
        val program = LcncProgram("test", listOf(httpGet, pick).toSeries(), listOf(wire).toSeries())

        assertFailsWith<IllegalArgumentException> {
            // n1.json already has ONE wire (ONE cardinality output) — second wire rejected
            LcncMating.mate(program, "n1", "json", "display", 400.0, 300.0)
        }
    }

    @Test
    fun mateSequentialCallsDontCollideNodeIds() {
        // timer.tick has MANY cardinality — can fan out to multiple targets.
        val timer = LcncNode("n1", "timer")
        val program = LcncProgram("test", listOf(timer).toSeries(), emptySeriesOf())
        val m1 = LcncMating.mate(program, "n1", "tick", "http.get", 200.0, 60.0)
        val m2 = LcncMating.mate(m1.program, "n1", "tick", "graal.vitals", 400.0, 60.0)

        // Both new nodes exist and have different IDs
        val types = (0 until m2.program.nodes.size).map { m2.program.nodes[it].type }
        assertTrue(types.count { it == "http.get" } == 1)
        assertTrue(types.count { it == "graal.vitals" } == 1)
        assertEquals(2, m2.program.wires.size, "two wires created")
    }

    // ── round-trip through Confix serialization ──────────────────────────

    @Test
    fun matedProgramSurvivesConfixRoundTrip() {
        val timer = LcncNode("n1", "timer")
        val program = LcncProgram("roundtrip", listOf(timer).toSeries(), emptySeriesOf())
        val mated = LcncMating.mate(program, "n1", "tick", "http.get", 300.0, 60.0)

        val json = LcncProgramConfix.toJson(mated.program)
        val reparsed = LcncProgramConfix.fromJson("roundtrip", json)

        assertEquals(mated.program.nodes.size, reparsed.nodes.size, "node count preserved")
        assertEquals(mated.program.wires.size, reparsed.wires.size, "wire count preserved")
        assertEquals(mated.program.controls.matingPoints.size,
            reparsed.controls.matingPoints.size, "mating points preserved")

        // Wire round-trip
        val rw = reparsed.wires[0]
        assertEquals("n1", rw.fromNode)
        assertEquals("tick", rw.fromPort)
        assertEquals(mated.wire.toNode, rw.toNode)
        assertEquals("trigger?", rw.toPort)

        // Node positions round-trip
        val rn = (0 until reparsed.nodes.size).map { reparsed.nodes[it] }
            .firstOrNull { it.type == "http.get" }
        assertTrue(rn != null)
        assertEquals(300.0, rn!!.x)
        assertEquals(60.0, rn.y)
    }

    @Test
    fun emptyProgramRoundTrips() {
        val json = LcncProgramConfix.toJson(LcncProgram.EMPTY)
        val reparsed = LcncProgramConfix.fromJson("", json)
        assertEquals(0, reparsed.nodes.size)
        assertEquals(0, reparsed.wires.size)
    }

    // ── positions ────────────────────────────────────────────────────────

    @Test
    fun mateUsesProvidedDropCoordinates() {
        val timer = LcncNode("n1", "timer")
        val program = LcncProgram("pos", listOf(timer).toSeries(), emptySeriesOf())
        val mated = LcncMating.mate(program, "n1", "tick", "http.get", 1234.5, 678.9)
        val newNode = (0 until mated.program.nodes.size).map { mated.program.nodes[it] }
            .first { it.type == "http.get" }
        assertEquals(1234.5, newNode.x)
        assertEquals(678.9, newNode.y)
    }
}
