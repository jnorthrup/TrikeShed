package borg.trikeshed.wiki

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.reify

/** Curation identities projected into wiki paths. Only the selected section opens source and readings. */
class CurationWiki(val nodes: ConstellationNodes) {
    fun pages(selected: String?, articles: Series<WikiArticle> = emptySeriesOf()): Series<WikiArticle> {
        val membership = checkNotNull(nodes.root.listFiles { file -> file.isFile && file.name.endsWith(".members") }) {
            "The retained library cannot be listed"
        }
        val constellations = membership.sortedBy { it.name }.associate { file ->
            file.name.removeSuffix(".members").let { it to nodes.members(it) }
        }
        val names = constellations.values.flatten().distinct().sorted()
        val books = names.map { name ->
            val owner = nodes.forBook(name)
            requireNotNull(owner.load(name)) { "Retained library book '$name' is unavailable" } j owner
        }.toSeries()
        val stored = articles.view.associateBy { it.path }
        val pages = LinkedHashMap<String, WikiArticle>()
        fun project(path: String, text: String, kind: WikiKind) {
            val source = stored[path]
            val body = if (source == null) text else buildString {
                append(text).append("\n## ").append(if (kind == WikiKind.CONSTELLATION) "Saved summary" else "Wiki source")
                    .append("\n\n[Raw source](/api/wiki/read?path=").append(WikiSurface.encode(path)).append(")\n\n")
                // The page already has its title; the unchanged Markdown remains at the raw-source URL.
                val heading = source.text.lineSequence().firstOrNull().orEmpty()
                append(if (heading.startsWith("# ") && WikiSurface.title(source) == WikiSurface.plain(text.lineSequence().first().removePrefix("# ")))
                    source.text.removePrefix(heading).trimStart('\r', '\n') else source.text)
            }
            pages[path] = article(path, body, kind)
        }
        project(ROOT, buildString {
            append("# Library\n\nDocument curations, organized by constellation, book and section.\n\n")
            if (constellations.isEmpty()) append("No retained constellations are available.\n")
            for ((name, members) in constellations) append("- ").append(link(name, constellationPath(name)))
                .append(" — ").append(members.size).append(" books\n")
        }, WikiKind.LIBRARY)
        for ((name, members) in constellations) project(constellationPath(name), buildString {
            append("# Constellation ").append(literal(name)).append("\n\n").append(link("Library", ROOT)).append("\n\n")
            append("## Books\n\n")
            if (members.isEmpty()) append("No retained books are available.\n")
            for (member in members) append("- ").append(link(member, bookPath(member))).append('\n')
        }, WikiKind.CONSTELLATION)
        for ((book, owner) in books.view) {
            val cites = book.cites.groupBy({ it.first }, { it.second })
            val citedBy = book.cites.groupBy({ it.second }, { it.first })
            project(bookPath(book.name), buildString {
                append("# ").append(literal(book.name)).append("\n\n").append(link("Library", ROOT)).append("\n\n")
                append("Constellations: ").append(constellations.entries.filter { book.name in it.value }
                    .joinToString(" · ") { link(it.key, constellationPath(it.key)) }).append("\n\n")
                append("Work: ").append(literal(book.work)).append("\n\nDate: ").append(literal(book.date)).append("\n\n")
                append("## Contents\n\n")
                for (ordinal in book.headings.indices) append("- ").append(link("${ordinal + 1}. ${book.headings[ordinal]}", sectionPath(book.name, ordinal))).append('\n')
                append("\nThis page is a projection of retained book metadata; its content identifier identifies this projection.\n")
            }, WikiKind.BOOK)
            for (ordinal in book.headings.indices) {
                val path = sectionPath(book.name, ordinal)
                val body = buildString {
                    append("# ").append(literal(book.headings[ordinal])).append("\n\n")
                    append(link(book.name, bookPath(book.name))).append(" · Section ").append(ordinal + 1)
                        .append(" of ").append(book.headings.size).append("\n\n")
                    if (ordinal > 0) append(link("Previous section", sectionPath(book.name, ordinal - 1))).append("\n\n")
                    if (ordinal + 1 < book.headings.size) append(link("Next section", sectionPath(book.name, ordinal + 1))).append("\n\n")
                    fun citations(title: String, ordinals: List<Int>) {
                        if (ordinals.isEmpty()) return
                        append("## ").append(title).append("\n\n")
                        for (other in ordinals.distinct()) {
                            require(other in book.headings.indices) { "Invalid retained citation in '${book.name}': $other" }
                            append("- ").append(link(book.headings[other], sectionPath(book.name, other))).append('\n')
                        }
                        append('\n')
                    }
                    citations("Cites", cites[ordinal].orEmpty())
                    citations("Cited by", citedBy[ordinal].orEmpty())
                    if (selected == path) append(section(owner, book, ordinal))
                    else append("Open this section to read its retained source and readings.\n")
                }
                project(path, body, WikiKind.SECTION)
            }
        }
        for ((path, article) in stored) pages.putIfAbsent(path, article)
        return pages.values.toSeries()
    }

    private fun section(owner: ConstellationNodes, book: Book, ordinal: Int): String = buildString {
        append("## Source\n\n")
        val source = owner.texts(book.name).getOrNull(ordinal)
        if (source == null) append("No retained source text is available for this section.\n\n")
        else append(fenced(source)).append('\n')
        append("This page is a projection of retained source and curation records. Its content identifier identifies the projection, not the original document.\n\n")
        val statements = book.statements.indices.filter { ordinal in book.support[it] }
        if (statements.isNotEmpty()) {
            append("## Statements\n\n")
            for (index in statements) append("- ").append(literal(book.statements[index].sentence)).append('\n')
            append('\n')
        }
        val reading = owner.readingFile(book.name, ordinal)
        if (!reading.exists()) {
            append("## Readings\n\nNo retained sentence reading is available for this section.\n")
            return@buildString
        }
        val sentences = requireNotNull(reify(reading.readBytes()) as? List<*>) { "Invalid retained reading: ${book.name} section $ordinal" }
            .map { requireNotNull(it as? Map<*, *>) { "Invalid retained sentence: ${book.name} section $ordinal" } }
        fun terms(sentence: Map<*, *>): List<Map<*, *>> = (sentence["concepts"] as? List<*>).orEmpty()
            .map { requireNotNull(it as? Map<*, *>) { "Invalid retained concept: ${book.name} section $ordinal" } }
        fun key(concept: Map<*, *>): String = concept["lemma"].toString() + '\u0000' + concept["class"].toString()
        val concepts = sentences.flatMap(::terms).filter { it["lemma"] is String }.distinctBy(::key)
        val conceptIds = concepts.mapIndexed { index, concept -> key(concept) to index + 1 }.toMap()
        val classes = concepts.mapNotNull { it["class"] as? String }.distinct()
        val classIds = classes.mapIndexed { index, name -> name to index + 1 }.toMap()
        val occurrences = LinkedHashMap<String, MutableList<Int>>()
        append("## Sentences\n\n")
        if (sentences.isEmpty()) append("The retained reading contains no sentences.\n\n")
        for ((index, sentence) in sentences.withIndex()) {
            append("### Sentence ").append(index + 1).append("\n\n").append(fenced(sentence["text"]?.toString().orEmpty())).append('\n')
            val terms = terms(sentence)
            val seen = HashSet<String>()
            for (concept in terms) {
                val key = key(concept)
                if (key in conceptIds && seen.add(key)) occurrences.getOrPut(key) { mutableListOf() }.add(index)
            }
            val references = terms.mapNotNull { concept -> conceptIds[key(concept)]?.let { id ->
                link(concept["lemma"].toString(), "#Concept_$id")
            } }.distinct()
            if (references.isNotEmpty()) append("Concepts: ").append(references.joinToString(" · ")).append("\n\n")
            for (statement in (sentence["statements"] as? List<*>).orEmpty()) {
                val fields = requireNotNull(statement as? Map<*, *>) { "Invalid retained statement: ${book.name} section $ordinal" }
                append("- ").append(literal(s_["bearer", "force", "action", "object", "condition"].view
                    .mapNotNull { fields[it]?.toString() }.joinToString(" · "))).append('\n')
            }
            append('\n')
        }
        if (concepts.isNotEmpty()) append("## Concepts\n\n")
        for ((index, concept) in concepts.withIndex()) {
            append("### Concept ").append(index + 1).append("\n\n").append(literal(concept["lemma"].toString())).append("\n\n")
            val cls = concept["class"] as? String
            if (cls != null) append("Class: ").append(link(cls, "#Class_${classIds.getValue(cls)}")).append("\n\n")
            else append("No class is recorded.\n\n")
            val ancestors = (concept["is"] as? List<*>).orEmpty()
            if (ancestors.isNotEmpty()) append("Recorded ancestors: ").append(literal(ancestors.joinToString(" → "))).append("\n\n")
            append("Occurs in: ").append(occurrences.getValue(key(concept))
                .joinToString(" · ") { link("Sentence ${it + 1}", "#Sentence_${it + 1}") }).append("\n\n")
        }
        if (classes.isNotEmpty()) append("## Classes\n\n")
        for ((index, cls) in classes.withIndex()) {
            append("### Class ").append(index + 1).append("\n\n").append(literal(cls)).append("\n\n")
            append(concepts.mapIndexedNotNull { conceptIndex, concept ->
                if (concept["class"] == cls) link(concept["lemma"].toString(), "#Concept_${conceptIndex + 1}") else null
            }.joinToString(" · ")).append("\n\n")
        }
    }

    companion object {
        const val ROOT = "library/index.md"
        // Same identity used by constellation.brief(page=true); the saved summary belongs to this page.
        fun constellationPath(name: String): String = "patterns/constellation-$name.md"
        fun bookPath(book: String): String = "library/${WikiSurface.encode(book)}/index.md"
        fun sectionPath(book: String, ordinal: Int): String = "library/${WikiSurface.encode(book)}/$ordinal.md"
        private fun article(path: String, text: String, kind: WikiKind) = WikiArticle(path, text, ContentId.of(text.encodeToByteArray()).value, kind)
        private fun literal(value: String): String = buildString {
            for (char in value) when {
                char == '\n' || char == '\r' -> append(' ')
                char in "\\`*_{}[]()#+-.!>" -> append('\\').append(char)
                else -> append(char)
            }
        }
        private fun link(label: String, path: String): String =
            "[${literal(label)}](" + (if (path.startsWith('#')) path else "/" + WikiSurface.encode(path)) + ")"
        private fun fenced(value: String): String {
            var delimiter = "```"
            while (value.contains(delimiter)) delimiter += '`'
            return "$delimiter\n$value\n$delimiter\n"
        }
    }
}
