package borg.trikeshed.narsese

/**
 * A markup page's text as its markup declares it, for curation; nothing is inferred from layout.
 *
 * The zone read is `<main>`, else `<article>`, else `<body>` without its site `header` and `footer`; `nav` and
 * `aside` are navigation wherever they sit, and script, style and other non-text elements hold no text. Inside it:
 *  - `h1`…`h6` are ATX heading lines, `#`…`######`, the markup's own levels;
 *  - a block that opens in bold keeps that run as `**lead**`, the run-in head of print typography;
 *  - an `li` is a `- ` line, a table row one line with its cells joined by ` | `;
 *  - every other block is a paragraph, and blocks are separated by a blank line.
 * Character references decode, and whitespace collapses as a browser collapses it.
 *
 * The zone's anchors are the page's links stream: each `href`, resolved against the page's canonical address, with
 * its anchor text and the block it sits in. A link to a place in the page itself is not a link out. Links are never
 * text: the extract carries the anchor words only.
 */
object MarkupStreams {
    /** One anchor read in the zone: where it points, its words, and the block (as the extract writes it) holding it. */
    class Link(val href: String, val anchor: String, val line: String)

    /** [html] as text: its `<title>` first as `# title` when it has one, then the zone's blocks. */
    fun text(html: String): String = walk(html, null)

    /** The zone's anchors, in reading order. */
    fun links(html: String): List<Link> = ArrayList<Link>().also { walk(html, it) }

    /** [links] as the twin the miner keeps beside the extract: `href`, `anchor`, `line`, tab-separated, one per row. */
    fun linksTsv(html: String): String = buildString {
        append("href\tanchor\tline\n")
        for (l in links(html)) append(cell(l.href)).append('\t').append(cell(l.anchor)).append('\t').append(cell(l.line)).append('\n')
    }

    /** A links twin read back. */
    fun linksOf(tsv: String): List<Link> = tsv.lineSequence().drop(1).mapNotNull { r ->
        val c = r.split('\t')
        if (c.size < 3 || c[0].isBlank()) null else Link(c[0], c[1], c[2])
    }.toList()

    private fun cell(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun walk(html: String, sink: MutableList<Link>?): String {
        val zone = when {
            Regex("<main[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(html) -> "main"
            Regex("<article[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(html) -> "article"
            else -> "body"
        }
        val bodied = Regex("<body[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(html)
        val base = if (sink == null) null else (CANONICAL.find(html) ?: BASE.find(html))?.groupValues?.get(1)?.let(::decode)
        val lines = ArrayList<String>()
        val stack = ArrayList<String>()
        val depth = HashMap<String, Int>()
        fun d(n: String) = depth[n] ?: 0
        val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)
            ?.groupValues?.get(1)?.let { WS.replace(INVISIBLE.replace(decode(it), ""), " ").trim() }.orEmpty()
        val block = StringBuilder()
        var heading = 0
        var item = false
        var lead = NONE
        val cells = ArrayList<String>()
        // The anchor open now (its href and where its words begin in the block), and the anchors the block holds.
        var href: String? = null
        var from = 0
        val held = ArrayList<Pair<String, String>>()

        fun reading(): Boolean {
            if (NAV.any { d(it) > 0 }) return false
            return when (zone) {
                "main" -> d("main") > 0
                "article" -> d("article") > 0
                else -> (!bodied || d("body") > 0) && SITE.none { d(it) > 0 }
            }
        }
        fun closeLead() {
            if (lead != OPEN) return
            while (block.isNotEmpty() && block[block.length - 1] == ' ') block.setLength(block.length - 1)
            block.append("**"); lead = DONE
        }
        fun closeAnchor() {
            val h = href ?: return
            href = null
            val words = block.substring(minOf(from, block.length)).replace("**", "").trim()
            if (words.isNotEmpty()) held.add(h to words)
        }
        fun flush() {
            if (href != null) { closeAnchor() }
            closeLead()
            val s = block.toString().trim()
            block.setLength(0); lead = NONE
            if (s.isEmpty() || s == "****") { held.clear(); return }
            val line = when {
                heading > 0 -> "#".repeat(heading) + " " + s.replace("**", "")
                d("tr") > 0 -> s
                item -> "- $s"
                else -> s
            }
            when {
                heading > 0 -> lines.add(line)
                d("tr") > 0 -> cells.add(s)
                item -> { item = false; lines.add(line) }
                else -> lines.add(line)
            }
            if (sink != null) for ((h, a) in held) sink.add(Link(h, a, line))
            held.clear()
        }
        fun endRow() { flush(); if (cells.isNotEmpty()) { lines.add(cells.joinToString(" | ")); cells.clear() } }
        fun text(raw: String) {
            if (!reading()) return
            val t = WS.replace(INVISIBLE.replace(decode(raw), ""), " ")
            if (t.isBlank()) { if (block.isNotEmpty() && block[block.length - 1] != ' ') block.append(' '); return }
            if (lead == NONE) {
                if (d("b") + d("strong") > 0 && heading == 0) { block.setLength(0); block.append("**"); lead = OPEN; block.append(t.trimStart()); return }
                lead = DONE
                if (block.isBlank()) { block.setLength(0); block.append(t.trimStart()); return }
            }
            block.append(t)
        }
        fun opened(n: String) {
            when {
                n in HEADINGS -> { flush(); heading = n[1] - '0' }
                n == "li" -> { flush(); item = true }
                n == "tr" -> endRow()
                n == "br" -> if (block.isNotEmpty() && block[block.length - 1] != ' ') block.append(' ')
                n in BLOCK -> flush()
            }
        }
        fun closed(n: String) {
            when {
                n in HEADINGS -> { flush(); heading = 0 }
                n == "a" -> closeAnchor()
                n == "b" || n == "strong" -> if (d("b") + d("strong") == 0) closeLead()
                n == "li" -> { flush(); item = false }
                n == "tr" -> endRow()
                n in BLOCK -> flush()
            }
        }

        var i = 0
        val end = html.length
        while (i < end) {
            val lt = html.indexOf('<', i)
            if (lt < 0) { text(html.substring(i)); break }
            if (lt > i) text(html.substring(i, lt))
            if (html.startsWith("<!--", lt)) { val e = html.indexOf("-->", lt + 4); i = if (e < 0) end else e + 3; continue }
            val next = html.getOrNull(lt + 1)
            if (next == '!' || next == '?') { val e = html.indexOf('>', lt); i = if (e < 0) end else e + 1; continue }
            if (next == null || !(next.isLetter() || next == '/')) { text("<"); i = lt + 1; continue }
            // The tag runs to the first '>' outside a quoted attribute value.
            var j = lt + 1; var quote = ' '
            while (j < end) {
                val c = html[j]
                if (quote != ' ') { if (c == quote) quote = ' ' } else if (c == '"' || c == '\'') quote = c else if (c == '>') break
                j++
            }
            val tag = html.substring(lt + 1, minOf(j, end))
            i = if (j < end) j + 1 else end
            val closing = tag.startsWith("/")
            val name = NAME.find(tag.removePrefix("/"))?.value?.lowercase() ?: continue
            if (closing) {
                val at = stack.lastIndexOf(name)
                if (at >= 0) while (stack.size > at) { val m = stack.removeAt(stack.size - 1); depth[m] = d(m) - 1; closed(m) }
                continue
            }
            // Raw text elements: their content is not markup and holds no text of the page.
            if (name in RAW) {
                val close = html.indexOf("</$name", i, ignoreCase = true)
                i = if (close < 0) end else (html.indexOf('>', close).takeIf { it >= 0 }?.plus(1) ?: end)
                continue
            }
            val selfClosed = tag.endsWith("/")
            if (name in VOID || selfClosed) { if (name !in SKIP) opened(name); if (selfClosed && name !in VOID && name !in SKIP) closed(name); continue }
            if (name in SKIP) {
                // A skipped element's content is dropped whole, its nesting honoured.
                var k = i; var open = 1
                val tagAt = Regex("<(/?)$name(?=[\\s/>])", RegexOption.IGNORE_CASE)
                while (open > 0) {
                    val m = tagAt.find(html, k)
                    if (m == null) { k = end; break }
                    open += if (m.groupValues[1].isEmpty()) 1 else -1
                    k = html.indexOf('>', m.range.last).let { if (it < 0) end else it + 1 }
                }
                i = k
                continue
            }
            stack.add(name); depth[name] = d(name) + 1
            opened(name)
            if (name == "a" && sink != null && reading()) {
                closeAnchor()
                HREF.find(tag)?.let { m -> resolve(decode(m.groupValues[2].ifEmpty { m.groupValues[3] }).trim(), base) }
                    ?.let { href = it; from = block.length }
            }
        }
        endRow()
        if (title.isNotEmpty()) lines.add(0, "# $title")
        return lines.joinToString("\n\n")
    }

    /**
     * [href] as an address out of the page, or null for a place in the page itself (`#…`, or [base] with a fragment)
     * and for what is not a document (`javascript:`, `mailto:`, `tel:`, `data:`). Relative forms resolve against [base].
     */
    private fun resolve(href: String, base: String?): String? {
        if (href.isEmpty() || href.startsWith("#")) return null
        val scheme = SCHEME.find(href)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") return null
        val out = when {
            scheme != null -> href
            base == null -> return href
            href.startsWith("//") -> base.substringBefore(':') + ":" + href
            href.startsWith("/") -> ORIGIN.find(base)?.value?.plus(href) ?: href
            else -> base.substringBefore('#').substringBefore('?').substringBeforeLast('/') + "/" + href
        }
        return if (base != null && out.substringBefore('#') == base.substringBefore('#') && '#' in out) null else out
    }

    private const val NONE = 0
    private const val OPEN = 1
    private const val DONE = 2

    private val NAME = Regex("^[A-Za-z][A-Za-z0-9:-]*")
    private val HREF = Regex("\\shref\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val CANONICAL = Regex("<link\\b[^>]*\\brel=[\"']canonical[\"'][^>]*\\bhref=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val BASE = Regex("<base\\b[^>]*\\bhref=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val ORIGIN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]+")
    private val WS = Regex("[\\s\\u00A0\\u2002\\u2003\\u2009]+")
    private val INVISIBLE = Regex("[\\u00AD\\u200B\\u200C\\u200D\\u200E\\u200F\\uFEFF]")
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val RAW = setOf("script", "style", "textarea", "title", "xmp")
    private val SKIP = setOf("head", "noscript", "template", "svg", "math", "iframe", "object", "canvas", "video", "audio", "select", "button")
    private val NAV = setOf("nav", "aside")
    private val SITE = setOf("header", "footer")
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val BLOCK = setOf("address", "article", "aside", "blockquote", "caption", "dd", "details", "dialog", "div", "dl", "dt",
        "fieldset", "figcaption", "figure", "footer", "form", "header", "hgroup", "hr", "main", "nav", "ol", "p", "pre", "section",
        "summary", "table", "tbody", "td", "tfoot", "th", "thead", "ul")

    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ensp" to " ", "emsp" to " ",
        "thinsp" to " ", "ndash" to "–", "mdash" to "—", "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“",
        "rdquo" to "”", "bdquo" to "„", "hellip" to "…", "copy" to "©", "reg" to "®", "trade" to "™", "sect" to "§",
        "para" to "¶", "middot" to "·", "bull" to "•", "laquo" to "«", "raquo" to "»", "deg" to "°", "times" to "×",
        "divide" to "÷", "euro" to "€", "pound" to "£", "cent" to "¢", "yen" to "¥", "dagger" to "†", "Dagger" to "‡",
        "prime" to "′", "Prime" to "″", "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "shy" to "", "zwj" to "",
        "zwnj" to "", "lrm" to "", "rlm" to "")

    private fun decode(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
            else -> NAMED[e] ?: m.value
        }
    }

    private fun codePoint(c: Int): String = when {
        c < 0 || c > 0x10FFFF -> "\uFFFD"
        c < 0x10000 -> c.toChar().toString()
        else -> (c - 0x10000).let { charArrayOf(((it shr 10) + 0xD800).toChar(), ((it and 0x3FF) + 0xDC00).toChar()).concatToString() }
    }
}
