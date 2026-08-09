package modelmux.acp

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.`▶`

/**
 * Memory tool harness for the paper's three agent roles (arXiv:2607.26637v1).
 *
 * RQ5: "the harness is a lever, not a neutral wrapper." The same model + same
 * content produces radically different store shapes depending on which tools
 * are available. This file defines the tool vocabularies from the paper's
 * Section C.4 as [AcpTool] declarations.
 *
 * Write set (management agent): view, create, str_replace, insert, delete,
 * rename, grep — the paper's seven-tool write set.
 *
 * Read set (search agent): view, grep, toc, section_read — the paper's
 * read-only navigation set. Ranked search variant (Center+BM25) appends
 * ranked_search.
 *
 * Shell variant: replaces the tool set with a bash shell over the store.
 */

// ═══════════════════════════════════════════
// Tool name constants
// ═══════════════════════════════════════════

const val TOOL_VIEW = "view"
const val TOOL_CREATE = "create"
const val TOOL_STR_REPLACE = "str_replace"
const val TOOL_INSERT = "insert"
const val TOOL_DELETE = "delete"
const val TOOL_RENAME = "rename"
const val TOOL_GREP = "grep"
const val TOOL_TOC = "toc"
const val TOOL_SECTION_READ = "section_read"
const val TOOL_RANKED_SEARCH = "ranked_search"
const val TOOL_SHELL = "shell"

// ═══════════════════════════════════════════
// JSON parameter schemas (OpenAI function-calling format)
// ═══════════════════════════════════════════

private val VIEW_SCHEMA = """{"type":"object","properties":{"path":{"type":"string","description":"Memory file path to read, e.g. /memories/people/alice.md"}},"required":["path"]}"""

private val CREATE_SCHEMA = """{"type":"object","properties":{"path":{"type":"string","description":"Memory file path to create"},"description":{"type":"string","description":"One-line frontmatter description (d_f)"},"content":{"type":"string","description":"Markdown body content"}},"required":["path","description","content"]}"""

private val STR_REPLACE_SCHEMA = """{"type":"object","properties":{"path":{"type":"string","description":"Memory file path to edit"},"old":{"type":"string","description":"Exact string to find — must be unique in the file"},"new":{"type":"string","description":"Replacement string"}},"required":["path","old","new"]}"""

private val INSERT_SCHEMA = """{"type":"object","properties":{"path":{"type":"string","description":"Memory file path"},"line":{"type":"integer","description":"Line number to insert at (0=prepend)"},"content":{"type":"string","description":"Content to insert"}},"required":["path","line","content"]}"""

private val DELETE_SCHEMA = """{"type":"object","properties":{"path":{"type":"string","description":"Memory file path to delete"}},"required":["path"]}"""

private val RENAME_SCHEMA = """{"type":"object","properties":{"old_path":{"type":"string","description":"Current path"},"new_path":{"type":"string","description":"New path"}},"required":["old_path","new_path"]}"""

private val GREP_SCHEMA = """{"type":"object","properties":{"pattern":{"type":"string","description":"Regex pattern to search for"},"path":{"type":"string","description":"Optional: limit to a directory or file"}},"required":["pattern"]}"""

private val TOC_SCHEMA = """{"type":"object","properties":{"path":{"type":"string","description":"Memory file or directory to list the heading tree of"}},"required":["path"]}"""

private val SECTION_READ_SCHEMA = """{"type":"object","properties":{"path":{"type":"string","description":"Memory file path"},"heading":{"type":"string","description":"Heading text to read the section under"}},"required":["path","heading"]}"""

private val RANKED_SEARCH_SCHEMA = """{"type":"object","properties":{"query":{"type":"string","description":"Search query"},"limit":{"type":"integer","description":"Max results (default 3)"}},"required":["query"]}"""

// ═══════════════════════════════════════════
// Tool sets
// ═══════════════════════════════════════════

/** View a memory file's content + frontmatter. */
val MEM_VIEW: AcpTool = TOOL_VIEW j VIEW_SCHEMA

/** Create a new memory file with frontmatter. */
val MEM_CREATE: AcpTool = TOOL_CREATE j CREATE_SCHEMA

/** Replace a unique string in a memory file. */
val MEM_STR_REPLACE: AcpTool = TOOL_STR_REPLACE j STR_REPLACE_SCHEMA

/** Insert content at a line in a memory file. */
val MEM_INSERT: AcpTool = TOOL_INSERT j INSERT_SCHEMA

/** Delete a memory file. */
val MEM_DELETE: AcpTool = TOOL_DELETE j DELETE_SCHEMA

/** Rename/move a memory file. */
val MEM_RENAME: AcpTool = TOOL_RENAME j RENAME_SCHEMA

/** Line-level regex search across memory files. */
val MEM_GREP: AcpTool = TOOL_GREP j GREP_SCHEMA

/** Table of contents — heading tree for a file or directory. */
val MEM_TOC: AcpTool = TOOL_TOC j TOC_SCHEMA

/** Read a specific heading section from a file. */
val MEM_SECTION_READ: AcpTool = TOOL_SECTION_READ j SECTION_READ_SCHEMA

/** BM25-ranked keyword search (Center+BM25 variant). */
val MEM_RANKED_SEARCH: AcpTool = TOOL_RANKED_SEARCH j RANKED_SEARCH_SCHEMA

/**
 * Write set — management agent's seven tools (paper Section C.4).
 * The management agent integrates and organizes incoming content into the store.
 */
val MEMORY_WRITE_TOOLS: Series<AcpTool> = 7 j { i: Int ->
    when (i) {
        0 -> MEM_VIEW
        1 -> MEM_CREATE
        2 -> MEM_STR_REPLACE
        3 -> MEM_INSERT
        4 -> MEM_DELETE
        5 -> MEM_RENAME
        6 -> MEM_GREP
        else -> error("index $i out of bounds")
    }
}

/**
 * Read set — search agent's four read-only tools (paper Section C.4).
 * The search agent traverses the store and returns attributed answers.
 */
val MEMORY_READ_TOOLS: Series<AcpTool> = 4 j { i: Int ->
    when (i) {
        0 -> MEM_VIEW
        1 -> MEM_GREP
        2 -> MEM_TOC
        3 -> MEM_SECTION_READ
        else -> error("index $i out of bounds")
    }
}

/**
 * Center+BM25 — read set plus ranked keyword search (paper Section 3).
 * Tests RQ5: "adding a tool changes behavior."
 */
val MEMORY_READ_TOOLS_BM25: Series<AcpTool> = 5 j { i: Int ->
    when (i) {
        0 -> MEM_VIEW
        1 -> MEM_GREP
        2 -> MEM_TOC
        3 -> MEM_SECTION_READ
        4 -> MEM_RANKED_SEARCH
        else -> error("index $i out of bounds")
    }
}

/**
 * Harness profile — selects which tool set an agent uses.
 * Paper RQ5 axis: Center, Center+BM25, Shell.
 */
enum class MemoryHarnessProfile {
    CENTER,
    CENTER_PLUS_BM25,
    SHELL,
    ;

    /** Write tools for this profile (management agent). */
    val writeTools: Series<AcpTool> get() = when (this) {
        CENTER, CENTER_PLUS_BM25 -> MEMORY_WRITE_TOOLS
        SHELL -> MEMORY_WRITE_TOOLS // shell uses same write ops, different invocation
    }

    /** Read tools for this profile (search agent). */
    val readTools: Series<AcpTool> get() = when (this) {
        CENTER -> MEMORY_READ_TOOLS
        CENTER_PLUS_BM25 -> MEMORY_READ_TOOLS_BM25
        SHELL -> MEMORY_READ_TOOLS // shell reads via grep/toc too
    }
}

/**
 * Memory role — the paper's three agent roles. Each role determines
 * which tool set is selected and which capabilities are required.
 */
enum class MemoryRole {
    MANAGEMENT,
    SEARCH,
    EXECUTION,
    ;

    /** Whether this role can write to the store. */
    val canWrite: Boolean get() = this == MANAGEMENT

    /** Tool set for this role under a given harness profile. */
    fun tools(profile: MemoryHarnessProfile): Series<AcpTool> =
        if (canWrite) profile.writeTools else profile.readTools
}

/** Verify a tool set has the expected tool names. */
fun toolNames(tools: Series<AcpTool>): List<String> {
    val names = mutableListOf<String>()
    for (i in 0 until tools.size) {
        names.add(tools[i].a)
    }
    return names
}
