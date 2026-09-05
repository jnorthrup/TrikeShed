package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LcncShakeDemoTest {
    private fun flatten(nodes: Series<LcncNode>): List<LcncNode> = buildList {
        for (i in 0 until nodes.size) { add(nodes[i]); addAll(flatten(nodes[i].children)) }
    }

    @Test
    fun everyPaletteTypeAndSocketHasAnUninstalledTypedCounterpart() {
        val contracts = LcncContracts.all().associateBy { it.type }
        val specimen = LcncShakeDemo.build()
        val program = specimen.program
        val nodes = flatten(program.nodes)
        val byId = nodes.associateBy { it.id }
        assertEquals(0, program.wires.size)
        assertEquals(nodes.size, byId.size)
        assertEquals(contracts.keys, nodes.filter { it.id == "palette.${it.type}" }.map { it.type }.toSet())
        val endpoints = mutableSetOf<Triple<String, String, String>>()
        for (i in 0 until specimen.counterparts.size) {
            val w = specimen.counterparts[i]
            endpoints += Triple(w.fromNode, "out", w.fromPort.removeSuffix("?"))
            endpoints += Triple(w.toNode, "in", w.toPort.removeSuffix("?"))
        }
        for (node in nodes) {
            for ((dir, ports) in listOf("in" to LcncTypeCheck.inputsOf(node, contracts), "out" to LcncTypeCheck.outputsOf(node, contracts))) {
                for (port in ports) assertTrue(Triple(node.id, dir, port.removeSuffix("?")) in endpoints, "missing counterpart: ${node.id}/$dir/$port")
            }
        }
        val result = LcncTypeCheck.check(program.copy(wires = specimen.counterparts), contracts)
        assertTrue(result.isEmpty(), result.toString())
        val roundTrip = LcncProgramConfix.fromJson(program.name, LcncProgramConfix.toJson(program))
        assertEquals(0, roundTrip.wires.size)
        assertTrue(roundTrip.controls.inspectionOnly)
        assertTrue(nodes.size in 100..1500, "bounded palette stress fixture: ${nodes.size}")
        val shaken = LcncMating.treeshake(roundTrip)
        assertEquals(endpoints.size, shaken.socketCount)
        assertEquals(shaken.socketCount, shaken.connectedSocketCount)
        assertEquals(specimen.counterparts.size, shaken.made.size)
        val installedEndpoints = shaken.made.flatMap { w -> listOf(
            Triple(w.fromNode, "out", w.fromPort.removeSuffix("?")),
            Triple(w.toNode, "in", w.toPort.removeSuffix("?")),
        ) }.toSet()
        assertEquals(endpoints, installedEndpoints)
        assertTrue(shaken.starvedNodeIds.isEmpty())
        assertTrue(LcncMating.treeshake(shaken.program).made.isEmpty())
        println("Shake Demo: ${contracts.size} palette types, ${nodes.size} nodes, ${endpoints.size} covered sockets, 0 installed wires")
    }

    @Test
    fun geometryDoesNotChangeCounterpartCompatibilityOrAuthorizeEffects() {
        val specimen = LcncShakeDemo.build()
        val contracts = LcncContracts.all().associateBy { it.type }
        fun moved(nodes: Series<LcncNode>): Series<LcncNode> = (0 until nodes.size).map { i ->
            nodes[i].copy(x = -nodes[i].x * 13, y = nodes[i].y * 7, children = moved(nodes[i].children))
        }.toSeries()
        assertTrue(LcncTypeCheck.check(specimen.program.copy(nodes = moved(specimen.program.nodes), wires = specimen.counterparts), contracts).isEmpty())
        val shake = LcncTreeShake.shake(specimen.program.copy(controls = specimen.program.controls.copy(inspectionOnly = false)),
            LcncTreeShakeOptions(includeOptional = true), contracts)
        val nodes = flatten(specimen.program.nodes).associateBy { it.id }
        assertFalse(shake.made.any { contracts[nodes.getValue(it.toNode).type]?.isEffect == true })
    }

    @Test
    fun shakeClosesEverySocketAfterScatteringOrCollocatingNodes() {
        val specimen = LcncShakeDemo.build()
        for (seed in listOf(0, 17, 83, 100)) {
            val random = Random(seed)
            var row = 0
            fun move(nodes: Series<LcncNode>): Series<LcncNode> = (0 until nodes.size).map { nodes[it] }.shuffled(random).map { node ->
                node.copy(x = if (seed == 0 || seed == 100) 0.0 else random.nextDouble(-1e6, 1e6),
                    y = when (seed) { 0 -> 0.0; 100 -> row++ * 5000.0; else -> random.nextDouble(-1e6, 1e6) }, children = move(node.children))
            }.toSeries()
            val result = LcncMating.treeshake(specimen.program.copy(nodes = move(specimen.program.nodes)))
            assertEquals(result.socketCount, result.connectedSocketCount, "seed $seed")
            assertEquals(specimen.counterparts.size, result.made.size)
        }
    }

    @Test
    fun shakeCompletesSixteenScopesAndADoubledPalette() {
        val probe = LcncShakeDemo.deepProbe(16)
        val deep = LcncMating.treeshake(probe.program.copy(controls = LcncConfixControls(inspectionOnly = true)))
        assertEquals(deep.socketCount, deep.connectedSocketCount)
        assertEquals(probe.counterparts.size, deep.made.size)
        val selected = LcncMating.treeshake(probe.program.copy(controls = LcncConfixControls(inspectionOnly = true)),
            LcncTreeShakeOptions(parentId = "depth.7.scope"))
        assertEquals(selected.socketCount, selected.connectedSocketCount)
        assertTrue(selected.made.none { it.fromNode == "depth.0.in" || it.toNode == "depth.0.scope" })
        val specimen = LcncShakeDemo.build()
        fun duplicate(nodes: Series<LcncNode>): List<LcncNode> = (0 until nodes.size).map { i ->
            nodes[i].copy(id = "copy." + nodes[i].id, x = -nodes[i].x, children = duplicate(nodes[i].children).toSeries())
        }
        val doubled = specimen.program.copy(nodes = ((0 until specimen.program.nodes.size).map { specimen.program.nodes[it] } + duplicate(specimen.program.nodes)).toSeries())
        val result = LcncMating.treeshake(doubled)
        assertEquals(result.socketCount, result.connectedSocketCount)
        assertEquals(specimen.counterparts.size * 2, result.made.size)
    }

    @Test
    fun augmentingPathRepairsAGreedyConflictAndMissingMatesRemainOpen() {
        val program = LcncProgram("conflict", listOf(
            LcncNode("json", "scope.in", mapOf("name" to "json", "kind" to "json")),
            LcncNode("text", "scope.in", mapOf("name" to "text", "kind" to "text"), x = 1e6),
            LcncNode("any", "display", x = 200.0),
            LcncNode("json-sink", "scope.out", mapOf("name" to "result", "kind" to "json"), x = 300.0),
        ).toSeries(), emptyList<LcncWire>().toSeries(), controls = LcncConfixControls(inspectionOnly = true))
        val result = LcncMating.treeshake(program)
        assertEquals(4, result.connectedSocketCount)
        assertTrue(LcncWire("json", "value", "json-sink", "value") in result.made)
        assertTrue(LcncWire("text", "value", "any", "x") in result.made)
        val missing = LcncMating.treeshake(program.copy(nodes = listOf(program.nodes[3]).toSeries()))
        assertEquals(1, missing.socketCount)
        assertEquals(0, missing.connectedSocketCount)
        assertTrue(missing.made.isEmpty())
    }

    @Test
    fun specimenWorkIsBoundedBeforePairEnumeration() {
        val oversized = LcncProgram("oversized", (0..1500).map { LcncNode("n$it", "note") }.toSeries(),
            emptyList<LcncWire>().toSeries(), controls = LcncConfixControls(inspectionOnly = true))
        assertFailsWith<IllegalArgumentException> { LcncMating.treeshake(oversized) }
        val tooManySockets = oversized.copy(nodes = (0 until 700).map { i ->
            LcncNode("s$i", "scope", children = listOf(
                LcncNode("in$i", "scope.in", mapOf("name" to "value", "kind" to "json")),
            ).toSeries())
        }.toSeries())
        assertFailsWith<IllegalArgumentException> { LcncMating.treeshake(tooManySockets) }
    }

    @Test
    fun deepContextsRoundTripAndReturnTheCallersBinding() = runTest {
        for (depth in listOf(1, 8, 16)) {
            val specimen = LcncShakeDemo.deepProbe(depth)
            val wired = specimen.program.copy(wires = specimen.counterparts)
            val roundTrip = LcncProgramConfix.fromJson(wired.name, LcncProgramConfix.toJson(wired))
            val runner = LcncRunner(mapOf(
                "json.value" to LcncNodeRunner { node, _ -> mapOf("value" to JsonSupport.parse(node.params.getValue("value"))) },
                "gauge" to LcncNodeRunner { _, _ -> emptyMap() },
            )).apply { maxScopeDepth = 20 }
            assertEquals(123, runner.runProcedure(roundTrip, mapOf("value" to 123)).returns["result"])
            assertEquals(456, runner.runProcedure(roundTrip, mapOf("value" to 456)).returns["result"])
        }
    }

    @Test
    fun deepArgumentMapShadowsItsCallerButExplicitNamedInputWins() = runTest {
        val specimen = LcncShakeDemo.deepProbe(16)
        fun overrideAtEight(nodes: Series<LcncNode>): Series<LcncNode> = (0 until nodes.size).map { i ->
            val node = nodes[i]
            node.copy(
                params = if (node.id == "depth.8.args") mapOf("value" to "{\"value\":999}") else node.params,
                children = overrideAtEight(node.children),
            )
        }.toSeries()
        val explicit = specimen.program.copy(nodes = overrideAtEight(specimen.program.nodes), wires = specimen.counterparts)
        val inherited = explicit.copy(wires = (0 until explicit.wires.size).map { explicit.wires[it] }
            .filterNot { it.toNode == "depth.8.scope" && it.toPort == "value" }.toSeries())
        val runner = LcncRunner(mapOf(
            "json.value" to LcncNodeRunner { node, _ -> mapOf("value" to JsonSupport.parse(node.params.getValue("value"))) },
            "gauge" to LcncNodeRunner { _, _ -> emptyMap() },
        )).apply { maxScopeDepth = 20 }
        assertEquals(999.0, runner.runProcedure(inherited, mapOf("value" to 123)).returns["result"])
        assertEquals(123, runner.runProcedure(explicit, mapOf("value" to 123)).returns["result"])
    }

    @Test
    fun deepSelectedParentShakeKeepsOuterContextAndExistingWiring() {
        val specimen = LcncShakeDemo.deepProbe(16)
        val nodes = flatten(specimen.program.nodes)
        val selected = nodes.single { it.id == "depth.7.scope" }
        val selectedIds = flatten(selected.children).map { it.id }.toSet()
        val existing = LcncWire("depth.0.args", "value", "depth.0.scope", "args?")
        val program = specimen.program.copy(wires = listOf(existing).toSeries())
        val result = LcncTreeShake.shake(program, LcncTreeShakeOptions(
            reach = 1500.0, includeOptional = true, parentId = selected.id,
        ))
        assertEquals(selected.id, result.parentId)
        assertEquals(nodes, flatten(result.program.nodes))
        assertEquals(existing, result.program.wires[0])
        assertTrue(result.made.isNotEmpty())
        assertTrue(result.made.all { it.fromNode in selectedIds && it.toNode in selectedIds })
        assertTrue(result.verdicts.all { it.nodeId in selectedIds })
    }

    @Test
    fun wiringSpecimensNeverDispatchEvenAsNamedSubprograms() = runTest {
        val specimen = LcncShakeDemo.build().program
        var dispatched = 0
        val registry = LcncContracts.all().associate { it.type to LcncNodeRunner { _, _ -> dispatched++; emptyMap() } }
        val runner = LcncRunner(registry).apply { subprogramLoader = { specimen } }
        assertFailsWith<IllegalArgumentException> { runner.runProcedure(specimen) }
        val caller = LcncProgram("caller", listOf(LcncNode("ref", "scope", subprogram = specimen.name)).toSeries(), emptyList<LcncWire>().toSeries())
        assertFailsWith<IllegalArgumentException> { runner.runProcedure(caller) }
        assertEquals(0, dispatched)
    }
}
