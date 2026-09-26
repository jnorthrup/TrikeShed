package borg.trikeshed.graal.console

/**
 * Byte and text projections of the Graal console document panel and of the shared file viewer:
 * escaping, code/markdown views, the Cascade shape strip (RLE runs + fib ticks), the class-file
 * card and the hex head. Two escapers exist because the console escapes `& <` only while the
 * viewer (and the blackboard panel's esc9) escapes `& < > " '`.
 */
object Projection {
    /** Console escaper: `&` and `<`. */
    fun esc(x: String): String = x.replace("&", "&amp;").replace("<", "&lt;")

    /** Console attribute escaper: [esc] plus quotes. */
    fun attrEsc(x: String): String = esc(x).replace("\"", "&quot;").replace("'", "&#39;")

    /** Full escaper: `& < > " '` (the viewer's esc, the board's esc9). */
    fun escAll(x: String): String {
        val sb = StringBuilder(x.length)
        for (c in x) when (c) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;"); '\'' -> sb.append("&#39;"); else -> sb.append(c)
        }
        return sb.toString()
    }

    /** Viewer byte format: B / KiB / MiB. */
    fun fmtBytesViewer(b: Double): String = when {
        b > 1048576 -> Terrain.fixed(b / 1048576, 1) + " MiB"
        b > 1024 -> Terrain.fixed(b / 1024, 1) + " KiB"
        else -> b.toLong().toString() + " B"
    }

    /** Hash chip: short hex, full value in the title, click copies. */
    fun chip(hex: String?, label: String?): String {
        val t = hex ?: ""
        val short = t.replaceFirst("sha256:", "").take(8)
        return "<span class=\"hashchip\" title=\"" + t + "\" onclick=\"navigator.clipboard&&navigator.clipboard.writeText(this.title)\">" +
            (if (!label.isNullOrEmpty()) "$label " else "") + short + "…</span>"
    }

    private val STR = Regex("(&quot;|\")((?:[^\"\\\\]|\\\\.)*?)(\")")
    private val COM = Regex("(//.*$|#.*$)")
    private val KW = Regex("\\b(fun|val|var|class|object|interface|override|suspend|return|if|else|when|for|while|import|package|private|public|internal|data|companion|def|lambda|function|const|let|new|static|void|int|long|boolean|public|final|try|catch|finally|throw|async|await)\\b")

    /** Console code view: sequential string / comment / keyword highlighting over escaped lines. */
    fun codeView(text: String, maxLines: Int = 1200): String =
        "<div class=\"codeview\">" + text.split("\n").take(maxLines).joinToString("") { l ->
            var h = esc(l)
            h = STR.replace(h) { "<span class=\"str\">\"" + it.groupValues[2] + "\"</span>" }
            h = COM.find(h)?.let { m -> h.substring(0, m.range.first) + "<span class=\"com\">" + m.value + "</span>" + h.substring(m.range.last + 1) } ?: h
            h = KW.replace(h) { "<span class=\"kw\">" + it.groupValues[1] + "</span>" }
            "<div>" + (h.ifEmpty { " " }) + "</div>"
        } + "</div>"

    private val TOKENS = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|//.*$|#.*$|\\b(?:fun|val|var|class|object|interface|override|suspend|return|if|else|when|for|while|import|package|private|public|internal|data|companion|def|lambda|function|const|let|new|static|void|int|long|boolean|final|try|catch|finally|throw|async|await)\\b")

    /** Viewer code view: one tokenizing pass, every span escaped. */
    fun codeViewTokens(text: String, maxLines: Int = 1200): String =
        "<div class=\"codeview\">" + text.split("\n").take(maxLines).joinToString("") { line ->
            val html = StringBuilder()
            var at = 0
            for (m in TOKENS.findAll(line)) {
                html.append(escAll(line.substring(at, m.range.first)))
                val token = m.value
                val kind = if (token.startsWith("//") || token.startsWith("#")) "com" else if (token.startsWith("\"") || token.startsWith("'")) "str" else "kw"
                html.append("<span class=\"").append(kind).append("\">").append(escAll(token)).append("</span>")
                at = m.range.last + 1
            }
            val s = html.toString() + escAll(line.substring(at))
            "<div>" + (s.ifEmpty { " " }) + "</div>"
        } + "</div>"

    private val FENCE = Regex("```([\\s\\S]*?)```")
    private val H6 = Regex("^###### (.*)$", RegexOption.MULTILINE)
    private val H5 = Regex("^##### (.*)$", RegexOption.MULTILINE)
    private val H4 = Regex("^#### (.*)$", RegexOption.MULTILINE)
    private val H3 = Regex("^### (.*)$", RegexOption.MULTILINE)
    private val H2 = Regex("^## (.*)$", RegexOption.MULTILINE)
    private val H1 = Regex("^# (.*)$", RegexOption.MULTILINE)
    private val LI = Regex("^\\s*[-*] (.*)$", RegexOption.MULTILINE)
    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")
    private val CODE = Regex("`([^`]+)`")
    private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    private val PARA = Regex("\n\n+")
    private val SAFE_URL = Regex("^(https?://|/(?!/)|#)", RegexOption.IGNORE_CASE)

    private fun md(escaped: String, link: (String, String) -> String): String {
        var h = escaped
        h = FENCE.replace(h) { "<pre>" + it.groupValues[1] + "</pre>" }
        h = H6.replace(h) { "<h3>" + it.groupValues[1] + "</h3>" }
        h = H5.replace(h) { "<h3>" + it.groupValues[1] + "</h3>" }
        h = H4.replace(h) { "<h3>" + it.groupValues[1] + "</h3>" }
        h = H3.replace(h) { "<h3>" + it.groupValues[1] + "</h3>" }
        h = H2.replace(h) { "<h2>" + it.groupValues[1] + "</h2>" }
        h = H1.replace(h) { "<h1>" + it.groupValues[1] + "</h1>" }
        h = LI.replace(h) { "<li>" + it.groupValues[1] + "</li>" }
        h = BOLD.replace(h) { "<b>" + it.groupValues[1] + "</b>" }
        h = CODE.replace(h) { "<code>" + it.groupValues[1] + "</code>" }
        h = LINK.replace(h) { link(it.groupValues[1], it.groupValues[2]) }
        h = PARA.replace(h, "<br><br>")
        return "<div class=\"mdview\">$h</div>"
    }

    /** Console markdown view. */
    fun mdView(text: String): String = md(esc(text)) { label, url -> "<a href=\"$url\" target=\"_blank\" rel=\"noopener\">$label</a>" }

    /** Viewer markdown view: only http(s), same-origin path and fragment links become anchors. */
    fun mdViewSafe(text: String): String = md(escAll(text)) { label, url ->
        if (SAFE_URL.containsMatchIn(url)) "<a href=\"$url\" target=\"_blank\" rel=\"noopener\">$label</a>" else label
    }

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Class-file card for CAFEBABE bytes, else null. */
    fun classCard(b: ByteArray, fmtBytes: (Double) -> String): String? {
        if (b.size < 10) return null
        if (u16(b, 0) != 0xCAFE || u16(b, 2) != 0xBABE) return null
        val minor = u16(b, 4); val major = u16(b, 6); val pool = u16(b, 8)
        val jdk = if (major >= 49) "Java " + (major - 44) else "pre-1.5"
        return "<table class=\"classcard\"><tr><td>magic</td><td>CAFEBABE</td></tr><tr><td>class file version</td><td>$major.$minor ($jdk)</td></tr><tr><td>constant pool</td><td>$pool entries</td></tr><tr><td>size</td><td>" + fmtBytes(b.size.toDouble()) + "</td></tr></table>"
    }

    /** Hex + printable head of the first [n] bytes. */
    fun hexHead(b: ByteArray, n: Int, fmtBytes: (Double) -> String, escape: (String) -> String): String {
        val len = minOf(n, b.size)
        val out = StringBuilder()
        var i = 0
        while (i < len) {
            val end = minOf(i + 16, len)
            out.append((i until end).joinToString(" ") { (b[it].toInt() and 0xFF).toString(16).padStart(2, '0') })
            out.append("   ")
            for (k in i until end) { val x = b[k].toInt() and 0xFF; out.append(if (x in 32..126) x.toChar() else '·') }
            out.append('\n')
            i += 16
        }
        return "<pre style=\"font-size:10px\">" + escape(out.toString()) + "</pre><div style=\"color:var(--dim)\">first " + len + " of " + fmtBytes(b.size.toDouble()) + " — no friendlier projection for this type yet</div>"
    }

    /** One run of equal line classes. */
    class Run(val c: Char, val n: Int, val start: Int)
    class Shape(val runs: List<Run>, val lines: Int)

    val FIB: Set<Int> = HashSet<Int>().also { s -> var a = 0; var b = 1; while (a < 100000) { s.add(a); val t = a + b; a = b; b = t } }

    private val HEAD = Regex("^#{1,6} ")
    private val RULE = Regex("^=+$|^-{3,}$")
    private val TABLE = Regex("^\\|.*\\|")
    private val BULLET = Regex("^[-*+•] |^\\d+\\. ")
    private val CODELINE = Regex("^(fun|val|var|class|object|import|package|def|function|const|let|public|private|#include|@)")
    private val CODETAIL = Regex("[{};]\\s*$")
    private val SECTION = Regex("^(##+ )?(section|chapter)\\b", RegexOption.IGNORE_CASE)

    fun classifyLine(l: String): Char {
        val t = l.trim()
        if (t.isEmpty()) return '_'
        if (HEAD.containsMatchIn(t) || RULE.containsMatchIn(t)) return 'H'
        if (TABLE.containsMatchIn(t)) return 'T'
        if (t.startsWith("```")) return 'C'
        if (BULLET.containsMatchIn(t)) return 'B'
        if (CODELINE.containsMatchIn(t) || CODETAIL.containsMatchIn(t)) return 'J'
        if (SECTION.containsMatchIn(t)) return 'S'
        return 'P'
    }

    fun shapeRuns(text: String): Shape {
        val lines = text.split("\n")
        val runs = ArrayList<Run>()
        var c: Char? = null; var n = 0; var start = 1
        lines.forEachIndexed { i, l ->
            val k = classifyLine(l)
            if (k == c) n++ else { if (c != null) runs.add(Run(c!!, n, start)); c = k; n = 1; start = i + 1 }
        }
        if (c != null) runs.add(Run(c!!, n, start))
        return Shape(runs, lines.size)
    }

    private val SHAPE_NAMES = mapOf('H' to "heading", 'S' to "section", 'P' to "prose", '_' to "blank", 'B' to "bullet", 'T' to "table", 'J' to "code", 'C' to "fence")

    /** Shape strip; [jumpCall] true → `onclick="jumpLine(n)"` (console), false → `data-line` (viewer). */
    fun shapeStripHtml(text: String, jumpCall: Boolean, escape: (String) -> String): String {
        val shape = shapeRuns(text)
        val cells = shape.runs.mapIndexed { i, r ->
            val isFib = i in FIB
            val title = (SHAPE_NAMES[r.c] ?: r.c.toString()) + " · " + r.n + " lines " + r.start + "–" + (r.start + r.n - 1) + (if (isFib) " · depth $i" else "")
            "<button class=\"shape-cell" + (if (isFib) " fib" else "") + "\" title=\"" + title + "\" style=\"--c:var(--shape-" + r.c + ",var(--shape-P));flex:" + r.n + " 0 2px\" " +
                (if (jumpCall) "onclick=\"jumpLine(" + r.start + ")\"" else "data-line=\"" + r.start + "\"") + "></button>"
        }.joinToString("")
        val key = shape.runs.joinToString("") { it.c.toString() }
        return "<div class=\"shape-strip\">" + cells + "</div><code class=\"shape-key\">" + escape(key) + " · " + shape.lines + " lines · " + shape.runs.size + " runs</code>"
    }

    fun extOf(id: String): String = id.substringAfterLast('.').lowercase()
}
