package borg.trikeshed.web.pages

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * The cell grid shared by the Hermes xterm page and the VM terminal page: the server owns the
 * screen, this holds the last snapshot plus patches and paints rows of `.cell` spans.
 * [hermes] selects the Hermes variant (full blank cell, cursor cell left to CSS).
 */
internal class TerminalGrid(private val hermes: Boolean) {
    var rows = 24
    var columns = 80
    var revision: dynamic = 0
    var cursor: dynamic = js("({row:0,column:0,visible:true})")
    var lines: dynamic = js("[]")
    var lastCursorRow: dynamic = -1

    private val ansi16 = if (hermes) TerminalPalette.HERMES_ANSI16 else TerminalPalette.VM_ANSI16

    fun blank(): dynamic {
        val c: dynamic = obj()
        c.text = " "; c.continuation = false; c.fg = -1; c.bg = -1
        if (hermes) {
            c.bold = false; c.faint = false; c.italic = false; c.underline = false; c.blink = false
            c.inverse = false; c.concealed = false; c.crossedOut = false
        }
        return c
    }

    private fun blankLine(): dynamic {
        val a: dynamic = js("[]")
        for (i in 0 until columns) a.push(blank())
        return a
    }

    fun normalize() {
        while ((lines.length as Int) < rows) lines.push(blankLine())
        for (r in 0 until rows) {
            val line: dynamic = lines[r]
            while ((line.length as Int) < columns) line.push(blank())
            line.length = columns
        }
    }

    private fun color(index: dynamic, rgb: dynamic, fallback: String): String =
        TerminalPalette.color(ansi16, (index as Number).toInt(), if (rgb == null) null else (rgb as Number).toLong(), fallback)

    private fun cellSpan(cell: dynamic, r: Int, c: Int): HTMLElement {
        val isCursor = truthy(cursor.visible) && cursor.row == r && cursor.column == c
        val s = document.createElement("span") as HTMLElement
        s.className = "cell" + (if (truthy(cell.blink)) " blink" else "") + (if (isCursor) " cursor" else "")
        var fg = color(cell.fg, cell.fgRgb, "#d8dce6")
        var bg = color(cell.bg, cell.bgRgb, "#000")
        if (truthy(cell.inverse)) { val t = fg; fg = bg; bg = t }
        // Hermes: the cursor cell is left to CSS so the caret animation is not fighting an inline background.
        if (!hermes || !isCursor) {
            s.style.color = if (truthy(cell.concealed)) bg else fg
            s.style.backgroundColor = bg
        }
        s.style.fontWeight = if (truthy(cell.bold)) "700" else "400"
        s.style.opacity = if (truthy(cell.faint)) ".65" else "1"
        s.style.fontStyle = if (truthy(cell.italic)) "italic" else "normal"
        s.style.textDecoration = listOf(if (truthy(cell.underline)) "underline" else "", if (truthy(cell.crossedOut)) "line-through" else "")
            .filter { it.isNotEmpty() }.joinToString(" ")
        s.textContent = if (truthy(cell.continuation)) "" else if (truthy(cell.text)) str(cell.text) else " "
        return s
    }

    fun render(rowsToPaint: Collection<dynamic>) {
        normalize()
        val screen = byId("screen")
        for (rv in rowsToPaint) {
            if (rv == null || jsTypeOf(rv) != "number") continue
            val r = (rv as Number).toDouble()
            if (r < 0 || r >= rows || r != kotlin.math.floor(r)) continue
            val ri = r.toInt()
            var line = screen.children.item(ri) as HTMLElement?
            if (line == null) {
                line = document.createElement("div") as HTMLElement
                line.className = "termrow"
                screen.appendChild(line)
            }
            val cells: dynamic = lines[ri]
            val spans = Array(columns) { i -> cellSpan(cells[i], ri, i) }
            line.asDynamic().replaceChildren.apply(line, spans)
        }
        while (screen.children.length > rows) screen.lastChild!!.let { screen.removeChild(it) }
        byId("geometry").textContent = "$columns×$rows"
        byId("revision").textContent = "rev " + str(revision)
    }

    fun renderAll() = render((0 until rows).map { it.asDynamic() })

    /** Adopt a panel snapshot's geometry, cursor and lines. */
    fun adopt(p: dynamic) {
        if (truthy(p.rows)) rows = (p.rows as Number).toInt()
        if (truthy(p.columns)) columns = (p.columns as Number).toInt()
        revision = if (truthy(p.revision)) p.revision else 0
        if (truthy(p.cursor)) cursor = p.cursor
        lines = if (truthy(p.lines)) p.lines else js("[]")
    }

    /** Adopt the terminal header an event carries alongside its patches. */
    fun adoptHeader(t: dynamic) {
        if (truthy(t.cursor)) cursor = t.cursor
        if (truthy(t.revision)) revision = t.revision
        if (truthy(t.columns)) columns = (t.columns as Number).toInt()
        if (truthy(t.rows)) rows = (t.rows as Number).toInt()
    }

    fun patches(ps: dynamic) {
        val touched = LinkedHashSet<dynamic>()
        touched.add(lastCursorRow)
        touched.add(cursor.row)
        for (p in jsArray(ps)) {
            val pr: dynamic = if (truthy(p.revision)) p.revision else 0
            revision = js("Math.max")(revision, pr)
            if (!truthy(lines[p.y])) lines[p.y] = blankLine()
            val cells = jsArray(p.cells)
            for (i in cells.indices) if ((p.x as Number).toInt() + i < columns) lines[p.y][(p.x as Number).toInt() + i] = cells[i]
            touched.add(p.y)
        }
        render(touched)
        lastCursorRow = cursor.row
    }

    fun signalHtml(s: dynamic): String {
        val id = strOrEmpty(s.id)
        val cause: dynamic = s.causeSignalId
        val payload = if (truthy(s.payload)) str(s.payload) else ""
        return if (hermes) {
            val esc = TerminalPalette::escapeHermes
            "<b>" + esc(strOrEmpty(s.kind)) + "</b> <span class=\"id\">#" + esc(TerminalPalette.shortSignal(id, 10)) + "</span>" +
                (if (truthy(cause)) "<div class=\"cause\">← " + esc(TerminalPalette.shortSignal(str(cause), 10)) + "</div>" else "") +
                "<div>" + esc(TerminalPalette.preview(payload, true, 160)) + "</div>"
        } else {
            val esc = TerminalPalette::escapeVm
            "<b>" + esc(strOrEmpty(s.kind)) + "</b> #" + esc(TerminalPalette.shortSignal(id, 9)) +
                (if (truthy(cause)) "<div class=\"cause\">← " + esc(TerminalPalette.shortSignal(str(cause), 9)) + "</div>" else "") +
                "<div>" + esc(TerminalPalette.preview(payload, false, 120)) + "</div>"
        }
    }

    fun signal(s: dynamic) {
        if (!truthy(s)) return
        val cssEscape: dynamic = js("CSS.escape")
        if (document.querySelector("[data-signal=\"" + (cssEscape(s.id) as String) + "\"]") != null) return
        val div = document.createElement("div") as HTMLElement
        div.className = "sig " + str(s.lane) + (if (s.kind == "failed") " failed" else "")
        div.setAttribute("data-signal", str(s.id))
        div.innerHTML = signalHtml(s)
        val rail = if (s.lane == "manual") byId("manualRail") else byId("causalRail")
        rail.prepend(div)
        while (rail.children.length > 100) rail.removeChild(rail.lastChild!!)
    }
}
