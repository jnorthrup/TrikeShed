package borg.trikeshed.utils.ingress

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.lib.AppendWal
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() = runBlocking {
    val forgeHome = File(System.getProperty("user.home"), ".local/forge")
    val walFile = File(forgeHome, JulesBoardStore.WAL_FILENAME)
    val wal = JvmAppendWal(walFile)
    val store = JulesBoardStore(wal)

    val workId = "ingest-graphify-pggraph-dry-capabilities"
    val title = "Merge Graphify + pgGraph DRY capabilities into Forge blackboard facade"
    val spec = """
    TARGET: Single merged ingress port for Python (Graphify) and PostgreSQL (pgGraph) capabilities

    INGEST PRODUCTS:
    1. Graphify: https://github.com/Graphify-Labs/graphify
       - Turns codebases, docs, SQL schemas, configs, PDFs into queryable knowledge graphs
       - Provides /graphify skill for Claude Code, Cursor, Codex, Gemini CLI
       - Local deterministic AST parsing

    2. pgGraph: https://github.com/Evokoa/pgGraph
       - Open-source graph database superpowers for existing Postgres data
       - Backend for https://polygres.com (internal search that feels like extended context)
       - Turns Postgres rows, relationships, and embeddings into one hybrid query

    INTEGRATION GOAL:
    - DRY: Design a single unified blackboard facade that abstracts both graph capabilities
    - Python integration path: Graphify's AST parsing and knowledge graph extraction
    - PostgreSQL integration path: pgGraph's hybrid query + embeddings
    - Forge blackboard ingress: unified capability surface for TrikeShed agents

    DELIVERABLES:
    1. Capability facade: GraphCapability interface with dual backends (AST + Postgres)
    2. Ingress port: graphIngess() accepting both Graphify parses and pgGraph queries
    3. Blackboard projection: unified fact schema for both graph types
    4. Test: one mixed query spanning Python codebase docs + Postgres rows returns ranked answer

    CONSTRAINTS:
    - No redundant implementations; common path must be shared
    - Preserve both products' distinct strengths (AST precision vs hybrid search)
    - Compatible with existing JulesSessionCard/JulesBoardStore WAL projection

    ACCEPTANCE:
    [ ] Single GraphCapability interface wires both backends
    [ ] ForgeBoardIngest can accept both Graphify and pgGraph sources
    [ ] Blackboard query yields unified ranking across Python + Postgres
    [ ] No duplicate parsing/query code in the dual paths
    """.trimIndent()

    val cause = JulesCause.WorkQueued(
        workId = workId,
        tier = "trikeshed",
        title = title,
        spec = spec,
        parent = null,
        score = 0.75,
        at = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    )

    store.appendWork(workId, cause)
    println("Appended work: $workId")
    println("Title: $title")
    println("Queue entry count: ${store.loadQueue().size}")
}
