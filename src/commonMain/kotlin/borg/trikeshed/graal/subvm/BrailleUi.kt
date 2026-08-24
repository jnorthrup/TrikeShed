package borg.trikeshed.graal.subvm

/**
 * BrailleUi — window/tk approximation via Unicode Braille (U+2800 block), no native.
 *
 * Why braille: PyQt/PySide/tkinter are native C++ extensions blocked in the GraalPy sleeve
 * (`hermes-native-module-banlist.txt`) so an LLM ships the gap to Jules. The replacement is
 * pure-Kotlin, pure-Python, VT-native: each Braille cell is 2×4 dots → U+2800 + bits, so a
 * 80×24 VT is 40×12 braille cells without native, without NiceGUI, without PyQt. This is the
 * go-to window/tk approximation — oroboros and every polyglot subVM can host it.
 *
 * Computronium demo note: that repo's `demo/` used NiceGUI (still minimal, pyqt-free). Braille
 * replaces it with zero native deps — same tradeoff lean as NiceGUI, but VT-native and Jules-
 * replaceable.
 *
 * Common contract: no AWT, no Swing, no Qt. Host VT (`Vt220Terminal`) renders the returned
 * String; the Python sleeve (`graalpy-sleeve/hermes/braille/`) projects the same cells.
 */
object BrailleUi {

    // Braille dot bit mapping (Unicode U+2800):
    // dot1(0x01) dot4(0x08)
    // dot2(0x02) dot5(0x10)
    // dot3(0x04) dot6(0x20)
    // dot7(0x40) dot8(0x80)
    private const val BRAILLE_BASE = 0x2800

    fun cell(dots: Int): Char = (BRAILLE_BASE + (dots and 0xFF)).toChar()

    /** 2×4 dot matrix → braille char. `dots[y][x]` true = raised. */
    fun cell2x4(dots: Array<BooleanArray>): Char {
        var bits = 0
        if (dots.getOrNull(0)?.getOrNull(0) == true) bits = bits or 0x01
        if (dots.getOrNull(1)?.getOrNull(0) == true) bits = bits or 0x02
        if (dots.getOrNull(2)?.getOrNull(0) == true) bits = bits or 0x04
        if (dots.getOrNull(0)?.getOrNull(1) == true) bits = bits or 0x08
        if (dots.getOrNull(1)?.getOrNull(1) == true) bits = bits or 0x10
        if (dots.getOrNull(2)?.getOrNull(1) == true) bits = bits or 0x20
        if (dots.getOrNull(3)?.getOrNull(0) == true) bits = bits or 0x40
        if (dots.getOrNull(3)?.getOrNull(1) == true) bits = bits or 0x80
        return cell(bits)
    }

    /** Pure VT window frame as braille + box-drawing, no native. */
    fun windowFrame(title: String, wCells: Int, hCells: Int, body: List<String> = emptyList()): String = buildString {
        val top = "┌" + "─".repeat(wCells) + "┐"
        val titleLine = "│" + title.padEnd(wCells).take(wCells) + "│"
        val sep = "├" + "─".repeat(wCells) + "┤"
        val bottom = "└" + "─".repeat(wCells) + "┘"
        appendLine(top)
        appendLine(titleLine)
        appendLine(sep)
        for (i in 0 until hCells) {
            val line = body.getOrNull(i)?.padEnd(wCells)?.take(wCells) ?: " ".repeat(wCells)
            appendLine("│$line│")
        }
        append(bottom)
    }

    /**
     * Canvas: `pixels[y][x]` (true = dot) at 2× horizontal-dot resolution vs VT chars.
     * Renders as braille cells — 2 dots wide, 4 tall per char — so a 80×24 canvas is
     * 40×6 braille glyphs. Pure string, no native, host VT prints it verbatim.
     */
    fun canvas(pixels: Array<BooleanArray>): String = buildString {
        val h = pixels.size
        val w = if (h == 0) 0 else pixels[0].size
        val cellH = (h + 3) / 4
        val cellW = (w + 1) / 2
        for (cy in 0 until cellH) {
            for (cx in 0 until cellW) {
                val dots = Array(4) { dy -> BooleanArray(2) { dx ->
                    val y = cy * 4 + dy
                    val x = cx * 2 + dx
                    y < h && x < w && pixels[y][x]
                }}
                append(cell2x4(dots))
            }
            if (cy + 1 < cellH) append('\n')
        }
    }

    /** Minimal widget toolkit — Label, Button, Frame all as braille/VT strings. */
    data class Label(val text: String) { fun render(): String = text }
    data class Button(val label: String, val focused: Boolean = false) {
        fun render(): String = if (focused) "[▶ $label ◀]" else "[ $label ]"
    }
    data class Frame(val title: String, val children: List<Any> = emptyList()) {
        fun render(w: Int = 40, h: Int = 8): String {
            val body = children.map { when (it) {
                is Label -> it.render()
                is Button -> it.render()
                is String -> it
                else -> it.toString()
            }}
            return windowFrame(title, w, h, body)
        }
    }
}
