package com.seaofnodes.simple.ccek

import borg.literbike.ccek.core.Context
import borg.literbike.ccek.core.Element
import borg.literbike.ccek.core.Key
import kotlinx.coroutines.*
import kotlinx.channels.*

/**
 * Channelized DAG Construction via SupervisorJob Fan-In/Fan-Out
 *
 * This implements the SeaOfNodes DAG construction using Kotlin coroutines:
 * - Each basic block gets its own child coroutine under a SupervisorJob hierarchy
 * - DAG nodes flow through channels as datalog-like facts
 * - Fan-out: parse blocks concurrently
 * - Fan-in: merge results into unified DAG
 *
 * Unlike the Java reference (sequential recursive descent), this pipeline
 * uses CCEK-injected context to carry state through coroutine boundaries.
 */

// ── Datalog-like fact types ───────────────────────────────────

/**
 * A fact in the datalog-like channelized DAG delivery.
 * Each fact represents a relational assertion about the graph.
 */
sealed class DagFact {
    /** Node definition: (nid, label, type, inputs, controls) */
    data class NodeDef(
        val nid: Int,
        val label: String,
        val typeName: String,
        val inputIds: List<Int>,
        val controlIds: List<Int>
    ) : DagFact()

    /** Edge assertion: node `from` connects to node `to` */
    data class Edge(val from: Int, val to: Int, val edgeType: EdgeKind) : DagFact()
    enum class EdgeKind { DATA, CONTROL, PHI }

    /** SSA assignment: variable `name` is defined by node `nid` in block `bid` */
    data class SSAFact(val name: String, val nid: Int, val blockId: Int) : DagFact()

    /** Block boundary: block `bid` starts at source position `pos` */
    data class BlockBoundary(val blockId: Int, val startPos: Int, val endPos: Int) : DagFact()
}

// ── SupervisorJob hierarchy for block-level concurrency ───────

/**
 * Concurrent DAG builder using SupervisorJob fan-out/fan-in.
 *
 * Architecture:
 * ```
 * rootJob (SupervisorJob)
 *   ├── block_0_job ──produce→ channel_0 ──\
 *   ├── block_1_job ──produce→ channel_1 ──┤→ merge → DAG
 *   ├── block_2_job ──produce→ channel_2 ──┤
 *   └── ...                                 /
 * ```
 *
 * Each block coroutine:
 * 1. Reads its source range from the CCEK Context
 * 2. Produces DagFact assertions to its channel
 * 3. Awaits child block coroutines (for nested control flow)
 * 4. Reports completion
 *
 * The merge coroutine:
 * 1. Collects all facts from all channels
 * 2. Resolves node IDs to actual DagNode instances
 * 3. Returns the unified DAG via the output channel
 */
class ConcurrentDagBuilder(
    private val source: String,
    private val scope: CoroutineScope,
    private val context: Context = Context.empty()
) {
    private val factChannel = Channel<DagFact>(capacity = Channel.UNLIMITED)
    private val dagChannel = Channel<List<DagNode>>(capacity = 1)

    /**
     * Build the DAG concurrently. Returns the complete list of DagNodes.
     */
    suspend fun build(): List<DagNode> {
        val rootJob = SupervisorJob()
        val blockScope = CoroutineScope(scope.coroutineContext + rootJob)

        // Fan-out: launch block coroutines
        val blocks = identifyBlocks(source)
        val blockJobs = blocks.map { (blockId, start, end) ->
            blockScope.launch {
                processBlock(blockId, start, end)
            }
        }

        // Merge: collect all facts into DAG nodes
        val mergeJob = blockScope.launch {
            val facts = mutableListOf<DagFact>()
            // Collect until all block jobs complete
            blockScope.launch {
                blockJobs.forEach { it.join() }
                factChannel.close()
            }
            for (fact in factChannel) {
                facts.add(fact)
            }
            val dagNodes = resolveFacts(facts)
            dagChannel.send(dagNodes)
        }

        rootJob.join()
        mergeJob.join()
        return dagChannel.receive()
    }

    /**
     * Process a single basic block, producing DagFacts to the shared channel.
     * Each block reads from the CCEK Context for phase state.
     */
    private suspend fun processBlock(blockId: Int, startPos: Int, endPos: Int) {
        val blockContext = context.plus(
            BlockKey,
            BlockElement(BlockKey, blockId, startPos, endPos)
        )

        // Emit block boundary fact
        factChannel.send(DagFact.BlockBoundary(blockId, startPos, endPos))

        // TODO: Parse tokens in range [startPos, endPos) and emit NodeDef/Edge/SSA facts
        // This is where the Parser.kt logic gets decomposed into per-block coroutines
    }

    /**
     * Resolve datalog facts into concrete DagNode instances.
     * Deduplicates, validates edges, and assigns final node IDs.
     */
    private fun resolveFacts(facts: List<DagFact>): List<DagNode> {
        val nodeDefs = facts.filterIsInstance<DagFact.NodeDef>()
        return nodeDefs.map { def ->
            DagNode(
                nid = def.nid,
                label = def.label,
                inputs = def.inputIds,
                controls = def.controlIds,
                typeName = def.typeName
            )
        }
    }

    /**
     * Identify basic blocks in the source.
     * Blocks are delimited by control flow: { }, if/else, loops, function bodies.
     */
    private fun identifyBlocks(source: String): List<Triple<Int, Int, Int>> {
        // Simple block identification: function body = one block for now
        // TODO: Parse control flow to identify block boundaries
        return listOf(Triple(0, 0, source.length))
    }
}

// ── Block-level CCEK state ────────────────────────────────────

object BlockKey : Key<BlockElement> {
    override val elementClass = BlockElement::class
    override fun factory() = BlockElement(this, 0, 0, 0)
}

data class BlockElement(
    override val keyType: Key<*> = BlockKey,
    val blockId: Int,
    val startPos: Int,
    val endPos: Int
) : Element

// ── Pipeline orchestration ────────────────────────────────────

/**
 * Full compiler pipeline: source → lexer → parser → DAG → idealize → schedule → codegen.
 * Each phase is a coroutine that reads/writes via CCEK Context.
 */
class CcekCompilerPipeline(
    private val scope: CoroutineScope
) {
    suspend fun compile(source: String): Context {
        var ctx = Context.empty()

        // Phase 1: Lex
        ctx = ctx.flow<LexerElement> { existing ->
            LexerElement(LexerKey, tokenize(source))
        }

        // Phase 2: Parse → DAG (concurrent)
        val dagNodes = ConcurrentDagBuilder(source, scope, ctx).build()
        ctx = ctx.plus(ParserKey, ParserElement(ParserKey, dagNodes))

        // Phase 3+: TODO - idealize, schedule, codegen
        // Each phase follows the same pattern: read from Context, transform, write back

        return ctx
    }

    private fun tokenize(source: String): List<Token> {
        // TODO: Port lexer logic from Parser.kt
        return emptyList()
    }
}
