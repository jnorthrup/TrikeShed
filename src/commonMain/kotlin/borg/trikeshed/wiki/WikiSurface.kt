package borg.trikeshed.wiki

import borg.trikeshed.docs.DocumentMarkdown
import borg.trikeshed.lib.*

enum class WikiKind { FILE, LIBRARY, CONSTELLATION, BOOK, SECTION }
data class WikiArticle(val path: String, val text: String, val cid: String, val kind: WikiKind = WikiKind.FILE)
data class WikiSection(val level: Int, val title: String, val anchor: String)
data class WikiContent(val html: String, val sections: Series<WikiSection>, val links: Series<String>)
data class WikiLink(val path: String, val fragment: String, val article: WikiArticle?)

/** Reading projection of the wiki plane; page identity remains its relative Markdown path. */
object WikiSurface {
    fun render(
        articles: Series<WikiArticle>,
        path: String? = null,
        source: Boolean = false,
        unavailable: String? = null,
        truncated: Boolean = false,
    ): String {
        val index = articles.view.associateBy { it.path }
        val article = index[path]
        val title = article?.let(::title) ?: path?.let(::filename) ?: "Curations"
        val rendered = article?.let { content(it, articles, index) }
        return buildString {
            append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>")
            append(escape(title)).append(" · TrikeShed</title>")
            append("<link rel=\"stylesheet\" href=\"/application-nav.css\"><link rel=\"stylesheet\" href=\"/wiki.css\">")
            append("<script defer src=\"/kotlin/pages/pages.js\"></script></head><body data-app-page=\"wiki\">")
            append("<div class=\"wiki-shell\"><aside class=\"wiki-sidebar\"><a class=\"wiki-brand\" href=\"/wiki\">Curations</a>")
            append("<p class=\"wiki-kicker\">The wiki</p><nav aria-label=\"Wiki navigation\"><ul class=\"wiki-tools\">")
            append("<li><a href=\"/wiki\">All pages</a></li><li><a href=\"/documents#curation\">Document curations</a></li>")
            append("<li><a href=\"/curator\">Curator</a></li></ul></nav></aside><main class=\"wiki-main\">")
            append("<header><p class=\"wiki-kicker\">Curations</p><h1 class=\"wiki-title\">")
            append(escape(title)).append("</h1></header>")
            if (unavailable != null) {
                append("<p class=\"wiki-notice\" role=\"status\">Wiki unavailable: ").append(escape(unavailable)).append("</p>")
            }
            if (truncated) append("<p class=\"wiki-notice\">This page index is incomplete. Some pages could not be loaded; links and backlinks reflect only the available pages.</p>")
            when {
                path == null -> {
                    append("<p>Open a constellation to browse its books, then open a section to read its sources and curations. Authored patterns and skills are listed here too.</p>")
                    if (articles.size == 0) {
                        if (unavailable == null) append("<p class=\"wiki-empty\">No wiki pages are available yet.</p>")
                    } else {
                        val constellations = articles.view.any { it.kind == WikiKind.CONSTELLATION }
                        append("<h2>Browse curations</h2><ul class=\"wiki-list\">")
                        for (item in articles.view) {
                            when (item.kind) {
                                WikiKind.FILE, WikiKind.CONSTELLATION -> Unit
                                WikiKind.LIBRARY -> if (constellations) continue
                                else -> continue
                            }
                            append("<li><a href=\"").append(url(item.path)).append("\">").append(escape(title(item))).append("</a>")
                            append(" <span class=\"wiki-path\">").append(escape(item.path)).append("</span></li>")
                        }
                        append("</ul>")
                    }
                }
                article == null -> {
                    append("<p class=\"wiki-empty\">This page is not available in the wiki.</p><p class=\"wiki-path\">")
                    append(escape(path)).append("</p><p><a href=\"/wiki\">Browse all pages</a></p>")
                }
                else -> {
                    append("<nav class=\"wiki-tabs\" aria-label=\"Page views\"><a href=\"").append(url(path)).append('"')
                    if (!source) append(" aria-current=\"page\"")
                    append(">Read</a><a href=\"").append(url(path)).append("&amp;source=1\"")
                    if (source) append(" aria-current=\"page\"")
                    append(">View source</a>")
                    if (article.kind == WikiKind.FILE) append("<a href=\"/api/wiki/read?path=").append(encode(path)).append("\">Raw source</a>")
                    append("</nav>")
                    append("<p class=\"wiki-meta\"><span class=\"wiki-path\">").append(escape(path)).append("</span>")
                    if (article.cid.isNotEmpty()) append(" · <span title=\"Content identifier\">").append(escape(article.cid)).append("</span>")
                    append("</p>")
                    if (source) append("<pre class=\"wiki-source\"><code>").append(escape(article.text)).append("</code></pre>")
                    else if (article.text.isBlank()) append("<p class=\"wiki-empty\">This page has no content yet.</p>")
                    else {
                        if (rendered!!.sections.size > 0) {
                            append("<nav class=\"wiki-toc\" aria-label=\"Contents\"><h2>Contents</h2><ol>")
                            for (section in rendered.sections.view) {
                                append("<li data-level=\"").append(section.level).append("\"><a href=\"#")
                                append(encode(section.anchor)).append("\">").append(escape(section.title)).append("</a></li>")
                            }
                            append("</ol></nav>")
                        }
                        append("<article class=\"wiki-article\">").append(rendered.html).append("</article>")
                    }
                    append("<section class=\"wiki-backlinks\" aria-label=\"What links here\"><h2>What links here</h2>")
                    var linked = false
                    for (item in articles.view) {
                        if (item.path == path || !content(item, articles, index).links.view.contains(path)) continue
                        if (!linked) append("<ul>")
                        linked = true
                        append("<li><a href=\"").append(url(item.path)).append("\">").append(escape(title(item))).append("</a></li>")
                    }
                    if (linked) append("</ul>") else append("<p>No available pages link here.</p>")
                    append("</section>")
                }
            }
            append("<footer class=\"wiki-meta\"><a href=\"https://www.mediawiki.org/wiki/Help:Links\">Link conventions</a></footer>")
            append("</main></div></body></html>")
        }
    }

    fun title(article: WikiArticle): String {
        var fence = false
        for (line in article.text.lineSequence()) {
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) fence = !fence
            else if (!fence && line.startsWith("# ")) {
                return line.removePrefix("# ").trim().replace(Regex("\\s+#+$"), "").trim().takeIf { it.isNotEmpty() }
                    ?.let(::plain) ?: filename(article.path)
            }
        }
        return filename(article.path)
    }

    fun filename(path: String): String = path.substringAfterLast('/').removeSuffix(".md").replace('_', ' ')
    fun escape(value: String): String = DocumentMarkdown.escape(value)
    fun url(path: String): String = "/wiki?page=" + encode(path)

    fun encode(value: String): String = buildString {
        val hex = "0123456789ABCDEF"
        for (byte in value.encodeToByteArray()) {
            val n = byte.toInt() and 255
            if (n in 65..90 || n in 97..122 || n in 48..57 || n == 45 || n == 46 || n == 95 || n == 126) append(n.toChar())
            else append('%').append(hex[n shr 4]).append(hex[n and 15])
        }
    }

    /** Protect inline literals and links before the shared Markdown block renderer runs. */
    fun content(article: WikiArticle, articles: Series<WikiArticle>, index: Map<String, WikiArticle>? = null): WikiContent {
        val substitutions = mutableListOf<String>()
        val blocks = mutableSetOf<Int>()
        val links = mutableListOf<String>()
        val sections = mutableListOf<WikiSection>()
        var marker = "WIKISUBSTITUTION"
        while (article.text.contains(marker)) marker += "X"
        fun token(html: String): String {
            val id = substitutions.size
            substitutions.add(html)
            return marker + id + "END"
        }
        fun link(target: String, label: String, wiki: Boolean): String {
            val text = escape(if (wiki) label else unescape(label))
            if (!wiki && SCHEME.containsMatchIn(target)) {
                return if (DocumentMarkdown.safeTarget(target) && target.none { it.code < 32 || it == '\\' })
                    token("<a class=\"wiki-external\" href=\"${escape(target)}\">$text</a>")
                else token(text + " (" + escape(target) + ")")
            }
            if (!wiki && target.startsWith('/') && !target.startsWith("//") && !target.substringBefore('#').substringBefore('?').endsWith(".md")) {
                return if (target.none { it.isWhitespace() || it.code < 32 || it == '\\' || it == '"' })
                    token("<a href=\"${escape(target)}\">$text</a>") else token(text)
            }
            if (!wiki && !target.startsWith('#') && !target.substringBefore('#').endsWith(".md") &&
                target.substringBefore('#').substringAfterLast('/').contains('.')) {
                val attachment = path(article.path.substringBeforeLast('/', "").let {
                    if (it.isEmpty()) decode(target.substringBefore('#')) else "$it/${decode(target.substringBefore('#'))}"
                })
                return if (attachment != null && DocumentMarkdown.safeTarget(target) && !target.startsWith("//") && target.none { it.code < 32 || it == '\\' })
                    token("<a href=\"/api/wiki/read?path=${encode(attachment)}\">$text</a>") else token(text)
            }
            val resolved = resolve(target, article, articles, wiki, index)
            if (resolved == null) return token(text)
            if (resolved.article != null && resolved.path !in links) links.add(resolved.path)
            val href = (if (resolved.path == article.path && target.startsWith('#')) "" else url(resolved.path)) +
                if (resolved.fragment.isEmpty()) "" else "#" + encode(anchor(resolved.fragment))
            val state = if (resolved.article == null) " class=\"wiki-missing\" title=\"Page not available\"" else ""
            return token("<a href=\"${escape(href)}\"$state>$text</a>")
        }
        fun inline(line: String): String = buildString {
            var offset = 0
            while (offset < line.length) {
                if (line[offset] == '\\' && offset + 1 < line.length && line[offset + 1] in PUNCTUATION) {
                    append(token(escape(line.substring(offset + 1, offset + 2))))
                    offset += 2
                    continue
                }
                if (line[offset] == '`') {
                    val end = (offset until line.length).firstOrNull { line[it] != '`' } ?: line.length
                    val delimiter = line.substring(offset, end)
                    val close = line.indexOf(delimiter, end)
                    if (close >= 0) {
                        append(token("<code>" + escape(line.substring(end, close)) + "</code>"))
                        offset = close + delimiter.length
                        continue
                    }
                }
                if (line.startsWith("[[", offset)) {
                    val close = line.indexOf("]]", offset + 2)
                    if (close >= 0) {
                        val value = line.substring(offset + 2, close)
                        val target = value.substringBefore('|').trim()
                        append(link(target, value.substringAfter('|', target).replace('_', ' '), true))
                        offset = close + 2
                        continue
                    }
                }
                if (line[offset] == '[' && (offset == 0 || line[offset - 1] != '!')) {
                    var labelEnd = offset + 1
                    var brackets = 1
                    while (labelEnd < line.length) {
                        if (line[labelEnd] == '\\' && labelEnd + 1 < line.length && line[labelEnd + 1] in PUNCTUATION) {
                            labelEnd += 2
                            continue
                        }
                        if (line[labelEnd] == '[') brackets++
                        else if (line[labelEnd] == ']') brackets--
                        if (brackets == 0) break
                        labelEnd++
                    }
                    if (labelEnd + 1 < line.length && line[labelEnd + 1] == '(') {
                        var end = labelEnd + 2
                        var depth = 1
                        while (end < line.length && depth > 0) {
                            if (line[end] == '\\' && end + 1 < line.length && line[end + 1] in PUNCTUATION) {
                                end += 2
                                continue
                            }
                            if (line[end] == '(') depth++ else if (line[end] == ')') depth--
                            end++
                        }
                        if (depth == 0) {
                            append(link(unescape(line.substring(labelEnd + 2, end - 1).trim()), line.substring(offset + 1, labelEnd), false))
                            offset = end
                            continue
                        }
                    }
                }
                append(line[offset++])
            }
        }
        val lines = article.text.replace("\r\n", "\n").lineSequence().iterator()
        val markdown = buildString {
            val paragraph = StringBuilder()
            fun flush() { append(inline(paragraph.toString())); paragraph.clear() }
            while (lines.hasNext()) {
                val line = lines.next()
                val prefix = QUOTE_PREFIX.find(line)!!.value
                val block = line.removePrefix(prefix).trimStart()
                val fence = FENCE.matchEntire(block)
                if (fence == null) { paragraph.append(line).append('\n'); continue }
                flush()
                val delimiter = fence.groupValues[1]
                val language = fence.groupValues[2].trim()
                val code = StringBuilder()
                while (lines.hasNext()) {
                    val body = lines.next().let { row ->
                        var unquoted = row
                        repeat(prefix.count { it == '>' }) { unquoted = unquoted.trimStart().removePrefix(">").removePrefix(" ") }
                        unquoted
                    }
                    val close = body.trim()
                    if (close.length >= delimiter.length && close.all { it == delimiter[0] }) break
                    code.append(body).append('\n')
                }
                val css = if (language.isNotEmpty() && language.all { it.isLetterOrDigit() || it in "-_+" })
                    " class=\"language-${escape(language)}\"" else ""
                blocks.add(substitutions.size)
                append('\n').append(prefix).append(token("<pre><code$css>${escape(code.toString())}</code></pre>"))
                    .append('\n').append('\n')
            }
            flush()
        }
        var html = DocumentMarkdown.render(markdown)
        html = Regex("<p>" + marker + "([0-9]+)END</p>").replace(html) { match ->
            if (match.groupValues[1].toInt() in blocks) marker + match.groupValues[1] + "END" else match.value
        }
        html = Regex(marker + "([0-9]+)END").replace(html) { substitutions[it.groupValues[1].toInt()] }
        val anchors = mutableSetOf<String>()
        html = HEADING_HTML.replace(html) { match ->
            val heading = plainHtml(match.groupValues[2])
            val base = anchor(heading)
            var count = 1
            var id = base
            while (!anchors.add(id)) { count++; id = "${base}_$count" }
            val level = match.groupValues[1].toInt()
            if (level == 1 && html.substring(0, match.range.first).isBlank() && heading == title(article)) "<span id=\"${escape(id)}\"></span>"
            else {
                sections.add(WikiSection(level, heading, id))
                "<h$level id=\"${escape(id)}\">${match.groupValues[2]}<a class=\"wiki-section-link\" href=\"#${encode(id)}\" aria-label=\"Link to section\">§</a></h$level>"
            }
        }
        return WikiContent(html, sections.toSeries(), links.toSeries())
    }

    fun resolve(target: String, source: WikiArticle, articles: Series<WikiArticle>, wiki: Boolean = true,
                index: Map<String, WikiArticle>? = null): WikiLink? {
        val name = decode(target.substringBefore('#')).trim()
        val fragment = decode(target.substringAfter('#', ""))
        if (name.isEmpty()) return WikiLink(source.path, fragment, source)
        if (name.any { it.code < 32 || it == '\\' || it == '?' } || name.startsWith("//")) return null
        val relative = source.path.substringBeforeLast('/', "").let { if (it.isEmpty() || name.startsWith('/')) name else "$it/$name" }
        val local = path(relative) ?: return null
        val root = path(name.removePrefix("/")) ?: local
        fun pagePath(value: String) = if (value.endsWith(".md")) value else "$value.md"
        val localPage = pagePath(local)
        val rootPage = pagePath(root)
        val exact = if (index != null) index[localPage] ?: index[rootPage]
            else articles.view.firstOrNull { it.path == localPage } ?: articles.view.firstOrNull { it.path == rootPage }
        if (exact != null) return WikiLink(exact.path, fragment, exact)
        val keyed = articles.view.singleOrNull {
            key(it.path) == key(localPage) || key(it.path) == key(rootPage) ||
                (!name.contains('/') && (key(filename(it.path)) == key(filename(name)) || key(title(it)) == key(name.removeSuffix(".md"))))
        }
        return WikiLink(keyed?.path ?: if (wiki) rootPage else localPage, fragment, keyed)
    }

    fun path(value: String): String? {
        val parts = mutableListOf<String>()
        for (part in value.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        }
        return parts.joinToString("/").takeIf { it.isNotEmpty() }
    }

    fun key(value: String): String = value.replace('_', ' ').let { it.take(1).lowercase() + it.drop(1) }
    fun anchor(value: String): String = value.trim().replace(Regex("\\s+"), "_").ifEmpty { "section" }
    fun unescape(value: String): String = buildString {
        var offset = 0
        while (offset < value.length) {
            if (value[offset] == '\\' && offset + 1 < value.length && value[offset + 1] in PUNCTUATION) offset++
            append(value[offset++])
        }
    }

    fun plain(value: String): String {
        val literals = mutableListOf<String>()
        var marker = "WIKITITLELITERAL"
        while (value.contains(marker)) marker += "X"
        val text = buildString {
            var offset = 0
            fun literal(text: String) { append(marker).append(literals.size).append("END"); literals.add(text) }
            while (offset < value.length) {
                if (value[offset] == '\\' && offset + 1 < value.length && value[offset + 1] in PUNCTUATION) {
                    literal(value.substring(offset + 1, offset + 2)); offset += 2; continue
                }
                if (value[offset] == '`') {
                    val end = (offset until value.length).firstOrNull { value[it] != '`' } ?: value.length
                    val delimiter = value.substring(offset, end)
                    val close = value.indexOf(delimiter, end)
                    if (close >= 0) { literal(value.substring(end, close)); offset = close + delimiter.length; continue }
                }
                append(value[offset++])
            }
        }
        return Regex(marker + "([0-9]+)END").replace(plainHtml(DocumentMarkdown.inline(text))) { literals[it.groupValues[1].toInt()] }
    }
    fun plainHtml(value: String): String = value.replace(Regex("<[^>]*>"), "")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    fun decode(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            if (value[i] == '%' && i + 2 < value.length && value.substring(i + 1, i + 3).toIntOrNull(16) != null) {
                val bytes = mutableListOf<Byte>()
                while (i + 2 < value.length && value[i] == '%') {
                    val byte = value.substring(i + 1, i + 3).toIntOrNull(16) ?: break
                    bytes.add(byte.toByte()); i += 3
                }
                append(bytes.toByteArray().decodeToString())
            } else append(value[i++])
        }
    }

    val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    const val PUNCTUATION = "\\`*_{}[]()#+-.!>"
    val QUOTE_PREFIX = Regex("^\\s*(?:>\\s*)*")
    val FENCE = Regex("^(`{3,}|~{3,})(.*)$")
    val HEADING_HTML = Regex("<h([1-6])>(.*?)</h[1-6]>")
}
