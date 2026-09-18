@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge

import borg.trikeshed.forge.board.ForgeBoard
import borg.trikeshed.forge.board.ForgeBoardCard
import borg.trikeshed.forge.board.ForgeBoardColumn
import borg.trikeshed.forge.doc.ForgeBlock
import borg.trikeshed.forge.doc.ForgePage
import borg.trikeshed.forge.doc.defaultForgeBlocks
import borg.trikeshed.forge.graph.ForgeGraphMode
import borg.trikeshed.forge.sheet.Workbook
import borg.trikeshed.forge.sheet.WorkbookSheet
import borg.trikeshed.forge.sheet.normalizeWorkbook
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Forge workspace state — the decisions of the retired `web/script.js`, ported to commonMain.
 *
 * The page/block/workbook/board model, its normalization, and its JSON serde live here; the DOM,
 * localStorage, and fetch live in the jsForge adapter. Script.js section "Seed + persistence".
 */

typealias PageId = String
typealias BlockId = String
typealias SheetId = String
typealias CardId = String

/** The six surfaces the shell switches between (`state.view` / `setView`). */
enum class ForgeView(val id: String) {
    Doc("doc"), Board("board"), Graph("graph"), Sheet("sheet"), Shape("shape"), Host("host");

    companion object {
        fun of(id: String?): ForgeView = entries.firstOrNull { it.id == id } ?: Doc
    }
}

/** JSON value coercion — JsonSupport reifies numbers as Double and may hand back Array for empty lists. */
fun Any?.asStr(fallback: String = ""): String = (this as? String) ?: fallback
fun Any?.asStrOrNull(): String? = this as? String
fun Any?.asInt(fallback: Int = 0): Int =
    (this as? Number)?.toInt() ?: (this as? String)?.toIntOrNull() ?: fallback
fun Any?.asLong(fallback: Long = 0): Long =
    (this as? Number)?.toLong() ?: (this as? String)?.toLongOrNull() ?: fallback
fun Any?.asDouble(fallback: Double = 0.0): Double =
    (this as? Number)?.toDouble() ?: (this as? String)?.toDoubleOrNull() ?: fallback
fun Any?.asBool(fallback: Boolean = false): Boolean = (this as? Boolean) ?: fallback
fun Any?.asList(): List<Any?> = when (this) {
    is List<*> -> this
    is Array<*> -> toList()
    else -> emptyList()
}
fun Any?.asMap(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()
fun Any?.asMaps(): List<Map<String, Any?>> = asList().mapNotNull { (it as? Map<String, Any?>) }

private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"

fun Long.toBase36(): String {
    if (this == 0L) return "0"
    var n = this
    val negative = n < 0
    if (negative) n = -n
    val out = StringBuilder()
    while (n > 0) {
        out.append(BASE36[(n % 36).toInt()])
        n /= 36
    }
    return (if (negative) "-" else "") + out.reverse().toString()
}

/** `'b' + 8 random base36 + 4 time base36` — the script.js block/page/sheet/card id mint. */
fun forgeUid(nowMs: Long = Clock.System.now().toEpochMilliseconds(), random: Random = Random.Default): String {
    val rand = (random.nextLong() and 0xFFFFFFFFL).toBase36().padStart(8, '0').takeLast(8)
    return "b" + rand + nowMs.toBase36().takeLast(4)
}

/** The full client workspace: pages, the active view, the workbook, the board, sheet/graph UI state. */
class ForgeWorkspace(
    val pages: MutableList<ForgePage>,
    var activePageId: PageId,
    var view: ForgeView,
    val workbook: Workbook,
    var board: ForgeBoard,
    var sheetId: SheetId? = null,
    val sheetExpanded: MutableMap<String, Boolean> = mutableMapOf(),
    var graphMode: ForgeGraphMode? = null,
) {
    fun activePage(): ForgePage = pages.firstOrNull { it.id == activePageId } ?: pages.first()

    fun toMap(): Map<String, Any?> = mapOf(
        "pages" to pages.map { it.toMap() },
        "activePageId" to activePageId,
        "view" to view.id,
        "workbook" to workbook.toMap(),
        "board" to board.toMap(),
        "sheetId" to sheetId,
        "sheetExpanded" to sheetExpanded.toMap(),
        "graphMode" to graphMode?.id,
    )

    fun toJson(): String = JsonSupport.stringify(toMap())
}

/** localStorage keys — the persisted lanes of the retired script. */
object ForgeStorageKeys {
    const val WORKSPACE = "forge.workspace.v2"
    const val BOARD_SEED = "forge:seed:board"
    const val CAUSAL_SEED = "forge:seed:causal"
}

/** `blocksFromSeed`: the ingested-corpus page blocks, or null when the seed carries no entities. */
fun forgeBlocksFromSeed(seed: Map<String, Any?>): MutableList<ForgeBlock>? {
    val entities = seed["lcncEntities"].asMaps()
    if (entities.isEmpty()) return null
    val blocks = mutableListOf(ForgeBlock(forgeUid(), borg.trikeshed.forge.doc.BlockType.H1, "Ingested corpus"))
    entities.take(40).forEach { e ->
        val title = e["title"].asStrOrNull() ?: e["name"].asStrOrNull() ?: e["path"].asStrOrNull() ?: "Untitled"
        val kind = e["lcncKind"].asStrOrNull() ?: e["kind"].asStrOrNull() ?: "entity"
        blocks.add(ForgeBlock(forgeUid(), borg.trikeshed.forge.doc.BlockType.BULLET, "$title  ·  $kind"))
    }
    return blocks
}

/** The seed's board projection (`seed.board`), present only with at least one column. */
fun forgeSeedBoard(seed: Map<String, Any?>): Map<String, Any?>? {
    val board = seed["board"] as? Map<String, Any?> ?: return null
    return if (board["columns"].asList().isNotEmpty()) board else null
}

/** `seedColumns`: order-sorted column ids+names, or the canonical three. */
fun forgeSeedColumns(seed: Map<String, Any?>): MutableList<ForgeBoardColumn> {
    val board = forgeSeedBoard(seed)
    if (board != null) {
        return board["columns"].asMaps()
            .sortedBy { it["order"].asInt() }
            .map { ForgeBoardColumn(it["id"].asStr(), it["name"].asStr()) }
            .toMutableList()
    }
    return mutableListOf(
        ForgeBoardColumn("todo", "To do"),
        ForgeBoardColumn("doing", "Doing"),
        ForgeBoardColumn("done", "Done"),
    )
}

/** `seedCards`: order-sorted cards with the `priority  ·  ← deps` meta, or entity-derived fallbacks. */
fun forgeSeedCards(seed: Map<String, Any?>): MutableList<ForgeBoardCard> {
    val board = forgeSeedBoard(seed)
    if (board != null && board["cards"] != null) {
        return board["cards"].asMaps()
            .sortedBy { it["order"].asInt() }
            .map { c ->
                val deps = c["dependencies"].asList().map { it.asStr() }
                val meta = listOfNotNull(
                    c["priority"].asStrOrNull()?.takeIf { it.isNotEmpty() },
                    if (deps.isNotEmpty()) "← " + deps.joinToString(", ") else null,
                ).joinToString("  ·  ")
                ForgeBoardCard(
                    id = c["id"].asStrOrNull()?.takeIf { it.isNotEmpty() } ?: forgeUid(),
                    title = c["title"].asStr("Untitled"),
                    column = c["columnId"].asStr(),
                    meta = meta,
                )
            }
            .toMutableList()
    }
    val entities = seed["lcncEntities"].asMaps()
    return entities.take(12).mapIndexed { i, e ->
        ForgeBoardCard(
            id = forgeUid(),
            title = e["title"].asStrOrNull() ?: e["name"].asStrOrNull() ?: e["path"].asStrOrNull() ?: "Card ${i + 1}",
            column = if (i % 3 == 0) "doing" else "todo",
            meta = e["lcncKind"].asStrOrNull() ?: e["kind"].asStrOrNull() ?: "",
        )
    }.toMutableList()
}

/** `defaultState`: one home page seeded from the corpus or the welcome blocks, doc view, fresh workbook+board. */
fun defaultForgeWorkspace(seed: Map<String, Any?>): ForgeWorkspace {
    val homeId = forgeUid()
    return ForgeWorkspace(
        pages = mutableListOf(
            ForgePage(
                id = homeId,
                icon = "▤",
                title = "",
                format = "document",
                blocks = forgeBlocksFromSeed(seed) ?: defaultForgeBlocks(),
            )
        ),
        activePageId = homeId,
        view = ForgeView.Doc,
        workbook = Workbook.default(),
        board = ForgeBoard(columns = forgeSeedColumns(seed), cards = forgeSeedCards(seed)),
    )
}

/** `normalizeState`: normalize the workbook and guarantee page format/blocks shape. */
fun normalizeForgeWorkspace(loaded: Map<String, Any?>): ForgeWorkspace {
    val pages = loaded["pages"].asMaps().map { p ->
        ForgePage(
            id = p["id"].asStrOrNull()?.takeIf { it.isNotEmpty() } ?: forgeUid(),
            icon = p["icon"].asStr("▤"),
            title = p["title"].asStr(),
            format = p["format"].asStrOrNull() ?: "document",
            blocks = p["blocks"].asMaps().map { ForgeBlock.fromMap(it) }.toMutableList(),
            children = p["children"].asList().map { it.asStr() }.toMutableList(),
        )
    }.toMutableList()
    return ForgeWorkspace(
        pages = pages,
        activePageId = loaded["activePageId"].asStr(pages.firstOrNull()?.id ?: ""),
        view = ForgeView.of(loaded["view"].asStrOrNull()),
        workbook = normalizeWorkbook(loaded["workbook"]),
        board = ForgeBoard.fromMap(loaded["board"].asMap()),
        sheetId = loaded["sheetId"].asStrOrNull(),
        sheetExpanded = loaded["sheetExpanded"].asMap()
            .mapValuesTo(mutableMapOf()) { it.value.asBool() },
        graphMode = loaded["graphMode"].asStrOrNull()?.let { ForgeGraphMode.of(it) },
    )
}

/**
 * `loadState`'s parse half: a stored workspace is adopted only when it has a non-empty pages array;
 * anything else falls back to the seed-derived default. Board-card overlay is the caller's second lane.
 */
fun forgeWorkspaceFromJson(raw: String?, seed: Map<String, Any?>): ForgeWorkspace {
    val parsed = raw?.let { runCatching { JsonSupport.parse(it) }.getOrNull() } as? Map<String, Any?>
    return if (parsed != null && parsed["pages"].asList().isNotEmpty()) normalizeForgeWorkspace(parsed)
    else normalizeForgeWorkspace(defaultForgeWorkspace(seed).toMap())
}

/** The board-cards overlay persisted under its own key (`forge:seed:board`). */
fun ForgeWorkspace.overlayBoardCards(raw: String?) {
    val parsed = raw?.let { runCatching { JsonSupport.parse(it) }.getOrNull() } as? Map<String, Any?> ?: return
    val cards = parsed["cards"]?.asMaps() ?: return
    board.cards.clear()
    board.cards.addAll(cards.map { ForgeBoardCard.fromMap(it) })
}

/** `renderSeedNote`: the sidebar's seed provenance line. */
fun forgeSeedNoteParts(seed: Map<String, Any?>): List<String> {
    val parts = mutableListOf<String>()
    seed["source"].asMap()["title"].asStrOrNull()?.let { parts.add(it) }
    forgeSeedBoard(seed)?.let { board ->
        val cards = board["cards"].asList()
        if (cards.isNotEmpty()) parts.add("${cards.size} cards")
    }
    val entities = seed["lcncEntities"].asList()
    if (entities.isNotEmpty()) parts.add("${entities.size} entities")
    val causal = seed["causalGraph"].asList().ifEmpty { seed["causalNodes"].asList() }
    if (causal.isNotEmpty()) parts.add("${causal.size} causal nodes")
    val correlations = seed["correlations"].asList()
    if (correlations.isNotEmpty()) parts.add("${correlations.size} correlations")
    seed["conceptGraph"].asMap()["nodes"]?.asList()?.takeIf { it.isNotEmpty() }?.let { parts.add("${it.size} concepts") }
    seed["docsGraph"].asMap()["nodes"]?.asList()?.takeIf { it.isNotEmpty() }?.let { parts.add("${it.size} documents") }
    val sheets = seed["sheets"].asList()
    if (sheets.isNotEmpty()) parts.add("${sheets.size} sheets")
    seed["hosts"].asMap()["host"]?.asMap()?.takeIf { it.isNotEmpty() }?.let { host ->
        parts.add(if (host["subVm"].asBool()) "host: " + host["platform"].asStr() else "host: dead")
    }
    return parts
}
