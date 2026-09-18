@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge.shape

import borg.trikeshed.forge.ForgeView
import borg.trikeshed.forge.asLong

/**
 * Shape-strip decisions — script.js sections "Shape strip (ingest motif)" and the
 * "group_level ladder": the shape alphabet taxonomy, run/box row parsing, doc-kind
 * classification, store-row nibble keys, and the depth grouping. The alphabet, gate, and
 * box walker themselves are commonMain (`ForgeKanbanIngest.planRules`, `media.boxes`);
 * the DOM drawing and CDN OCR/PDF loading are the jsForge adapter.
 */

/** `SHAPE_NAMES` — the plan-run alphabet legend. */
val SHAPE_NAMES: Map<String, String> = linkedMapOf(
    "_" to "blank",
    "6" to "work packages",
    "7" to "section 7",
    "S" to "section",
    "H" to "heading",
    "W" to "package",
    "D" to "depends",
    "B" to "bullet",
    "T" to "table",
    "C" to "fence",
    "J" to "code",
    "P" to "prose",
)

/** Ingest routing by file name — the `SHAPE_*` regex taxonomy. */
enum class ShapeRoute { Media, Text, Office, Workbook, Image, Pdf, Unsupported }

private val RX_MEDIA = Regex("\\.(mp4|m4a|m4v|mov|3gp|heic|heif|avif|mj2)$", RegexOption.IGNORE_CASE)
private val RX_TEXT = Regex("\\.(md|markdown|txt|html?)$", RegexOption.IGNORE_CASE)
private val RX_OFFICE = Regex("\\.(docx|pptx|xlsx)$", RegexOption.IGNORE_CASE)
private val RX_WORKBOOK = Regex("\\.(csv|tsv|xlsx|xls|numbers|ods)$", RegexOption.IGNORE_CASE)
private val RX_IMAGE = Regex("\\.(png|jpe?g|gif|bmp|webp|tiff?)$", RegexOption.IGNORE_CASE)
private val RX_PDF = Regex("\\.pdf$", RegexOption.IGNORE_CASE)
private val RX_EXTENSION = Regex("\\.([A-Za-z0-9]+)$")

fun shapeRouteOf(fileName: String): ShapeRoute = when {
    RX_MEDIA.containsMatchIn(fileName) -> ShapeRoute.Media
    RX_TEXT.containsMatchIn(fileName) -> ShapeRoute.Text
    RX_OFFICE.containsMatchIn(fileName) -> ShapeRoute.Office
    RX_WORKBOOK.containsMatchIn(fileName) -> ShapeRoute.Workbook
    RX_IMAGE.containsMatchIn(fileName) -> ShapeRoute.Image
    RX_PDF.containsMatchIn(fileName) -> ShapeRoute.Pdf
    else -> ShapeRoute.Unsupported
}

/** `importExtractedFile`'s routing: workbook extensions open the sheet view, everything else a doc. */
fun importedViewOf(fileName: String): ForgeView =
    if (RX_WORKBOOK.containsMatchIn(fileName)) ForgeView.Sheet else ForgeView.Doc

fun importedFormatOf(fileName: String): String =
    RX_EXTENSION.find(fileName)?.groupValues?.get(1)?.lowercase() ?: "document"

/** One shape run: symbol plus extent (lines, half-open → inclusive; or a box path with byte width). */
data class ShapeRun(val c: String, val start: Int, val end: Int, val n: Int, val path: String? = null)

/**
 * A dropped/stored document's shape: the run strip, the symbol key, and the display kind.
 * `unit` is "lines" or "bytes"; `via` records the ingest lane (browser / tika / store).
 */
class ShapeDoc(
    val name: String,
    val lines: List<String>,
    val runs: List<ShapeRun>,
    val key: List<String>,
    val sep: String,
    val kind: String,
    val unit: String,
    val via: String? = null,
    val bytes: Long = 0,
    var importedView: ForgeView? = null,
)

/** `shapeOf`'s run parsing: `"sym:start:end"` (half-open, absolute) → inclusive end. */
fun shapeRunsOf(runStrings: List<String>): List<ShapeRun> = runStrings.map { s ->
    val parts = s.split(':')
    val start = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val end = parts.getOrNull(2)?.toIntOrNull() ?: 0
    ShapeRun(c = parts.getOrNull(0) ?: "", start = start, end = end - 1, n = end - start)
}

/** `shapeOf`'s kind gate: plan > table > code > note > prose. */
fun shapeKindOf(key: List<String>, isPlan: Boolean): String = when {
    isPlan -> "plan"
    "T" in key -> "table"
    key.any { it == "C" || it == "J" } -> "code"
    key.any { it == "H" || it == "S" } -> "note"
    else -> "prose"
}

/** `shapeOf`: a text document's shape from its Kotlin-published runs. */
fun shapeDocOf(name: String, text: String, runStrings: List<String>, isPlan: Boolean): ShapeDoc {
    val lines = text.split(Regex("\r?\n"))
    val runs = shapeRunsOf(runStrings)
    val key = runs.map { it.c }
    return ShapeDoc(
        name = name, lines = lines, runs = runs, key = key, sep = "",
        kind = shapeKindOf(key, isPlan), unit = "lines",
    )
}

/** `shapeOfBoxes`: `"path:bytes"` per ISO BMFF box in walk order; width ∝ bytes. */
fun shapeDocOfBoxes(name: String, rows: List<String>): ShapeDoc {
    val runs = rows.mapIndexed { i, s ->
        val j = s.lastIndexOf(':')
        val path = if (j >= 0) s.substring(0, j) else s
        ShapeRun(
            c = path.substringAfterLast('/'),
            path = path,
            start = i,
            end = i,
            n = if (j >= 0) s.substring(j + 1).toIntOrNull() ?: 0 else 0,
        )
    }
    return ShapeDoc(
        name = name,
        lines = rows.map { it.replace(Regex(":(\\d+)$"), "  $1 B") },
        runs = runs,
        key = runs.map { it.c },
        sep = " ",
        kind = "box media",
        unit = "bytes",
    )
}

/** `storeKey`: a store row's AngularCodec code as 4 hex nibbles — the ladder's zoom rings. */
fun storeKeyOf(code: Long): List<String> =
    (code and 0xFFFF).toString(16).padStart(4, '0').map { it.toString() }

/** A store row as a shape doc (`/api/graal/map` rows: id, bytes, seq, gen, code). */
fun storeShapeDoc(id: String, bytes: Long, code: Long): ShapeDoc = ShapeDoc(
    name = id, lines = emptyList(), runs = emptyList(), key = storeKeyOf(code), sep = "",
    kind = "store", unit = "rows", via = "store", bytes = bytes,
)

fun storeShapeDocOf(row: Any?): ShapeDoc? {
    val cells = (row as? List<*>) ?: (row as? Array<*>)?.toList() ?: return null
    val id = cells.getOrNull(0)?.toString() ?: return null
    val bytes = cells.getOrNull(1).asLong()
    val code = cells.getOrNull(4).asLong()
    return storeShapeDoc(id, bytes, code)
}

/**
 * The group_level ladder: docs grouped by the first `depth` key symbols (Int.MAX_VALUE = ∞, the
 * full key), groups ordered by population descending. One group = one Couch group_level=depth row.
 */
fun shapeGroups(docs: List<ShapeDoc>, depth: Int): List<Pair<String, List<Int>>> {
    val groups = LinkedHashMap<String, MutableList<Int>>()
    docs.forEachIndexed { i, d ->
        val k = d.key.take(depth).joinToString(d.sep)
        groups.getOrPut(k) { mutableListOf() }.add(i)
    }
    return groups.entries.sortedByDescending { it.value.size }.map { it.key to it.value.toList() }
}

/** The HUD rungs: positive fib ticks up to the longest key, plus ∞. */
fun shapeLadderRungs(fibTicks: List<Int>, maxKey: Int): List<Int> =
    fibTicks.filter { it > 0 && it <= maxKey } + Int.MAX_VALUE
