@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.terminal

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view

/** Indexed/default/RGB terminal color. -1 means the terminal default. */
data class VtColor(val index: Int = -1, val rgb: Int? = null)

data class VtStyle(
    val foreground: VtColor = VtColor(),
    val background: VtColor = VtColor(),
    val bold: Boolean = false,
    val faint: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val blink: Boolean = false,
    val inverse: Boolean = false,
    val concealed: Boolean = false,
    val crossedOut: Boolean = false,
)

data class VtCell(
    val text: String = " ",
    val style: VtStyle = VtStyle(),
    /** True for the second cell occupied by a full-width glyph. */
    val continuation: Boolean = false,
)

data class VtCursor(val row: Int, val column: Int, val visible: Boolean)

data class VtPatch(
    val revision: Long,
    val row: Int,
    val column: Int,
    val cells: Series<VtCell>,
    val causeSignalId: String? = null,
)

data class VtSnapshot(
    val revision: Long,
    val columns: Int,
    val rows: Int,
    val cursor: VtCursor,
    val title: String,
    val alternateScreen: Boolean,
    val applicationCursorKeys: Boolean,
    val lines: Series<Series<VtCell>>,
    val scrollback: Series<Series<VtCell>>,
)

enum class VtKey {
    ENTER, BACKSPACE, TAB, ESCAPE,
    UP, DOWN, RIGHT, LEFT, HOME, END, INSERT, DELETE, PAGE_UP, PAGE_DOWN,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

/**
 * Stateful VT220/ECMA-48 screen model. It consumes output text, not a host PTY, so it remains
 * commonMain and can back browser, TUI, desktop and test surfaces with one parser.
 */
class XtermTerminal(
    columns: Int = 80,
    rows: Int = 24,
    private val scrollbackLimit: Int = 2_000,
) {
    var columns: Int = columns.coerceAtLeast(1); private set
    var rows: Int = rows.coerceAtLeast(1); private set
    var title: String = ""; private set
    var applicationCursorKeys: Boolean = false; private set
    var originMode: Boolean = false; private set
    var autoWrap: Boolean = true; private set
    var cursorVisible: Boolean = true; private set
    var alternateScreen: Boolean = false; private set
    var revision: Long = 0L; private set

    private var cells: Array<Array<VtCell>> = blankScreen(this.columns, this.rows)
    private var dirty: Array<BooleanArray> = Array(this.rows) { BooleanArray(this.columns) { true } }
    private var row = 0
    private var column = 0
    private var savedRow = 0
    private var savedColumn = 0
    private var savedStyle = VtStyle()
    private var style = VtStyle()
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1
    private var wrapPending = false
    private var mainScreen: SavedScreen? = null
    private val scrollbackRows = ArrayDeque<Array<VtCell>>()
    private val replies = ArrayDeque<String>()
    private val csi = StringBuilder()
    private val osc = StringBuilder()
    private var parser = Parser.GROUND

    init {
        require(columns > 0 && rows > 0)
        require(scrollbackLimit >= 0)
    }

    fun feed(text: String): Series<VtPatch> {
        feedIntoState(text)
        return drainPatches()
    }

    fun feed(text: String, causeSignalId: String?): Series<VtPatch> {
        feedIntoState(text)
        return drainPatches(causeSignalId)
    }

    private fun feedIntoState(text: String) {
        var offset = 0
        while (offset < text.length) {
            val first = text[offset]
            val grapheme = if (first.isHighSurrogate() && offset + 1 < text.length && text[offset + 1].isLowSurrogate()) {
                text.substring(offset, offset + 2).also { offset += 2 }
            } else {
                first.toString().also { offset++ }
            }
            accept(grapheme)
        }
    }

    /** Encode one manual key according to the DEC cursor-key mode selected by guest output. */
    fun encode(key: VtKey, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false): String {
        val base = when (key) {
            VtKey.ENTER -> "\r"
            VtKey.BACKSPACE -> if (ctrl) "\b" else "\u007f"
            VtKey.TAB -> if (shift) "\u001b[Z" else "\t"
            VtKey.ESCAPE -> "\u001b"
            VtKey.UP -> cursorKey('A')
            VtKey.DOWN -> cursorKey('B')
            VtKey.RIGHT -> cursorKey('C')
            VtKey.LEFT -> cursorKey('D')
            VtKey.HOME -> "\u001b[H"
            VtKey.END -> "\u001b[F"
            VtKey.INSERT -> "\u001b[2~"
            VtKey.DELETE -> "\u001b[3~"
            VtKey.PAGE_UP -> "\u001b[5~"
            VtKey.PAGE_DOWN -> "\u001b[6~"
            VtKey.F1 -> "\u001bOP"
            VtKey.F2 -> "\u001bOQ"
            VtKey.F3 -> "\u001bOR"
            VtKey.F4 -> "\u001bOS"
            VtKey.F5 -> "\u001b[15~"
            VtKey.F6 -> "\u001b[17~"
            VtKey.F7 -> "\u001b[18~"
            VtKey.F8 -> "\u001b[19~"
            VtKey.F9 -> "\u001b[20~"
            VtKey.F10 -> "\u001b[21~"
            VtKey.F11 -> "\u001b[23~"
            VtKey.F12 -> "\u001b[24~"
        }
        return if (alt && key != VtKey.ESCAPE) "\u001b$base" else base
    }

    fun encodeText(text: String, alt: Boolean = false): String = if (alt) "\u001b$text" else text

    fun consumeReplies(): String {
        if (replies.isEmpty()) return ""
        return buildString { while (replies.isNotEmpty()) append(replies.removeFirst()) }
    }

    fun resize(columns: Int, rows: Int): Series<VtPatch> {
        val nextColumns = columns.coerceAtLeast(1)
        val nextRows = rows.coerceAtLeast(1)
        if (nextColumns == this.columns && nextRows == this.rows) return emptySeriesOf()
        val next = blankScreen(nextColumns, nextRows)
        val copyRows = minOf(this.rows, nextRows)
        val copyColumns = minOf(this.columns, nextColumns)
        for (r in 0 until copyRows) for (c in 0 until copyColumns) next[r][c] = cells[r][c]
        this.columns = nextColumns
        this.rows = nextRows
        cells = next
        dirty = Array(nextRows) { BooleanArray(nextColumns) { true } }
        row = row.coerceIn(0, nextRows - 1)
        column = column.coerceIn(0, nextColumns - 1)
        savedRow = savedRow.coerceIn(0, nextRows - 1)
        savedColumn = savedColumn.coerceIn(0, nextColumns - 1)
        scrollTop = 0
        scrollBottom = nextRows - 1
        wrapPending = false
        revision++
        return drainPatches()
    }

    fun reset(): Series<VtPatch> {
        resetState()
        return drainPatches()
    }

    private fun resetState() {
        cells = blankScreen(columns, rows)
        dirty = Array(rows) { BooleanArray(columns) { true } }
        row = 0; column = 0; savedRow = 0; savedColumn = 0
        style = VtStyle(); savedStyle = style
        scrollTop = 0; scrollBottom = rows - 1
        title = ""; applicationCursorKeys = false; originMode = false
        autoWrap = true; cursorVisible = true; wrapPending = false
        parser = Parser.GROUND; csi.clear(); osc.clear(); replies.clear()
        revision++
    }

    fun snapshot(scrollbackRows: Int = 200): VtSnapshot = VtSnapshot(
        revision = revision,
        columns = columns,
        rows = rows,
        cursor = VtCursor(row, column, cursorVisible),
        title = title,
        alternateScreen = alternateScreen,
        applicationCursorKeys = applicationCursorKeys,
        lines = rows j { r: Int -> columns j { c: Int -> cells[r][c] } },
        scrollback = scrollbackRows.coerceAtLeast(0).let { count ->
            val start = (this.scrollbackRows.size - count).coerceAtLeast(0)
            val selected = this.scrollbackRows.drop(start)
            selected.size j { r: Int -> selected[r].let { line -> line.size j { c: Int -> line[c] } } }
        },
    )

    fun plainText(): String = (0 until rows).joinToString("\n") { r ->
        buildString { for (c in 0 until columns) if (!cells[r][c].continuation) append(cells[r][c].text) }.trimEnd()
    }

    fun drainPatches(causeSignalId: String? = null): Series<VtPatch> {
        val patches = ArrayList<VtPatch>()
        for (r in 0 until rows) {
            var c = 0
            while (c < columns) {
                while (c < columns && !dirty[r][c]) c++
                if (c >= columns) break
                val start = c
                while (c < columns && dirty[r][c]) { dirty[r][c] = false; c++ }
                val end = c
                patches += VtPatch(revision, r, start, (end - start) j { i: Int -> cells[r][start + i] }, causeSignalId)
            }
        }
        return patches.size j { i: Int -> patches[i] }
    }

    private fun accept(g: String) {
        val ch = g[0]
        when (parser) {
            Parser.GROUND -> when (ch) {
                '\u001b' -> parser = Parser.ESCAPE
                '\u0007' -> replies.addLast("\u0007")
                '\b' -> { column = (column - 1).coerceAtLeast(0); wrapPending = false }
                '\t' -> { column = nextTab(column); wrapPending = false }
                '\n', '\u000b', '\u000c' -> lineFeed()
                '\r' -> { column = 0; wrapPending = false }
                else -> if (ch.code >= 0x20 && ch != '\u007f') put(g)
            }
            Parser.ESCAPE -> escape(ch)
            Parser.CSI -> {
                if (ch.code in 0x40..0x7e) { executeCsi(ch, csi.toString()); csi.clear(); parser = Parser.GROUND }
                else if (ch == '\u001b') parser = Parser.ESCAPE
                else if (csi.length < 128) csi.append(ch)
            }
            Parser.OSC -> when (ch) {
                '\u0007' -> { executeOsc(); parser = Parser.GROUND }
                '\u001b' -> parser = Parser.OSC_ESCAPE
                else -> if (osc.length < 4096) osc.append(ch)
            }
            Parser.OSC_ESCAPE -> if (ch == '\\') { executeOsc(); parser = Parser.GROUND } else {
                if (osc.length < 4095) osc.append('\u001b').append(ch)
                parser = Parser.OSC
            }
            Parser.DCS -> if (ch == '\u001b') parser = Parser.DCS_ESCAPE
            Parser.DCS_ESCAPE -> parser = if (ch == '\\') Parser.GROUND else Parser.DCS
            Parser.CHARSET -> parser = Parser.GROUND // VT220 G0/G1 designation consumed
        }
    }

    private fun escape(ch: Char) {
        parser = Parser.GROUND
        when (ch) {
            '[' -> { csi.clear(); parser = Parser.CSI }
            ']' -> { osc.clear(); parser = Parser.OSC }
            'P', '^', '_' -> parser = Parser.DCS
            '(', ')', '*', '+' -> parser = Parser.CHARSET
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> { lineFeed(); column = 0 }
            'M' -> reverseIndex()
            'H' -> Unit // tab-set accepted; fixed 8-column tabs remain deterministic
            'c' -> resetState()
            'Z' -> replies.addLast("\u001b[?62;1;2;6;7;8;9c") // DECID: VT220 family
            '=' -> Unit // application keypad mode — keypad is not exposed separately yet
            '>' -> Unit
        }
    }

    private fun executeOsc() {
        val value = osc.toString(); osc.clear()
        val command = value.substringBefore(';')
        if (command == "0" || command == "1" || command == "2") title = value.substringAfter(';', "").take(1024)
    }

    private fun executeCsi(final: Char, raw: String) {
        val private = raw.startsWith('?')
        val clean = raw.removePrefix("?").removePrefix(">")
        val values = if (clean.isEmpty()) listOf(0) else clean.split(';').map { it.toIntOrNull() ?: 0 }
        fun p(index: Int = 0, default: Int = 1): Int = values.getOrNull(index)?.takeIf { it != 0 } ?: default
        wrapPending = false
        when (final) {
            'A' -> row = (row - p()).coerceAtLeast(if (originMode) scrollTop else 0)
            'B' -> row = (row + p()).coerceAtMost(if (originMode) scrollBottom else rows - 1)
            'C', 'a' -> column = (column + p()).coerceAtMost(columns - 1)
            'D' -> column = (column - p()).coerceAtLeast(0)
            'E' -> { row = (row + p()).coerceAtMost(rows - 1); column = 0 }
            'F' -> { row = (row - p()).coerceAtLeast(0); column = 0 }
            'G', '`' -> column = (p() - 1).coerceIn(0, columns - 1)
            'd' -> row = absoluteRow(p())
            'H', 'f' -> { row = absoluteRow(p(0)); column = (p(1) - 1).coerceIn(0, columns - 1) }
            'J' -> eraseDisplay(values.firstOrNull() ?: 0)
            'K' -> eraseLine(values.firstOrNull() ?: 0)
            'L' -> insertLines(p())
            'M' -> deleteLines(p())
            '@' -> insertCharacters(p())
            'P' -> deleteCharacters(p())
            'X' -> eraseCharacters(p())
            'S' -> repeat(p()) { scrollUp(scrollTop, scrollBottom) }
            'T' -> repeat(p()) { scrollDown(scrollTop, scrollBottom) }
            'm' -> sgr(values)
            'r' -> setScrollRegion(values)
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h', 'l' -> setMode(values, private, final == 'h')
            'n' -> when (values.firstOrNull() ?: 0) {
                5 -> replies.addLast("\u001b[0n")
                6 -> replies.addLast("\u001b[${row + 1};${column + 1}R")
            }
            'c' -> replies.addLast("\u001b[?62;1;2;6;7;8;9c")
        }
    }

    private fun setMode(values: List<Int>, private: Boolean, enabled: Boolean) {
        if (!private) return
        for (mode in values) when (mode) {
            1 -> applicationCursorKeys = enabled
            6 -> { originMode = enabled; row = if (enabled) scrollTop else 0; column = 0 }
            7 -> autoWrap = enabled
            25 -> cursorVisible = enabled
            47, 1047, 1049 -> if (enabled) enterAlternateScreen(mode == 1049) else leaveAlternateScreen(mode == 1049)
        }
    }

    private fun enterAlternateScreen(save: Boolean) {
        if (alternateScreen) return
        if (save) saveCursor()
        mainScreen = SavedScreen(cells, row, column, style, scrollTop, scrollBottom)
        cells = blankScreen(columns, rows)
        dirty = Array(rows) { BooleanArray(columns) { true } }
        row = 0; column = 0; scrollTop = 0; scrollBottom = rows - 1
        alternateScreen = true; revision++
    }

    private fun leaveAlternateScreen(restore: Boolean) {
        val main = mainScreen ?: return
        cells = main.cells
        row = main.row.coerceIn(0, rows - 1); column = main.column.coerceIn(0, columns - 1)
        style = main.style; scrollTop = main.scrollTop.coerceIn(0, rows - 1); scrollBottom = main.scrollBottom.coerceIn(scrollTop, rows - 1)
        mainScreen = null; alternateScreen = false
        dirty = Array(rows) { BooleanArray(columns) { true } }
        if (restore) restoreCursor()
        revision++
    }

    private fun sgr(values: List<Int>) {
        if (values.isEmpty()) { style = VtStyle(); return }
        var i = 0
        while (i < values.size) {
            when (val code = values[i]) {
                0 -> style = VtStyle()
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(faint = true)
                3 -> style = style.copy(italic = true)
                4, 21 -> style = style.copy(underline = true)
                5, 6 -> style = style.copy(blink = true)
                7 -> style = style.copy(inverse = true)
                8 -> style = style.copy(concealed = true)
                9 -> style = style.copy(crossedOut = true)
                22 -> style = style.copy(bold = false, faint = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                25 -> style = style.copy(blink = false)
                27 -> style = style.copy(inverse = false)
                28 -> style = style.copy(concealed = false)
                29 -> style = style.copy(crossedOut = false)
                in 30..37 -> style = style.copy(foreground = VtColor(code - 30))
                39 -> style = style.copy(foreground = VtColor())
                in 40..47 -> style = style.copy(background = VtColor(code - 40))
                49 -> style = style.copy(background = VtColor())
                in 90..97 -> style = style.copy(foreground = VtColor(code - 90 + 8))
                in 100..107 -> style = style.copy(background = VtColor(code - 100 + 8))
                38, 48 -> {
                    val foreground = code == 38
                    if (values.getOrNull(i + 1) == 5 && values.getOrNull(i + 2) != null) {
                        val color = VtColor(values[i + 2].coerceIn(0, 255)); i += 2
                        style = if (foreground) style.copy(foreground = color) else style.copy(background = color)
                    } else if (values.getOrNull(i + 1) == 2 && values.getOrNull(i + 4) != null) {
                        val rgb = (values[i + 2].coerceIn(0, 255) shl 16) or
                            (values[i + 3].coerceIn(0, 255) shl 8) or values[i + 4].coerceIn(0, 255)
                        val color = VtColor(rgb = rgb); i += 4
                        style = if (foreground) style.copy(foreground = color) else style.copy(background = color)
                    }
                }
            }
            i++
        }
    }

    private fun put(g: String) {
        val cp = codePoint(g)
        if (isCombining(cp)) {
            val targetColumn = if (column > 0) column - 1 else 0
            val old = cells[row][targetColumn]
            cells[row][targetColumn] = old.copy(text = old.text + g)
            mark(row, targetColumn)
            return
        }
        if (wrapPending && autoWrap) { column = 0; lineFeed() }
        wrapPending = false
        val width = if (isWide(cp)) 2 else 1
        clearWideAt(row, column)
        cells[row][column] = VtCell(g, style)
        mark(row, column)
        if (width == 2 && column + 1 < columns) {
            clearWideAt(row, column + 1)
            cells[row][column + 1] = VtCell("", style, continuation = true)
            mark(row, column + 1)
        }
        revision++
        val last = column + width - 1 >= columns - 1
        if (last) { column = columns - 1; wrapPending = autoWrap } else column += width
    }

    private fun clearWideAt(r: Int, c: Int) {
        if (cells[r][c].continuation && c > 0) { cells[r][c - 1] = blank(); mark(r, c - 1) }
        if (!cells[r][c].continuation && c + 1 < columns && cells[r][c + 1].continuation) {
            cells[r][c + 1] = blank(); mark(r, c + 1)
        }
    }

    private fun lineFeed() {
        wrapPending = false
        if (row == scrollBottom) scrollUp(scrollTop, scrollBottom) else row = (row + 1).coerceAtMost(rows - 1)
    }

    private fun reverseIndex() {
        wrapPending = false
        if (row == scrollTop) scrollDown(scrollTop, scrollBottom) else row = (row - 1).coerceAtLeast(0)
    }

    private fun scrollUp(top: Int, bottom: Int) {
        if (top == 0 && bottom == rows - 1 && !alternateScreen && scrollbackLimit > 0) {
            scrollbackRows.addLast(cells[top].copyOf())
            while (scrollbackRows.size > scrollbackLimit) scrollbackRows.removeFirst()
        }
        for (r in top until bottom) cells[r] = cells[r + 1]
        cells[bottom] = blankRow()
        markRows(top, bottom); revision++
    }

    private fun scrollDown(top: Int, bottom: Int) {
        for (r in bottom downTo top + 1) cells[r] = cells[r - 1]
        cells[top] = blankRow()
        markRows(top, bottom); revision++
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseRange(row, column, columns); for (r in row + 1 until rows) eraseRange(r, 0, columns) }
            1 -> { for (r in 0 until row) eraseRange(r, 0, columns); eraseRange(row, 0, column + 1) }
            2, 3 -> for (r in 0 until rows) eraseRange(r, 0, columns)
        }
        if (mode == 3) scrollbackRows.clear()
    }

    private fun eraseLine(mode: Int) = when (mode) {
        0 -> eraseRange(row, column, columns)
        1 -> eraseRange(row, 0, column + 1)
        2 -> eraseRange(row, 0, columns)
        else -> Unit
    }

    private fun eraseCharacters(count: Int) = eraseRange(row, column, minOf(columns, column + count))

    private fun eraseRange(r: Int, start: Int, end: Int) {
        for (c in start.coerceAtLeast(0) until end.coerceAtMost(columns)) { cells[r][c] = blank(); mark(r, c) }
        revision++
    }

    private fun insertCharacters(count: Int) {
        val n = count.coerceIn(1, columns - column)
        for (c in columns - 1 downTo column + n) cells[row][c] = cells[row][c - n]
        for (c in column until column + n) cells[row][c] = blank()
        markRange(row, column, columns); revision++
    }

    private fun deleteCharacters(count: Int) {
        val n = count.coerceIn(1, columns - column)
        for (c in column until columns - n) cells[row][c] = cells[row][c + n]
        for (c in columns - n until columns) cells[row][c] = blank()
        markRange(row, column, columns); revision++
    }

    private fun insertLines(count: Int) {
        if (row !in scrollTop..scrollBottom) return
        repeat(count.coerceIn(1, scrollBottom - row + 1)) {
            for (r in scrollBottom downTo row + 1) cells[r] = cells[r - 1]
            cells[row] = blankRow()
        }
        markRows(row, scrollBottom); revision++
    }

    private fun deleteLines(count: Int) {
        if (row !in scrollTop..scrollBottom) return
        repeat(count.coerceIn(1, scrollBottom - row + 1)) {
            for (r in row until scrollBottom) cells[r] = cells[r + 1]
            cells[scrollBottom] = blankRow()
        }
        markRows(row, scrollBottom); revision++
    }

    private fun setScrollRegion(values: List<Int>) {
        val top = (values.getOrNull(0)?.takeIf { it > 0 } ?: 1) - 1
        val bottom = (values.getOrNull(1)?.takeIf { it > 0 } ?: rows) - 1
        if (top in 0 until bottom && bottom < rows) {
            scrollTop = top; scrollBottom = bottom
            row = if (originMode) scrollTop else 0; column = 0
        }
    }

    private fun saveCursor() { savedRow = row; savedColumn = column; savedStyle = style }
    private fun restoreCursor() {
        row = savedRow.coerceIn(0, rows - 1); column = savedColumn.coerceIn(0, columns - 1); style = savedStyle; wrapPending = false
    }
    private fun absoluteRow(oneBased: Int): Int =
        if (originMode) (scrollTop + oneBased - 1).coerceIn(scrollTop, scrollBottom) else (oneBased - 1).coerceIn(0, rows - 1)
    private fun nextTab(c: Int): Int = (((c / 8) + 1) * 8).coerceAtMost(columns - 1)
    private fun cursorKey(final: Char): String = if (applicationCursorKeys) "\u001bO$final" else "\u001b[$final"
    private fun blank(): VtCell = VtCell(style = style)
    private fun blankRow(): Array<VtCell> = Array(columns) { blank() }
    private fun mark(r: Int, c: Int) { if (r in dirty.indices && c in dirty[r].indices) dirty[r][c] = true }
    private fun markRange(r: Int, start: Int, end: Int) { for (c in start until end) mark(r, c) }
    private fun markRows(start: Int, end: Int) { for (r in start..end) dirty[r].fill(true) }

    private data class SavedScreen(
        val cells: Array<Array<VtCell>>,
        val row: Int,
        val column: Int,
        val style: VtStyle,
        val scrollTop: Int,
        val scrollBottom: Int,
    )

    private enum class Parser { GROUND, ESCAPE, CSI, OSC, OSC_ESCAPE, DCS, DCS_ESCAPE, CHARSET }

    companion object {
        private fun blankScreen(columns: Int, rows: Int): Array<Array<VtCell>> = Array(rows) { Array(columns) { VtCell() } }
        private fun codePoint(s: String): Int = if (s.length == 2 && s[0].isHighSurrogate() && s[1].isLowSurrogate()) {
            0x10000 + ((s[0].code - 0xD800) shl 10) + (s[1].code - 0xDC00)
        } else s[0].code
        private fun isCombining(cp: Int): Boolean = cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF ||
            cp in 0x1DC0..0x1DFF || cp in 0x20D0..0x20FF || cp in 0xFE20..0xFE2F
        private fun isWide(cp: Int): Boolean = cp in 0x1100..0x115F || cp in 0x2329..0x232A ||
            cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
            cp in 0xFE10..0xFE19 || cp in 0xFE30..0xFE6F || cp in 0xFF00..0xFF60 ||
            cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1FAFF || cp in 0x20000..0x3FFFD
    }
}
