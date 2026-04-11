package com.seaofnodes.simple.ccek

import borg.literbike.ccek.core.Context
import borg.literbike.ccek.core.Element
import borg.literbike.ccek.core.Key
import kotlin.reflect.KClass

/**
 * SeaOfNodes CCEK Integration — Compiler phase state injection.
 *
 * Each compiler phase (Lexer, Parser, GraphBuilder, Idealizer, Scheduler, CodeGen)
 * gets its own Key/Element pair. The Context chain flows through the entire
 * compilation pipeline, with each phase adding its state and reading from
 * previous phases.
 *
 * Architecture:
 * - Fan-out: `SupervisorJob` spawns child coroutines per basic block for concurrent DAG construction
 * - Fan-in: `Channel<DagNode>` collects results from child coroutines
 * - Datalog-like: Each `DagNode` carries relational facts about graph edges, types, and SSA assignments
 */

// ── Lexer Phase ───────────────────────────────────────────────

object LexerKey : Key<LexerElement> {
    override val elementClass = LexerElement::class
    override fun factory() = LexerElement(this, emptyList())
}

data class Token(val kind: TokenKind, val text: String, val pos: Int)
enum class TokenKind {
    IDENT, INT, CHAR, STRING, KEYWORD, OP, DELIM, EOF
}

data class LexerElement(
    override val keyType: Key<*> = LexerKey,
    val tokens: List<Token>
) : Element

// ── Parser / SSA Phase ────────────────────────────────────────

object ParserKey : Key<ParserElement> {
    override val elementClass = ParserElement::class
    override fun factory() = ParserElement(this, emptyList())
}

/**
 * Datalog-like fact about a DAG node.
 * Encodes relational facts: node id, type, inputs, control dependencies.
 */
data class DagNode(
    val nid: Int,
    val label: String,
    val inputs: List<Int>,   // node ids of inputs (data edges)
    val controls: List<Int>, // node ids of control predecessors
    val typeName: String = "?"
) {
    val inputSeries:  lib.Series<Int>
        get() = inputs.size j { i -> inputs[i] }

    val controlSeries:  Series<Int>
        get() = controls.size j { i -> controls[i] }

    override fun toString() = "$label#${nid}(${inputs.joinToString(",")})"
}

/**
 * SSA assignment fact — maps variable names to defining node ids at each program point.
 */
data class SSAAssignment(
    val variable: String,
    val definingNode: Int,
    val blockId: Int
)

data class ParserElement(
    override val keyType: Key<*> = ParserKey,
    val dagNodes: List<DagNode>,
    val ssaAssignments: List<SSAAssignment> = emptyList()
) : Element

// ── Ideal Graph Phase ─────────────────────────────────────────

object IdealKey : Key<IdealElement> {
    override val elementClass = IdealElement::class
    override fun factory() = IdealElement(this, emptyList())
}

data class IdealElement(
    override val keyType: Key<*> = IdealKey,
    val optimizedNodes: List<DagNode>,
    val peepholeCount: Int = 0,
    val gvnEliminated: Int = 0
) : Element

// ── Schedule Phase ────────────────────────────────────────────

object ScheduleKey : Key<ScheduleElement> {
    override val elementClass = ScheduleElement::class
    override fun factory() = ScheduleElement(this, emptyList())
}

data class ScheduledNode(
    val dagNode: DagNode,
    val scheduleOrder: Int
)

data class ScheduleElement(
    override val keyType: Key<*> = ScheduleKey,
    val scheduled: List<ScheduledNode>
) : Element

// ── Code Generation Phase ─────────────────────────────────────

object CodeGenKey : Key<CodeGenElement> {
    override val elementClass = CodeGenElement::class
    override fun factory() = CodeGenElement(this, byteArrayOf())
}

data class CodeGenElement(
    override val keyType: Key<*> = CodeGenKey,
    val machineCode: ByteArray
) : Element {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CodeGenElement) return false
        return machineCode.contentEquals(other.machineCode)
    }
    override fun hashCode(): Int = machineCode.contentHashCode()
}

// ── CCEK Context helpers ──────────────────────────────────────

/**
 * Build a CCEK Context with the full compiler pipeline state.
 * Each phase reads from the Context, transforms, and writes back.
 */
fun compilerContext(): Context = Context.empty()

/**
 * Flow a Context through a transformation function.
 * The function reads the current state from Context and returns a new Context
 * with the updated phase element.
 */
inline fun <reified E : Element> Context.flow(
    crossinline transform: (E?) -> E
): Context {
    val existing = get<E>()
    val result = transform(existing)
    val key = result.keyType as Key<E>
    return plus(key, result)
}
