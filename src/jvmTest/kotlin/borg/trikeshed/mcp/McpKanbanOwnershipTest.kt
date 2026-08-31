package borg.trikeshed.mcp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * KMFSM-001, the static half: "an architecture test or static gate proves MCP
 * reaches Kanban through LCNC and no write reaches legacy `ForgeBoardFSM` or
 * telemetry `KanbanFSM`."
 *
 * [LcncKanbanMcpTest] proves the ownership rule *behaviourally* — a real write
 * travels through the LCNC registry, and withholding the registry leaves no
 * other way in. That is the stronger evidence, but it only covers the paths a
 * test happens to exercise. This is the cheap complement: a source gate, so the
 * day someone adds a store handle "just for a quick read" the build says no
 * before anyone has to notice at runtime.
 *
 * The audit's warning is specific and worth restating, because three types are
 * named closely enough to attach to the wrong one:
 *   - `BoardStoreElement` — the ONE durable owner. The lens may not hold it.
 *   - `kanban/ForgeBoardFSM` — deprecated legacy UI state. Not an owner.
 *   - `userspace/reactor/KanbanFSM` — a telemetry reducer. Not card CRUD.
 */
class McpKanbanOwnershipTest {

    private companion object {
        const val LENS = "src/commonMain/kotlin/borg/trikeshed/mcp/LcncKanbanMcp.kt"
        const val PORT = "src/commonMain/kotlin/borg/trikeshed/mcp/BoardKanbanReadPort.kt"

        /** Never legitimate in either file: a second board, or a write channel. */
        val FORBIDDEN_EVERYWHERE = listOf(
            "ForgeBoardFSM" to "the deprecated legacy UI FSM is not a board the MCP surface may touch",
            "KanbanFSM" to "the telemetry reducer is not card CRUD and must not become the MCP board",
            "BoardIntake" to "constructing an intake command bypasses the LCNC runner that owns composition",
            ".intake" to "the store's intake channel is the single writer; only an LCNC runner may reach it",
        )
    }

    private fun source(rel: String): String {
        val root = System.getProperty("user.dir") ?: fail("no user.dir")
        val f = File(root, rel)
        if (!f.isFile) fail("expected source at $rel — did the file move? The gate must follow it.")
        return codeOnly(f.readText())
    }

    /** The unstripped file, for the one assertion that is ABOUT string literals. */
    private fun rawSource(rel: String): String {
        val root = System.getProperty("user.dir") ?: fail("no user.dir")
        val f = File(root, rel)
        if (!f.isFile) fail("expected source at $rel — did the file move? The gate must follow it.")
        return f.readText()
    }

    /**
     * Comments and string literals removed, so the gate reads CODE.
     *
     * This is not fussiness: the first draft of this test failed on its own
     * documentation. `LcncKanbanMcp`'s KDoc necessarily names `BoardIntake` and
     * `BoardStoreElement` to explain the lowering an MCP write travels through,
     * and says in prose that it has "no intake channel to reach" — a substring
     * scan flagged all of it. A gate that fires on the sentence describing the
     * rule is noise, and noise gets deleted.
     *
     * Order matters. Block comments go first (KDoc holds the prose), then string
     * literals — which also removes the `//` inside `oroboros://…` URIs so it is
     * not mistaken for a line comment — and line comments last. An identifier
     * that survives all three is a real reference.
     */
    private fun codeOnly(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
        .replace(Regex("""(?<!\\)"(\\.|[^"\\])*""""), "\"\"")
        .replace(Regex("""//[^\n]*"""), " ")

    @Test
    fun theLensNeverHoldsTheStore() {
        val text = source(LENS)
        // The whole ownership claim in one assertion: the handler cannot name the
        // durable owner, so it cannot hold one, so it cannot write around LCNC.
        assertTrue(
            "BoardStoreElement" !in text,
            "LcncKanbanMcp must not reference BoardStoreElement — it is handed a runner " +
                "registry and a read port precisely so no second write path can exist. " +
                "If a read is genuinely needed, add it to KanbanReadPort instead.",
        )
        for ((symbol, why) in FORBIDDEN_EVERYWHERE) {
            assertTrue(symbol !in text, "LcncKanbanMcp references '$symbol' — $why")
        }
    }

    @Test
    fun theReadPortReadsAndDoesNotWrite() {
        val text = source(PORT)
        // The port is ALLOWED to hold the store — projecting it is its job. What
        // it may not do is reach the write side of it.
        assertTrue(
            "BoardStoreElement" in text,
            "BoardKanbanReadPort is expected to project the store; if that changed, this gate is stale",
        )
        for ((symbol, why) in FORBIDDEN_EVERYWHERE) {
            assertTrue(symbol !in text, "BoardKanbanReadPort references '$symbol' — $why")
        }
        // Reads only: the store's mutating verbs are the intake channel, and the
        // port's own surface is four projections. Naming `send` here would mean
        // a command was being posted from the read side.
        assertTrue(
            !Regex("""\bintake\b""").containsMatchIn(text),
            "BoardKanbanReadPort must not name the intake channel — it is the read half",
        )
    }

    @Test
    fun theOnlyMutationsAreTheTwoLcncRunners() {
        // Tool names are the write vocabulary. If a third appears, it must be a
        // deliberate decision with a runner behind it, not an accident — and the
        // guide, schema, and audit all quote this pair. This assertion is ABOUT
        // the string literals, so it reads the raw file rather than stripped code.
        val toolConstants = Regex("""const val TOOL_\w+: String = "([^"]+)"""")
            .findAll(rawSource(LENS)).map { it.groupValues[1] }.toList()
        kotlin.test.assertEquals(
            listOf("kanban.submit", "kanban.move"),
            toolConstants,
            "the MCP write vocabulary changed; update the schema resource, guide-mcp-kanban.md, " +
                "and the audit together, or LCNC and MCP will describe different boards",
        )
        // Every tool the handler dispatches must resolve out of the injected
        // registry — `tools[` is the only lookup, and there is no fallback.
        assertTrue(
            "tools[name]" in source(LENS),
            "tool dispatch must resolve the runner from the injected LCNC registry",
        )
    }
}
