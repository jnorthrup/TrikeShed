package borg.trikeshed.modelmux

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.cursor.Cursor

// ==================== SCAFFOLD & HARNESS ====================

/**
 * Knowledge Graph Scaffold - acts as a deterministic, non-parametric
 * component providing a stable topological map for the problem space.
 */
typealias KnowledgeGraphScaffold = Join<String, Series<String>>

/**
 * Skill Bank Memory Scaffold - freezes systemic entropy by representing
 * agent skills/memories.
 */
typealias SkillBankScaffold = Cursor

/**
 * Tool Ontology Scaffold - geometric invariant for agent tools.
 */
typealias ToolOntologyScaffold = Series<String>

/**
 * The unified Scaffold, comprising Knowledge Graphs, Skill Banks, and Tool Ontologies.
 */
data class Scaffold(
    val knowledgeGraph: KnowledgeGraphScaffold,
    val skillBank: SkillBankScaffold,
    val toolOntology: ToolOntologyScaffold
)

/**
 * The Harness acts as the experimental apparatus and statistical gating mechanism.
 * It governs information flow, time, budget, and reward evaluation.
 */
interface Harness {
    /**
     * Directs the active beam (LLM core) to interact with the detection medium (Scaffold).
     */
    suspend fun interact(core: ModelWorker, scaffold: Scaffold, prompt: Prompt): ModelResponse
}
