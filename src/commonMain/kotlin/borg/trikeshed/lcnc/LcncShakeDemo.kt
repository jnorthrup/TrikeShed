package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.math.ceil
import kotlin.math.sqrt

/** The complete node palette, unlinked. Counterparts are typed frame bindings/yields,
 * not new runners or invented compatibility rules. [counterparts] is a test oracle,
 * never installed cables or execution authority. */
object LcncShakeDemo {
    const val NAME = "preset-shake"
    const val DEPTH = 8

    data class Specimen(val program: LcncProgram, val counterparts: Series<LcncWire>)

    fun build(contracts: List<LcncPortContract> = LcncContracts.all()): Specimen {
        val vocabulary = contracts.associateBy { it.type }
        require(vocabulary.size == contracts.size) { "duplicate palette type" }
        val facts = LcncFacts.of(contracts)
        val nodes = mutableListOf<LcncNode>()
        val pairs = mutableListOf<LcncWire>()
        val columns = ceil(sqrt(contracts.size.toDouble())).toInt().coerceAtLeast(1)
        var row = 0.0
        var rowHeight = 0.0
        for ((column, contract) in contracts.sortedBy { it.type }.withIndex()) {
            if (column > 0 && column % columns == 0) { row += rowHeight + 100.0; rowHeight = 0.0 }
            val left = (column % columns) * 1800.0
            val id = "palette.${contract.type}"
            val params = contract.params.mapValues { it.value.v }.toMutableMap()
            if (contract.type == LcncContracts.SCOPE_IN || contract.type == LcncContracts.SCOPE_OUT) {
                params["name"] = id
                params["kind"] = "json"
            }
            val subject = LcncNode(id, contract.type, params, x = left + 380.0, y = row)
            val inputs = LcncTypeCheck.inputsOf(subject, vocabulary)
            val outputs = LcncTypeCheck.outputsOf(subject, vocabulary)
            fun kind(dir: String, port: String): String {
                val resolved = LcncTypeCheck.portKind(subject, dir, port, vocabulary, facts)
                require(resolved.kind != null || resolved.generic) { "untyped palette socket: $id/$dir/$port" }
                return resolved.kind ?: "json"
            }
            for ((index, port) in inputs.withIndex()) {
                val source = "$id.in.$index"
                nodes += LcncNode(source, LcncContracts.SCOPE_IN,
                    mapOf("name" to source, "kind" to kind("in", port)), x = left + 40.0, y = row + index * 290.0)
                pairs += LcncWire(source, "value", id, port)
            }
            nodes += subject
            for ((index, port) in outputs.withIndex()) {
                val sink = "$id.out.$index"
                nodes += LcncNode(sink, LcncContracts.SCOPE_OUT,
                    mapOf("name" to sink, "kind" to kind("out", port)), x = left + 1100.0, y = row + index * 250.0)
                pairs += LcncWire(id, port, sink, "value")
            }
            rowHeight = maxOf(rowHeight, 1000.0, inputs.size * 290.0, outputs.size * 250.0)
        }
        val probe = deepProbe()
        for (i in 0 until probe.program.nodes.size) nodes += probe.program.nodes[i].copy(x = probe.program.nodes[i].x + columns * 1800.0)
        for (i in 0 until probe.counterparts.size) pairs += probe.counterparts[i]
        return Specimen(LcncProgram(NAME, nodes.toSeries(), emptySeriesOf(),
            controls = LcncConfixControls(inspectionOnly = true)), pairs.toSeries())
    }

    /** Same binding name at each depth: nearest explicit binding shadows the
     * caller; returns cross each boundary through a declared yield. */
    fun deepProbe(depth: Int = DEPTH): Specimen {
        require(depth in 1..16) { "demo depth must be between 1 and 16" }
        val pairs = mutableListOf<LcncWire>()
        fun body(level: Int): Series<LcncNode> {
            val prefix = "depth.$level"
            val input = LcncNode("$prefix.in", LcncContracts.SCOPE_IN,
                mapOf("name" to "value", "kind" to "json"), x = 40.0, y = 40.0)
            val output = LcncNode("$prefix.out", LcncContracts.SCOPE_OUT,
                mapOf("name" to "result", "kind" to "json"), x = 1000.0, y = 40.0)
            if (level == depth) {
                pairs += LcncWire(input.id, "value", output.id, "value")
                return listOf(input, output).toSeries()
            }
            val child = LcncNode("$prefix.scope", LcncContracts.SCOPE, x = 360.0, y = 40.0, children = body(level + 1))
            val args = LcncNode("$prefix.args", "json.value",
                mapOf("value" to "{}"), x = 40.0, y = 330.0)
            val guard = LcncNode("$prefix.guard", "json.value",
                mapOf("value" to "true"), x = 40.0, y = 620.0)
            val mapOut = LcncNode("$prefix.map", "gauge", x = 1000.0, y = 300.0)
            pairs += LcncWire(input.id, "value", child.id, "value")
            pairs += LcncWire(args.id, "value", child.id, "args?")
            pairs += LcncWire(guard.id, "value", child.id, "when?")
            pairs += LcncWire(child.id, "result", output.id, "value")
            pairs += LcncWire(child.id, "returns", mapOut.id, "x")
            return listOf(input, args, guard, child, output, mapOut).toSeries()
        }
        return Specimen(LcncProgram("shake-depth", body(0), emptySeriesOf()), pairs.toSeries())
    }
}
