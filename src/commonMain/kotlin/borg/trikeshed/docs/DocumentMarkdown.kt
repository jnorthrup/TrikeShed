package borg.trikeshed.docs

/**
 * THE MUGGLE RENDERER: Markdown to HTML in commonMain, so the document surface
 * draws a note the same way on the JVM, in the browser bundle and on native.
 *
 * Deliberately small and safe rather than complete: headings, paragraphs,
 * bullet and numbered lists, block quotes, fenced code, rules, and the inline
 * trio (code spans, strong, emphasis) plus links whose targets are http(s),
 * mailto or relative. Everything is HTML-escaped first; raw HTML in the source
 * is text, never markup. What it does not do (tables, nested lists, images,
 * footnotes) it leaves as escaped paragraphs, visibly, rather than guessing.
 */
object DocumentMarkdown {

    fun render(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val out = StringBuilder()
        var i = 0
        val paragraph = ArrayList<String>()
        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            out.append("<p>").append(inline(paragraph.joinToString(" ") { it.trim() })).append("</p>\n")
            paragraph.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val language = trimmed.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.append(escape(lines[i])).append('\n'); i++ }
                    i++ // the closing fence, or the end of the text
                    out.append("<pre><code")
                    if (language.isNotEmpty() && language.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '+' }) out.append(" class=\"language-").append(language).append('"')
                    out.append('>').append(code).append("</code></pre>\n")
                }
                trimmed.isEmpty() -> { flushParagraph(); i++ }
                HEADING.matches(trimmed) -> {
                    flushParagraph()
                    val m = HEADING.matchEntire(trimmed)!!
                    val level = m.groupValues[1].length
                    out.append("<h").append(level).append('>').append(inline(m.groupValues[2].trim())).append("</h").append(level).append(">\n")
                    i++
                }
                RULE.matches(trimmed) -> { flushParagraph(); out.append("<hr>\n"); i++ }
                BULLET.matches(trimmed) -> {
                    flushParagraph()
                    out.append("<ul>\n")
                    while (i < lines.size && BULLET.matches(lines[i].trim())) {
                        out.append("<li>").append(inline(BULLET.matchEntire(lines[i].trim())!!.groupValues[1])).append("</li>\n"); i++
                    }
                    out.append("</ul>\n")
                }
                NUMBERED.matches(trimmed) -> {
                    flushParagraph()
                    out.append("<ol>\n")
                    while (i < lines.size && NUMBERED.matches(lines[i].trim())) {
                        out.append("<li>").append(inline(NUMBERED.matchEntire(lines[i].trim())!!.groupValues[1])).append("</li>\n"); i++
                    }
                    out.append("</ol>\n")
                }
                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val quoted = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { quoted.add(lines[i].trim().removePrefix(">").trim()); i++ }
                    out.append("<blockquote>").append(render(quoted.joinToString("\n"))).append("</blockquote>\n")
                }
                else -> { paragraph.add(line); i++ }
            }
        }
        flushParagraph()
        return out.toString()
    }

    /** Inline markup over already-escaped text: code spans first (their contents are literal), then strong, emphasis, links. */
    fun inline(text: String): String {
        val escaped = escape(text)
        val out = StringBuilder()
        var i = 0
        while (i < escaped.length) {
            if (escaped[i] == '`') {
                val close = escaped.indexOf('`', i + 1)
                if (close > i) { out.append("<code>").append(escaped, i + 1, close).append("</code>"); i = close + 1; continue }
            }
            out.append(escaped[i]); i++
        }
        var s = out.toString()
        s = STRONG.replace(s) { "<strong>" + it.groupValues[1] + "</strong>" }
        s = EMPHASIS.replace(s) { "<em>" + it.groupValues[1] + "</em>" }
        s = LINK.replace(s) { m ->
            val label = m.groupValues[1]
            val target = m.groupValues[2]
            if (safeTarget(target)) "<a href=\"" + target + "\">" + label + "</a>" else label + " (" + target + ")"
        }
        return s
    }

    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    /** Only targets a reader can follow without running anything: http(s), mailto, or a relative path. */
    fun safeTarget(target: String): Boolean {
        val t = target.trim().lowercase()
        if (t.isEmpty() || t.any { it.isWhitespace() || it == '"' }) return false
        val scheme = t.substringBefore(':', "")
        return scheme.isEmpty() || scheme == "http" || scheme == "https" || scheme == "mailto"
    }

    private val HEADING = Regex("""^(#{1,6})\s+(.+?)\s*#*$""")
    private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
    private val BULLET = Regex("""^[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^\d+[.)]\s+(.*)$""")
    private val STRONG = Regex("""\*\*(.+?)\*\*""")
    private val EMPHASIS = Regex("""(?<![\w*])[*_]([^*_\n]+?)[*_](?![\w*])""")
    private val LINK = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""")
}
