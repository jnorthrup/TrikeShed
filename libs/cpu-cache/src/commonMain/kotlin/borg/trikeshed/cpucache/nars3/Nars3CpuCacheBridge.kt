package borg.trikeshed.cpucache.nars3

import borg.trikeshed.lib.*
import borg.trikeshed.collections.s_
import borg.trikeshed.cpucache.*
import borg.trikeshed.parse.kursive.NarsiveElement
import borg.trikeshed.parse.kursive.NarsiveElementKind
import borg.trikeshed.parse.kursive.nars3.*

/**
 * Bridges CPU Cache Topology with NARS3 Machine Atoms.
 *
 * This allows hardware constraints (cache sizes, core count) to be processed
 * as "knowledge" atoms by the NARS3 machine, enabling hardware-aware reasoning
 * for optimization passes.
 */
object Nars3CpuCacheBridge {

    /**
     * Converts a [CpuCacheTopology] into a set of NARS3 Atoms.
     *
     * Each cache level and property becomes an atom with the corresponding
     * hardware evidence as its knowledge.
     */
    fun topologyToAtoms(topology: CpuCacheTopology): Series<Nars3Atom> {
        val atoms = mutableListOf<Nars3Atom>()
        val defaultBudget = Nars3Budget(0.8f, 0.8f, 1.0f) // Hardware facts are high quality

        topology.l1DataBytes?.let {
            atoms.add(createHardwareAtom("cpu-l1-data", "L1 Data Cache: $it bytes", it, defaultBudget))
        }
        topology.l1InstructionBytes?.let {
            atoms.add(createHardwareAtom("cpu-l1-inst", "L1 Instruction Cache: $it bytes", it, defaultBudget))
        }
        topology.l2Bytes?.let {
            atoms.add(createHardwareAtom("cpu-l2", "L2 Cache: $it bytes", it, defaultBudget))
        }
        topology.l3Bytes?.let {
            atoms.add(createHardwareAtom("cpu-l3", "L3 Cache: $it bytes", it, defaultBudget))
        }
        topology.coreCount?.let {
            atoms.add(createHardwareAtom("cpu-cores", "Core Count: $it", it.toLong(), defaultBudget))
        }

        return atoms.toSeries()
    }

    private fun createHardwareAtom(id: String, label: String, value: Long, budget: Nars3Budget): Nars3Atom {
        val element = NarsiveElement(
            kind = NarsiveElementKind.TERM,
            span = 0 j 0,
            lexeme = label.toSeries()
        )

        return CpuHardwareAtom(
            id = id,
            knowledge = s_[element],
            resources = budget,
            value = value
        )
    }
}

/**
 * A specialized [Nars3Atom] that carries hardware measurement data.
 */
class CpuHardwareAtom(
    id: String,
    knowledge: Series<NarsiveElement>,
    resources: Nars3Budget,
    val value: Long
) : Nars3Atom(id, knowledge, resources) {

    override suspend fun process(input: Channel<Nars3Message>, output: Channel<Nars3Message>) {
        for (msg in input) {
            // Hardware atoms enrich messages with cache constraints
            val enrichedContent = "${msg.content} [Constrained by $id=$value]"
            output.send(msg.copy(content = enrichedContent, budget = resources))
        }
        output.close()
    }
}
