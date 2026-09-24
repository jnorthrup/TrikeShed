@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge.doc

import borg.trikeshed.forge.asList
import borg.trikeshed.forge.asStr
import borg.trikeshed.forge.asStrOrNull
import borg.trikeshed.forge.forgeUid

/**
 * Block-editor decisions — script.js sections "Block type definitions", "Page helpers", and the
 * document codecs (`blocksToText`/`blocksToHtml`/`blocksFromText`/`importWorkbook`'s parsing half).
 * Pure Kotlin; the DOM side of the editor is the jsForge adapter.
 */

/** The slash-menu taxonomy, in menu order. `typeDef(t)` = [of] with [P] fallback. */
enum class BlockType(
    val id: String,
    val label: String,
    val desc: String,
    val icon: String,
    val placeholder: String,
) {
    P("p", "Text", "Plain paragraph", "¶", "Type '/' for commands"),
    H1("h1", "Heading 1", "Big section heading", "H1", "Heading 1"),
    H2("h2", "Heading 2", "Medium section heading", "H2", "Heading 2"),
    H3("h3", "Heading 3", "Small section heading", "H3", "Heading 3"),
    TODO("todo", "To-do list", "Track tasks with checkboxes", "☑", "To-do"),
    BULLET("bullet", "Bulleted list", "Simple bulleted list", "•", "List item"),
    NUMBERED("numbered", "Numbered list", "Numbered list", "1.", "List item"),
    QUOTE("quote", "Quote", "Capture a quotation", "❝", "Quote"),
    CODE("code", "Code", "Code block with mono font", "</>", "Code"),
    DIVIDER("divider", "Divider", "Horizontal rule", "—", "");

    companion object {
        fun of(id: String?): BlockType = entries.firstOrNull { it.id == id } ?: P
    }
}

/** The slash menu filter: empty filter lists every type, else case-insensitive label substring. */
fun slashMatches(filter: String): List<BlockType> =
    if (filter.isEmpty()) BlockType.entries
    else BlockType.entries.filter { it.label.lowercase().contains(filter.lowercase()) }

/** One block of the document editor. `text`/`checked`/`type` mutate in place, as the editor does. */
class ForgeBlock(
    val id: String,
    var type: BlockType,
    var text: String = "",
    var checked: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to type.id,
        "text" to text,
        "checked" to checked,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): ForgeBlock = ForgeBlock(
            id = map["id"].asStrOrNull()?.takeIf { it.isNotEmpty() } ?: forgeUid(),
            type = BlockType.of(map["type"].asStrOrNull()),
            text = map["text"].asStr(),
            checked = map["checked"] as? Boolean ?: false,
        )
    }
}

/** A workspace page: a titled block list with an icon and a format tag. */
class ForgePage(
    val id: String,
    var icon: String = "▤",
    var title: String = "",
    var format: String = "document",
    val blocks: MutableList<ForgeBlock> = mutableListOf(),
    val children: MutableList<String> = mutableListOf(),
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "icon" to icon,
        "title" to title,
        "format" to format,
        "blocks" to blocks.map { it.toMap() },
        "children" to children.toList(),
    )
}

/** `defaultBlocks` — the welcome page content. */
fun defaultForgeBlocks(): MutableList<ForgeBlock> = mutableListOf(
    ForgeBlock(forgeUid(), BlockType.H1, "Welcome to Forge"),
    ForgeBlock(forgeUid(), BlockType.P, "This is your workspace. Documents, boards, and graphs are the same underlying content shown from different angles."),
    ForgeBlock(forgeUid(), BlockType.H2, "Getting started"),
    ForgeBlock(forgeUid(), BlockType.TODO, "Click a checkbox to mark it done", checked = false),
    ForgeBlock(forgeUid(), BlockType.TODO, "Press / at the start of a line for block types", checked = false),
    ForgeBlock(forgeUid(), BlockType.BULLET, "Everything persists locally — no server required"),
    ForgeBlock(forgeUid(), BlockType.QUOTE, "The blackboard is the database. The projection is the page."),
    ForgeBlock(forgeUid(), BlockType.DIVIDER, ""),
    ForgeBlock(forgeUid(), BlockType.CODE, "// blocks are typed\n// the graph is causal\n// the board is a projection"),
)

/** The Enter-key continuation: headings and quotes exit to a paragraph; every other type repeats. */
fun BlockType.enterContinuation(): BlockType = when (this) {
    BlockType.H1, BlockType.H2, BlockType.H3, BlockType.QUOTE -> BlockType.P
    else -> this
}

/** `numberedIndex`: 1-based ordinal of this block within its contiguous numbered run. */
fun numberedIndex(blocks: List<ForgeBlock>, idx: Int): Int {
    var n = 1
    var i = idx - 1
    while (i >= 0 && blocks[i].type == BlockType.NUMBERED) {
        n++
        i--
    }
    return n
}

// ── Document codecs ─────────────────────────────────────────────────────

/** `blocksToText`: the markdown rendering of a page. */
fun blocksToText(page: ForgePage): String = page.blocks.joinToString("\n\n") { block ->
    val text = block.text
    when (block.type) {
        BlockType.H1 -> "# $text"
        BlockType.H2 -> "## $text"
        BlockType.H3 -> "### $text"
        BlockType.BULLET -> "- $text"
        BlockType.NUMBERED -> "1. $text"
        BlockType.TODO -> "- [${if (block.checked) "x" else " "}] $text"
        BlockType.QUOTE -> "> $text"
        BlockType.CODE -> "```\n$text\n```"
        BlockType.DIVIDER -> "---"
        BlockType.P -> text
    }
}

fun htmlEscape(value: Any?): String {
    val s = value?.toString() ?: ""
    val out = StringBuilder(s.length)
    for (ch in s) {
        when (ch) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&#39;")
            else -> out.append(ch)
        }
    }
    return out.toString()
}

/** `blocksToHtml`: a standalone editable-HTML export of a page. */
fun blocksToHtml(page: ForgePage): String {
    val body = page.blocks.joinToString("\n") { block ->
        val text = htmlEscape(block.text)
        when (block.type) {
            BlockType.H1 -> "<h1>$text</h1>"
            BlockType.H2 -> "<h2>$text</h2>"
            BlockType.H3 -> "<h3>$text</h3>"
            BlockType.BULLET -> "<ul><li>$text</li></ul>"
            BlockType.NUMBERED -> "<ol><li>$text</li></ol>"
            BlockType.TODO -> "<p>[${if (block.checked) "x" else " "}] $text</p>"
            BlockType.QUOTE -> "<blockquote>$text</blockquote>"
            BlockType.CODE -> "<pre><code>$text</code></pre>"
            BlockType.DIVIDER -> "<hr>"
            BlockType.P -> "<p>$text</p>"
        }
    }
    return "<!doctype html><html><head><meta charset=\"utf-8\"><title>${htmlEscape(page.title.ifEmpty { "Document" })}</title></head><body><h1>${htmlEscape(page.title.ifEmpty { "Untitled" })}</h1>$body</body></html>\n"
}

private val RX_HEADING = Regex("^(#{1,3})\\s+(.*)$")
private val RX_TODO = Regex("^\\s*-\\s*\\[([ xX])\\]\\s+(.*)$")
private val RX_BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val RX_NUMBERED = Regex("^\\s*\\d+[.)]\\s+(.*)$")
private val RX_QUOTE = Regex("^\\s*>\\s?(.*)$")
private val RX_DIVIDER = Regex("^\\s*-{3,}\\s*$")
private val RX_FENCE = Regex("^\\s*```")

/** `blocksFromText`: markdown-ish text back into blocks. Empty input yields one empty paragraph. */
fun blocksFromText(text: String): MutableList<ForgeBlock> {
    val blocks = mutableListOf<ForgeBlock>()
    val lines = text.split(Regex("\r?\n"))
    var inCode = false
    val code = StringBuilder()
    var codeLines = 0
    fun push(type: BlockType, value: String, checked: Boolean = false) {
        blocks.add(ForgeBlock(forgeUid(), type, value, checked))
    }
    for (line in lines) {
        if (RX_FENCE.containsMatchIn(line)) {
            if (inCode) push(BlockType.CODE, code.toString())
            code.setLength(0); codeLines = 0
            inCode = !inCode
            continue
        }
        if (inCode) {
            if (codeLines > 0) code.append('\n')
            code.append(line); codeLines++
            continue
        }
        val heading = RX_HEADING.find(line)
        if (heading != null) {
            push(BlockType.of("h" + heading.groupValues[1].length), heading.groupValues[2])
            continue
        }
        val todo = RX_TODO.find(line)
        if (todo != null) {
            push(BlockType.TODO, todo.groupValues[2], todo.groupValues[1].lowercase() == "x")
            continue
        }
        val bullet = RX_BULLET.find(line)
        if (bullet != null) { push(BlockType.BULLET, bullet.groupValues[1]); continue }
        val numbered = RX_NUMBERED.find(line)
        if (numbered != null) { push(BlockType.NUMBERED, numbered.groupValues[1]); continue }
        val quote = RX_QUOTE.find(line)
        if (quote != null) { push(BlockType.QUOTE, quote.groupValues[1]); continue }
        if (RX_DIVIDER.matches(line)) { push(BlockType.DIVIDER, ""); continue }
        if (line.isNotBlank()) push(BlockType.P, line)
    }
    if (inCode && codeLines > 0) push(BlockType.CODE, code.toString())
    if (blocks.isEmpty()) push(BlockType.P, "")
    return blocks
}

/** `importedTitle`: file name minus extension. */
fun importedTitle(name: String?): String =
    (name ?: "Untitled").replace(Regex("\\.[A-Za-z0-9]+$"), "").ifEmpty { "Untitled" }

/** `importDocument`'s decision half: the page a dropped text document becomes. */
fun importedPageOf(name: String, text: String, format: String?): ForgePage = ForgePage(
    id = forgeUid(),
    icon = if (format == "pdf") "▧" else "▤",
    title = importedTitle(name),
    format = format ?: "document",
    blocks = blocksFromText(text),
)

/** `delimitedRow`: one CSV/TSV/pipe row with double-quote escaping. */
fun delimitedRow(line: String, delimiter: Char): List<String> {
    val cells = mutableListOf<String>()
    val value = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        if (ch == '"') {
            if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                value.append('"'); i += 2; continue
            }
            quoted = !quoted; i++; continue
        }
        if (!quoted && ch == delimiter) {
            cells.add(value.toString()); value.setLength(0); i++; continue
        }
        value.append(ch); i++
    }
    cells.add(value.toString())
    return cells
}

private val RX_MD_ROW = Regex("^\\s*\\|.*\\|\\s*$")
private val RX_MD_DIVIDER = Regex("^\\s*\\|?\\s*:?-{3,}\\s*(\\|\\s*:?-{3,}\\s*)+\\|?\\s*$")

/**
 * `importWorkbook`'s parsing half: delimited text (or a markdown table) into a sheet grid.
 * Markdown tables trim their edge pipes and cell whitespace; the separator row is dropped.
 */
fun parseDelimitedSheet(name: String, text: String, columnNameOf: (Int) -> String): borg.trikeshed.forge.sheet.WorkbookSheet {
    val source = text.replace("\r", "")
    val lines = source.split('\n').filter { it.isNotEmpty() || source.isEmpty() }
    val markdownLines = lines.filter { RX_MD_ROW.matches(it) }
    val markdownTable = markdownLines.size > 1
    val delimiter = if (markdownTable) '|' else if ('\t' in source) '\t' else ','
    val parsed = (if (markdownTable) markdownLines else lines)
        .filter { !(markdownTable && RX_MD_DIVIDER.matches(it)) }
        .map { line ->
            val row = delimitedRow(line, delimiter)
            if (markdownTable) {
                row.dropLast(if (row.lastOrNull() == "") 1 else 0)
                    .drop(if (row.firstOrNull() == "") 1 else 0)
                    .map { it.trim() }
            } else row
        }
    val width = maxOf(1, parsed.maxOfOrNull { it.size } ?: 0)
    val header = parsed.firstOrNull() ?: emptyList()
    val columns = (0 until width).map { col -> header.getOrNull(col)?.takeIf { it.isNotEmpty() } ?: columnNameOf(col) }
    val rows = parsed.drop(1).map { row ->
        (0 until width).map { row.getOrNull(it) ?: "" }.toMutableList()
    }.toMutableList()
    return borg.trikeshed.forge.sheet.WorkbookSheet(
        id = forgeUid(),
        title = importedTitle(name),
        columns = columns.toMutableList(),
        rows = rows,
    )
}

/** The doc-tools format label for a page. */
fun pageFormatLabel(format: String): String = when (format) {
    "pdf" -> "PDF import"
    "document" -> "Editable document"
    else -> "Imported document"
}

/** Export file name: page title reduced to a filesystem-safe stem. */
fun exportFileStem(title: String, fallback: String): String =
    (title.ifEmpty { fallback }).replace(Regex("[^A-Za-z0-9_-]+"), "-")
