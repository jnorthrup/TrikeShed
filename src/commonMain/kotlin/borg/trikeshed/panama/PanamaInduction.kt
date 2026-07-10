package borg.trikeshed.panama

import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.causalGraphNode
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.cursor.provenance
import kotlinx.datetime.Clock

/**
 * Panama induction — reads and resolves DAG entries from Panama projects.
 *
 * This class interrogates Panama projects (gswormk TS brain, cache-tier Hazelcast,
 * columnar ISAM) to produce causal graph nodes that feed the ReteAgent.
 *
 * For now, this produces synthetic but structurally correct DAG entries.
 * Real project interrogation would require classpath scanning or manifest reading.
 */
object PanamaInduction {

    /**
     * Resolve all DAG entries from Panama projects.
     * Returns a sequence of CausalGraphNodes representing the causal chain.
     */
    fun resolveDagEntries(): Sequence<CausalGraphNode> {
        val clock = Clock.System.now().toEpochMilliseconds()
        val blackboardId = "panama-induction-$clock"
        val blackboard = BlackboardContext(
            id = blackboardId,
            provenance = provenance(
                source = "panama-induction",
                timestamp = clock,
                transformations = listOf("PanamaInduction.resolveDagEntries")
            )
        )

        // Build causal chain: project discovery → classpath scan → symbol extraction → DAG entry
        // Each node has proper parent dependencies forming a causal DAG
        val nodes = listOf(
            // Level 0: Project discovery (root)
            Triple("panama:project-discovery", "ProjectDiscovery", emptyList<String>()),
            // Level 1: Classpath scan depends on project discovery
            Triple("panama:classpath-scan", "ClasspathScan", listOf("panama:project-discovery")),
            // Level 2: Symbol extraction depends on classpath scan (3 parallel branches)
            Triple("panama:symbol-extract-gswormk", "SymbolExtract", listOf("panama:classpath-scan")),
            Triple("panama:symbol-extract-hazelcast", "SymbolExtract", listOf("panama:classpath-scan")),
            Triple("panama:symbol-extract-isam", "SymbolExtract", listOf("panama:classpath-scan")),
            // Level 3: DAG entry builder depends on all symbol extractions
            Triple("panama:dag-entry-builder", "DagEntryBuilder", listOf("panama:symbol-extract-gswormk", "panama:symbol-extract-hazelcast", "panama:symbol-extract-isam")),
            // Level 4: Reactor bridge depends on DAG entry builder
            Triple("panama:reactor-bridge", "ReactorBridge", listOf("panama:dag-entry-builder")),
        )

        return nodes.asSequence().mapIndexed { index, (nodeId, opId, parentNodeIds) ->
            causalGraphNode(
                nodeId = nodeId,
                opId = opId,
                opVersion = "v1",
                parentNodeIds = parentNodeIds,
                inputFingerprint = "panama-fingerprint-$index",
                blackboard = blackboard,
                causalClock = clock + index,
                topoOrdinal = index,
                outputHash = null
            )
        }
    }

    /**
     * Get a count of available DAG entries.
     */
    fun dagEntryCount(): Int = 7
}
