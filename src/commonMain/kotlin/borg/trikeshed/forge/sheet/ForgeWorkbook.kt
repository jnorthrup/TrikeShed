@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge.sheet

import borg.trikeshed.forge.asBool
import borg.trikeshed.forge.asInt
import borg.trikeshed.forge.asList
import borg.trikeshed.forge.asMaps
import borg.trikeshed.forge.asStr
import borg.trikeshed.forge.asStrOrNull
import borg.trikeshed.forge.forgeUid
import borg.trikeshed.parse.json.JsonSupport
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Workbook decisions — script.js "Render: sheets" minus the DOM: the workbook model and its
 * normalization, the formula tokenizer/parser/evaluator, TreeSheets cell/sort semantics, CSV export.
 * The grid renderer and key handling are the jsForge adapter.
 */

/** A cell address. `selected` in the persisted workbook state. */
data class CellRef(val row: Int, val col: Int) {
    fun toMap(): Map<String, Any?> = mapOf("row" to row, "col" to col)

    companion object {
        fun of(v: Any?): CellRef {
            val map = v as? Map<String, Any?>
            return CellRef(
                row = max(0, map?.get("row").asInt()),
                col = max(0, map?.get("col").asInt()),
            )
        }
    }
}

/** An editable workbook sheet: string columns and string rows, normalized rectangular. */
class WorkbookSheet(
    val id: String,
    var title: String,
    val columns: MutableList<String>,
    val rows: MutableList<MutableList<String>>,
) {
    fun rawCell(row: Int, col: Int): String = rows.getOrNull(row)?.getOrNull(col) ?: ""

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "columns" to columns.toList(),
        "rows" to rows.map { it.toList() },
    )

    companion object {
        fun fromMap(map: Map<String, Any?>, index: Int): WorkbookSheet {
            val columns = map["columns"].asList().map { it.asStr() }.ifEmpty { listOf("A") }
            val rows = map["rows"].asList().map { row ->
                val cells = row.asList().take(columns.size).map { it.asStr() }.toMutableList()
                while (cells.size < columns.size) cells.add("")
                cells
            }.toMutableList()
            return WorkbookSheet(
                id = map["id"].asStrOrNull()?.takeIf { it.isNotEmpty() } ?: forgeUid(),
                title = map["title"].asStrOrNull()?.takeIf { it.isNotEmpty() } ?: "Sheet ${index + 1}",
                columns = columns.toMutableList(),
                rows = rows,
            )
        }
    }
}

/** The editable workbook: sheets plus the active sheet and selected cell. */
class Workbook(
    val sheets: MutableList<WorkbookSheet>,
    var activeSheetId: String,
    var selected: CellRef,
) {
    fun sheetById(id: String?): WorkbookSheet? = sheets.firstOrNull { it.id == id }

    fun toMap(): Map<String, Any?> = mapOf(
        "sheets" to sheets.map { it.toMap() },
        "activeSheetId" to activeSheetId,
        "selected" to selected.toMap(),
    )

    companion object {
        /** `defaultWorkbook` — the Item/Qty/Price/Total demo grid with live formulas. */
        fun default(): Workbook {
            val sheetId = forgeUid()
            return Workbook(
                sheets = mutableListOf(
                    WorkbookSheet(
                        id = sheetId,
                        title = "Workbook",
                        columns = mutableListOf("Item", "Qty", "Price", "Total"),
                        rows = mutableListOf(
                            mutableListOf("Paper", "2", "3.50", "=B1*C1"),
                            mutableListOf("Pens", "5", "1.20", "=B2*C2"),
                            mutableListOf("Total", "", "", "=SUM(D1:D2)"),
                        ),
                    )
                ),
                activeSheetId = sheetId,
                selected = CellRef(0, 0),
            )
        }
    }
}

/** `normalizeWorkbook`: rectangular rows, guaranteed ids/titles, a live activeSheetId, clamped selection. */
fun normalizeWorkbook(workbook: Any?): Workbook {
    val map = workbook as? Map<String, Any?>
    val sheets = map?.get("sheets")?.asList()
    if (sheets == null) return Workbook.default()
    val normalized = sheets.mapIndexed { index, sheet ->
        WorkbookSheet.fromMap(sheet as? Map<String, Any?> ?: emptyMap(), index)
    }
    if (normalized.isEmpty()) return Workbook.default()
    val active = map["activeSheetId"].asStrOrNull()
    val selected = CellRef.of(map["selected"])
    return Workbook(
        sheets = normalized.toMutableList(),
        activeSheetId = if (normalized.any { it.id == active }) active!! else normalized.first().id,
        selected = CellRef(max(0, selected.row), max(0, selected.col)),
    )
}

// ── Read-only source sheets (the TreeSheets seed: a cell may hold another sheet) ──

data class SourceColumn(val name: String, val type: String)

/**
 * A seed-carried source sheet (`seed.sheets[]`); cells are raw JSON values — a Map with a
 * string `sheet` key is a nested-sheet reference ([isSheetRef]).
 */
class SourceSheet(
    val id: String,
    val title: String,
    val parent: String?,
    val columns: List<SourceColumn>,
    val rows: List<List<Any?>>,
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): SourceSheet = SourceSheet(
            id = map["id"].asStr(),
            title = map["title"].asStr(),
            parent = map["parent"].asStrOrNull(),
            columns = map["columns"].asMaps().map { SourceColumn(it["name"].asStr(), it["type"].asStr()) },
            rows = map["rows"].asList().map { it.asList() },
        )
    }
}

fun sourceSheetsOf(seed: Map<String, Any?>): List<SourceSheet> =
    seed["sheets"].asMaps().map { SourceSheet.fromMap(it) }

fun isSheetRef(cell: Any?): Boolean = cell is Map<*, *> && cell["sheet"] is String

fun sheetRefId(cell: Any?): String? = (cell as? Map<*, *>)?.get("sheet") as? String

/** `cellText`: scalar text, JSON for objects. */
fun cellText(cell: Any?): String = when (cell) {
    null -> ""
    is Map<*, *>, is List<*> -> JsonSupport.stringify(cell)
    else -> cell.toString()
}

enum class SortDir(val id: String) { Asc("asc"), Desc("desc") }

data class SheetSort(val col: Int, val dir: SortDir) {
    /** Header-click toggle: same column ascending flips to descending; anything else sorts ascending. */
    fun toggled(col: Int): SheetSort =
        if (this.col == col && dir == SortDir.Asc) SheetSort(col, SortDir.Desc) else SheetSort(col, SortDir.Asc)
}

/**
 * `sortedRows`: row indices in display order. Refs sort after scalars ( the ￿ prefix),
 * two finite numbers compare numerically, else lexically (JS used localeCompare; ordinal
 * compareTo is the portable approximation).
 */
fun sortedRowIndices(rows: List<List<Any?>>, sort: SheetSort?): List<Int> {
    val indices = rows.indices.toMutableList()
    if (sort == null) return indices
    val c = sort.col
    val comparator = Comparator<Int> { a, b ->
        val x = rows[a].getOrNull(c)
        val y = rows[b].getOrNull(c)
        val xs = if (isSheetRef(x)) "￿" + sheetRefId(x) else cellText(x)
        val ys = if (isSheetRef(y)) "￿" + sheetRefId(y) else cellText(y)
        val xn = xs.toDoubleOrNull()
        val yn = ys.toDoubleOrNull()
        val cmp = if (xn != null && yn != null && xs.isNotEmpty() && ys.isNotEmpty()) xn.compareTo(yn)
        else xs.compareTo(ys)
        if (sort.dir == SortDir.Desc) -cmp else cmp
    }
    indices.sortWith(comparator)
    return indices
}

/** `exportWorkbookCsv`'s text half: header + rows, quoted when a cell carries [",\n]. */
fun workbookCsvText(sheet: WorkbookSheet): String =
    (listOf(sheet.columns.toList()) + sheet.rows.map { it.toList() }).joinToString("\n") { row ->
        row.joinToString(",") { value ->
            val text = value
            if (Regex("[\",\n]").containsMatchIn(text)) "\"" + text.replace("\"", "\"\"") + "\"" else text
        }
    }

// ── Cell addresses ──────────────────────────────────────────────────────

/** `workbookColumnName`: 0 → A, 25 → Z, 26 → AA. */
fun workbookColumnName(index: Int): String {
    var n = index + 1
    var label = ""
    while (n > 0) {
        val rest = (n - 1) % 26
        label = (65 + rest).toChar() + label
        n = (n - 1) / 26
    }
    return label
}

/** `parseWorkbookRef`: "B12" → CellRef(row = 11, col = 1). */
fun parseWorkbookRef(value: String): CellRef? {
    val match = Regex("^([A-Z]+)([1-9][0-9]*)$", RegexOption.IGNORE_CASE).find(value) ?: return null
    var col = 0
    for (ch in match.groupValues[1].uppercase()) col = col * 26 + ch.code - 64
    return CellRef(row = (match.groupValues[2].toIntOrNull() ?: return null) - 1, col = col - 1)
}

// ── Formula engine ──────────────────────────────────────────────────────

/** Formula values: Double, Boolean, String, or a range List of those. */
enum class FormulaTokenKind { Value, Number, Word, Punct }

data class FormulaToken(val kind: FormulaTokenKind, val text: String, val number: Double = 0.0)

private val RX_FORMULA_NUMBER = Regex("^(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")
private val RX_FORMULA_WORD = Regex("^[A-Za-z_][A-Za-z0-9_]*")

/** `formulaTokens`: the lexer for a `=…` cell. Throws on unterminated text, bad number, bad token. */
fun formulaTokens(formula: String): List<FormulaToken> {
    val source = formula.drop(1)
    val tokens = mutableListOf<FormulaToken>()
    var i = 0
    while (i < source.length) {
        val ch = source[i]
        if (ch.isWhitespace()) { i++; continue }
        if (ch == '"' || ch == '\'') {
            val value = StringBuilder()
            i++
            while (i < source.length && source[i] != ch) { value.append(source[i]); i++ }
            if (i >= source.length) throw IllegalArgumentException("unterminated text")
            i++
            tokens.add(FormulaToken(FormulaTokenKind.Value, value.toString()))
            continue
        }
        if (ch.isDigit() || ch == '.') {
            val match = RX_FORMULA_NUMBER.find(source.substring(i)) ?: throw IllegalArgumentException("bad number")
            i += match.value.length
            tokens.add(FormulaToken(FormulaTokenKind.Number, match.value, match.value.toDouble()))
            continue
        }
        if (ch.isLetter() || ch == '_') {
            val match = RX_FORMULA_WORD.find(source.substring(i))!!
            i += match.value.length
            tokens.add(FormulaToken(FormulaTokenKind.Word, match.value))
            continue
        }
        if (ch in "+-*/^(),:") {
            tokens.add(FormulaToken(FormulaTokenKind.Punct, ch.toString()))
            i++
            continue
        }
        throw IllegalArgumentException("bad token")
    }
    return tokens
}

/** `workbookNumber`: finite numbers pass, booleans are 1/0, blank is 0, numeric strings parse, else throw. */
fun workbookNumber(value: Any?): Double = when (value) {
    is Double -> if (value.isFinite()) value else throw IllegalArgumentException("not numeric")
    is Number -> value.toDouble()
    is Boolean -> if (value) 1.0 else 0.0
    null -> 0.0
    is String -> {
        if (value.trim().isEmpty()) 0.0
        else value.toDoubleOrNull() ?: throw IllegalArgumentException("not numeric")
    }
    else -> value.toString().toDoubleOrNull() ?: throw IllegalArgumentException("not numeric")
}

/** `workbookTruthy`. */
fun workbookTruthy(value: Any?): Boolean = when (value) {
    is List<*> -> value.isNotEmpty()
    is Boolean -> value
    is Number -> value.toDouble() != 0.0
    else -> (value?.toString() ?: "").trim().isNotEmpty()
}

/** `workbookFunction`: SUM PRODUCT AVERAGE MIN MAX COUNT COUNTA ABS ROUND IF. */
fun workbookFunction(name: String, args: List<Any?>): Any? {
    val flat = mutableListOf<Any?>()
    for (arg in args) if (arg is List<*>) flat.addAll(arg) else flat.add(arg)
    val numeric = flat.mapNotNull { value ->
        when (value) {
            null -> null
            is Number -> value.toDouble().takeIf { it.isFinite() }
            is String -> if (value.trim().isEmpty()) null else value.toDoubleOrNull()
            else -> value.toString().toDoubleOrNull()
        }
    }
    return when (name.uppercase()) {
        "SUM" -> numeric.sum()
        "PRODUCT" -> numeric.fold(1.0) { acc, v -> acc * v }
        "AVERAGE" -> if (numeric.isEmpty()) 0.0 else numeric.sum() / numeric.size
        "MIN" -> if (numeric.isEmpty()) 0.0 else numeric.min()
        "MAX" -> if (numeric.isEmpty()) 0.0 else numeric.max()
        "COUNT" -> numeric.size
        "COUNTA" -> flat.count { it != null && it.toString() != "" }
        "ABS" -> kotlin.math.abs(workbookNumber(args.getOrNull(0)))
        "ROUND" -> {
            val digits = if (args.size > 1) workbookNumber(args[1]) else 0.0
            val factor = 10.0.pow(digits)
            // JS Math.round is half toward +∞, not half-to-even.
            floor(workbookNumber(args.getOrNull(0)) * factor + 0.5) / factor
        }
        "IF" -> if (workbookTruthy(args.getOrNull(0))) args.getOrNull(1) ?: true else args.getOrNull(2) ?: false
        else -> throw IllegalArgumentException("unknown function")
    }
}

/** Recursive-descent evaluator over [formulaTokens]: unary → power → mul/div → add/sub. */
object FormulaEvaluator {

    /** The token cursor as a class: the grammar's functions are mutually recursive (primary → addSub). */
    class Parse(
        val sheet: WorkbookSheet,
        val tokens: List<FormulaToken>,
        val stack: MutableSet<String>,
    ) {
        var pos = 0
        fun peek(): FormulaToken? = tokens.getOrNull(pos)
        fun take(): FormulaToken? = tokens.getOrNull(pos++)

        fun primary(): Any? {
            val token = take() ?: throw IllegalArgumentException("missing value")
            if (token.kind == FormulaTokenKind.Number) return token.number
            if (token.kind == FormulaTokenKind.Value) return token.text
            if (token.kind == FormulaTokenKind.Punct && token.text == "(") {
                val value = addSub()
                val close = take()
                if (close == null || close.text != ")") throw IllegalArgumentException("missing )")
                return value
            }
            if (token.kind != FormulaTokenKind.Word) throw IllegalArgumentException("expected value")
            val word = token.text
            val next = peek()
            if (next != null && next.kind == FormulaTokenKind.Punct && next.text == ":") {
                take()
                val finish = take()
                if (finish == null || finish.kind != FormulaTokenKind.Word) throw IllegalArgumentException("bad range")
                return workbookRange(sheet, word, finish.text, stack)
            }
            if (next != null && next.kind == FormulaTokenKind.Punct && next.text == "(") {
                take()
                val args = mutableListOf<Any?>()
                val first = peek()
                if (first == null || first.text != ")") {
                    while (true) {
                        args.add(addSub())
                        val sep = peek()
                        if (sep != null && sep.kind == FormulaTokenKind.Punct && sep.text == ",") { take(); continue }
                        break
                    }
                }
                val close = take()
                if (close == null || close.text != ")") throw IllegalArgumentException("missing )")
                return workbookFunction(word, args)
            }
            if (word.uppercase() == "TRUE") return true
            if (word.uppercase() == "FALSE") return false
            val ref = parseWorkbookRef(word) ?: throw IllegalArgumentException("unknown name")
            return evaluateCell(sheet, ref.row, ref.col, stack)
        }

        fun unary(): Any? {
            val next = peek()
            if (next != null && next.kind == FormulaTokenKind.Punct && (next.text == "+" || next.text == "-")) {
                val sign = if (take()!!.text == "-") -1.0 else 1.0
                return sign * workbookNumber(unary())
            }
            return primary()
        }

        fun power(): Any? {
            val left = unary()
            val next = peek()
            if (next != null && next.kind == FormulaTokenKind.Punct && next.text == "^") {
                take()
                return workbookNumber(left).pow(workbookNumber(power()))
            }
            return left
        }

        fun mulDiv(): Any? {
            var value = power()
            while (true) {
                val next = peek()
                if (next == null || next.kind != FormulaTokenKind.Punct || (next.text != "*" && next.text != "/")) break
                val op = take()!!.text
                val right = workbookNumber(power())
                value = if (op == "*") workbookNumber(value) * right else workbookNumber(value) / right
            }
            return value
        }

        fun addSub(): Any? {
            var value = mulDiv()
            while (true) {
                val next = peek()
                if (next == null || next.kind != FormulaTokenKind.Punct || (next.text != "+" && next.text != "-")) break
                val op = take()!!.text
                val right = workbookNumber(mulDiv())
                value = if (op == "+") workbookNumber(value) + right else workbookNumber(value) - right
            }
            return value
        }

        fun finish(): Any? {
            val value = addSub()
            if (pos != tokens.size) throw IllegalArgumentException("unexpected token")
            return value
        }
    }

    fun evaluate(sheet: WorkbookSheet, formula: String, stack: MutableSet<String>): Any? =
        Parse(sheet, formulaTokens(formula), stack).finish()

    /** `workbookRange`: every evaluated cell of the inclusive rectangle between two refs. */
    fun workbookRange(sheet: WorkbookSheet, start: String, end: String, stack: MutableSet<String>): List<Any?> {
        val first = parseWorkbookRef(start) ?: throw IllegalArgumentException("bad range")
        val last = parseWorkbookRef(end) ?: throw IllegalArgumentException("bad range")
        val values = mutableListOf<Any?>()
        for (row in min(first.row, last.row)..max(first.row, last.row)) {
            for (col in min(first.col, last.col)..max(first.col, last.col)) {
                values.add(evaluateCell(sheet, row, col, stack))
            }
        }
        return values
    }

    /** `evaluateWorkbookCell`: literals pass through; `=…` evaluates with circular-reference detection. */
    fun evaluateCell(sheet: WorkbookSheet, row: Int, col: Int, stack: MutableSet<String>): Any? {
        val raw = sheet.rawCell(row, col)
        if (!raw.startsWith('=')) {
            // Workbook cells are strings; a non-formula cell is a number when it parses, else the text.
            return if (raw.isNotEmpty()) raw.toDoubleOrNull() ?: raw else raw
        }
        val key = sheet.id + "!" + row + ":" + col
        if (!stack.add(key)) throw IllegalArgumentException("circular reference")
        try {
            val value = evaluate(sheet, raw, stack)
            stack.remove(key)
            return value
        } catch (e: Throwable) {
            stack.remove(key)
            throw e
        }
    }
}

/** JS `String(number)`: integral doubles print without the fraction ("7", not "7.0"). */
fun jsNumberString(value: Double): String =
    if (value.isFinite() && value == floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString()
    else value.toString()

/** `workbookDisplay`: the rendered cell — numbers trimmed to 10 decimal places, errors as #ERR. */
fun workbookDisplay(sheet: WorkbookSheet, row: Int, col: Int): String = try {
    when (val value = FormulaEvaluator.evaluateCell(sheet, row, col, mutableSetOf())) {
        null -> ""
        is Double -> jsNumberString(floor(value * 10000000000.0 + 0.5) / 10000000000.0)
        else -> value.toString()
    }
} catch (e: Throwable) {
    "#ERR"
}

/** `selectedWorkbookCell`: the persisted selection clamped into the sheet's bounds. */
fun selectedWorkbookCell(sheet: WorkbookSheet, selected: CellRef): CellRef = CellRef(
    row = min(max(0, selected.row), max(0, sheet.rows.size - 1)),
    col = min(max(0, selected.col), max(0, sheet.columns.size - 1)),
)
