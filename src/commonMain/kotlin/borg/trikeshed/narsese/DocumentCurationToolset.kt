package borg.trikeshed.narsese

import borg.trikeshed.lcnc.LcncContracts
import borg.trikeshed.lib.toSeries
import borg.trikeshed.modelmux.ToolOntologyScaffold
import modelmux.acp.MemoryHarnessProfile
import modelmux.acp.toolNames

/**
 * The read-only capability vocabulary given to language curation.
 *
 * LCNC contracts are the declared TrikeShed/Forge surface. ACP memory tools
 * are included because a curation model may need to name a memory operation,
 * while runtime additions (mounted Camel endpoints and routed model cards)
 * are supplied by the host. The entries describe capabilities only: the
 * curator remains proposal-only and never dispatches one of these tools.
 */
object DocumentCurationToolset {
    const val MODEL_BASE = "llm-model-base"

    fun all(available: Iterable<String> = emptyList()): ToolOntologyScaffold {
        val entries = LinkedHashSet<String>()
        entries += "$MODEL_BASE:ModelWorker.invoke(Prompt)->ModelResponse"
        entries += "$MODEL_BASE:ModelWorker.providers()->Series<ProviderDescriptor>"
        entries += "trikeshed:DocumentSource->DocumentCuratorElement"
        entries += "forge:ModuleContext{casStore,blackboard,routes,muxContext,beliefBag,narsRete,kifBank}"

        for (contract in LcncContracts.all()) {
            entries += "lcnc:${contract.type}(${contract.inputs.joinToString(",")})->(${contract.outputs.joinToString(",")})"
        }
        for (name in toolNames(MemoryHarnessProfile.CENTER_PLUS_BM25.writeTools))
            entries += "acp.memory.write:$name"
        for (name in toolNames(MemoryHarnessProfile.CENTER_PLUS_BM25.readTools))
            entries += "acp.memory.read:$name"
        for (entry in available) {
            val value = entry.trim()
            if (value.isNotEmpty()) entries += value
        }
        return entries.toSeries() // Bolt: Use .toSeries() directly to avoid intermediate List allocation
    }
}
