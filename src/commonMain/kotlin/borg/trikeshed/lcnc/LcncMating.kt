package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.toList

/**
 * Kotlin-owned mating operation for the panels surface. Pointer geometry and
 * the selected type are merely ingress; all compatibility, cardinality,
 * function, add, and replace validation happens here before a new immutable
 * program value is returned for Confix persistence.
 */
data class LcncMatingCandidate(
    val type: String,
    val inputPort: String,
    val title: String,
)

data class LcncMatedProgram(
    val program: LcncProgram,
    val wire: LcncWire,
    val matingPoint: LcncPatchMatingPoint,
)

object LcncMating {
    fun compatibleTypes(program: LcncProgram, sourceNode: String, sourcePort: String): List<LcncMatingCandidate> {
        val source = node(program, sourceNode)
        val sourceContract = LcncContracts.find(source.type) ?: return emptyList()
        val sourceKind = sourceContract.outputKinds[sourcePort.removeSuffix("?")] ?: return emptyList()
        return LcncContracts.all().flatMap { target ->
            target.inputs.mapNotNull { input ->
                val clean = input.removeSuffix("?")
                val inputKind = target.inputKinds[clean] ?: return@mapNotNull null
                if (inputKind != sourceKind) return@mapNotNull null
                LcncMatingCandidate(target.type, input, target.title)
            }
        }.distinctBy { it.type to it.inputPort }
    }

    fun mate(
        program: LcncProgram,
        sourceNode: String,
        sourcePort: String,
        targetType: String,
        targetX: Double,
        targetY: Double,
    ): LcncMatedProgram {
        val source = node(program, sourceNode)
        require(sourcePort.removeSuffix("?") in (LcncContracts.find(source.type)?.outputs ?: emptyList())) {
            "unknown source output: ${source.type}.$sourcePort"
        }
        val candidate = compatibleTypes(program, sourceNode, sourcePort)
            .firstOrNull { it.type == targetType }
            ?: error("incompatible mated type: $targetType for ${source.type}.$sourcePort")
        val id = freshNodeId(program)
        val targetNode = LcncNode(id, candidate.type, x = targetX, y = targetY)
        val wire = LcncWire(sourceNode, sourcePort, id, candidate.inputPort)
        // Duplicate guard: a ONE-cardinality output can only fan out to ONE wire,
        // and a ONE-cardinality input can only receive ONE wire. Check BOTH sides
        // using the existing wire graph (not the fresh `id` which is always new).
        val srcCard = LcncContracts.find(source.type)?.cardinality?.get(sourcePort.removeSuffix("?")) ?: LcncCardinality.ONE
        if (srcCard == LcncCardinality.ONE) {
            require(program.wires.toList().none { it.fromNode == sourceNode && it.fromPort == sourcePort }) {
                "source output already wired: ${source.type}.$sourcePort (ONE cardinality)"
            }
        }
        val tgtCard = LcncContracts.find(targetType)?.cardinality?.get(candidate.inputPort.removeSuffix("?")) ?: LcncCardinality.ONE
        if (tgtCard == LcncCardinality.ONE) {
            val existingType = mutableMapOf<String, String>()
            require(program.wires.toList().none {
                it.toPort == candidate.inputPort && run {
                    val t = existingType.getOrPut(it.toNode) {
                        runCatching { node(program, it.toNode).type }.getOrDefault("")
                    }
                    t == targetType
                }
            }) {
                "target input already connected: ${candidate.type}.${candidate.inputPort} (ONE cardinality)"
            }
        }
        val point = LcncPatchMatingPoint(
            id = "mate:$sourceNode:$sourcePort:$id:${candidate.inputPort}",
            fromNode = sourceNode,
            fromPort = sourcePort,
            toNode = id,
            toPort = candidate.inputPort,
            cardinality = LcncContracts.find(source.type)?.cardinality?.get(sourcePort.removeSuffix("?")) ?: LcncCardinality.ONE,
            function = LcncContracts.find(candidate.type)?.functions?.get(candidate.inputPort.removeSuffix("?"))?.firstOrNull() ?: "identity",
        ).validate()
        val controls = program.controls.addMatingPoint(point)
        val nodes = program.nodes.toList().plus(targetNode).toSeries()
        val wires = program.wires.toList().plus(wire).toSeries()
        // W2.4: seq must clear the fresh id — "n3" ⇒ seq ≥ 4 — or the browser's
        // next addNode("n"+G.seq++) would collide with the mated node.
        val freshNum = id.removePrefix("n").toIntOrNull() ?: 0
        val nextSeq = maxOf(program.seq, freshNum + 1)
        return LcncMatedProgram(
            program.copy(nodes = nodes, wires = wires, controls = controls, seq = nextSeq),
            wire, point,
        )
    }

    private fun node(program: LcncProgram, id: String): LcncNode =
        program.nodes.toList().firstOrNull { it.id == id }
            ?: error("unknown source node: $id")

    private fun freshNodeId(program: LcncProgram): String {
        var n = program.nodes.toList().size + 1
        while (program.nodes.toList().any { it.id == "n$n" }) n++
        return "n$n"
    }
}
