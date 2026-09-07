package borg.trikeshed.forge.concept

/**
 * DocsGraph — the Forge mindmap of `docs/`, drawn the way [ConceptGraph] draws the code.
 *
 * `docs/` is the published site: 31 markdown files sitting next to `index.html`, and until now
 * nothing in the site referenced a single one of them. The corpus already IS a graph — the docs
 * cross-link each other 43 times — so the map does not have to be invented, only read. That is
 * the difference between this and [ConceptGraph]: the concept lattice is hand-written and must be
 * maintained by hand (its test checks every cited file still exists), while this one is DERIVED,
 * so it cannot describe a doc that was deleted or miss one that was added.
 *
 * NO IO HERE, deliberately. Parsing takes [DocSource] values so the whole map is a pure function
 * over text: the JVM baker reads the directory, the browser gets the result in the seed, and a
 * test can hand it three strings and assert the shape. commonMain never learns what a file is.
 *
 * Layers run from the most normative to the least — an index points at guides, guides cite specs,
 * specs are pinned by contracts, contracts are argued in plans, plans are answered by analyses,
 * and everything else is a note. Edges are the corpus's own links, so a doc's column says what
 * KIND of thing it is and its edges say what it actually leans on.
 */
data class DocSource(val path: String, val text: String)

object DocsGraph {

    /** Layer order = column order, most normative first. */
    val LAYERS: List<String> = listOf("index", "guide", "spec", "contract", "plan", "analysis", "note")

    /** Where the corpus lives relative to the repository root, and the site root it is served at. */
    const val ROOT: String = "docs/"

    private val LINK = Regex("""\]\(\s*<?([^)<>\s]+?\.md)(?:#[^)\s]*)?>?\s*\)""", RegexOption.IGNORE_CASE)
    private val H1 = Regex("""^#\s+(.+?)\s*$""")
    private val HEADING = Regex("""^#{1,6}\s+""")

    /** `docs/guide-kanban-board.md` -> `guide-kanban-board`. Also the node id. */
    fun idOf(path: String): String =
        path.substringAfterLast('/').removeSuffix(".md").removeSuffix(".MD")

    /**
     * Which column a document belongs in, from its name.
     *
     * Name-derived rather than front-matter-derived because the corpus has no front matter and
     * inventing one would mean editing 31 files to draw a picture of them. The order of the
     * checks is the precedence: `guides-index` is an index before it is a guide, and
     * `oroboros-gap-analysis-…` is an analysis before its `oroboros-` prefix means anything.
     */
    fun layerOf(id: String): String {
        val n = id.lowercase()
        return when {
            n.endsWith("-index") || n == "index" || n.startsWith("index-") -> "index"
            "gap-analysis" in n || "analysis" in n || "audit" in n -> "analysis"
            n.startsWith("guide-") || n.startsWith("guides") -> "guide"
            "spec" in n -> "spec"
            "contract" in n || "covenant" in n || n == "gates" -> "contract"
            "plan" in n || "roadmap" in n -> "plan"
            else -> "note"
        }
    }

    /** The `# Title` line, or the id spelled as words when a document has no heading. */
    fun titleOf(source: DocSource): String {
        for (line in source.text.lineSequence()) {
            H1.find(line)?.let { return it.groupValues[1].trim() }
        }
        return idOf(source.path).replace('-', ' ').replace('_', ' ')
    }

    /**
     * The first prose sentence, as the inspector's one-line gloss.
     *
     * Headings, code fences, quotes, lists and tables are skipped: the useful line is the one a
     * human wrote to say what the document is, and it is never the table of contents.
     */
    fun glossOf(source: DocSource, limit: Int = 160): String {
        var fenced = false
        for (raw in source.text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("```")) { fenced = !fenced; continue }
            if (fenced || line.isEmpty()) continue
            if (HEADING.containsMatchIn(line)) continue
            if (line.startsWith(">") || line.startsWith("|") || line.startsWith("-") ||
                line.startsWith("*") || line.startsWith("<")
            ) continue
            val clean = line.replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1").replace("`", "")
            return if (clean.length <= limit) clean else clean.take(limit - 1).trimEnd() + "…"
        }
        return ""
    }

    /** Every `.md` this document links to, as node ids, in first-seen order and deduplicated. */
    fun linksOf(source: DocSource): List<String> =
        LINK.findAll(source.text).map { idOf(it.groupValues[1]) }.distinct().toList()

    /** The nodes for a corpus, in layer order then title order, so the picture is stable. */
    fun nodesOf(sources: List<DocSource>): List<LayeredNode> =
        sources.map { s ->
            LayeredNode(
                id = idOf(s.path),
                title = titleOf(s),
                layer = layerOf(idOf(s.path)),
                symbol = glossOf(s),
                file = s.path,
            )
        }.sortedWith(compareBy({ LAYERS.indexOf(it.layer) }, { it.title.lowercase() }))

    /**
     * The edges for a corpus: one per cross-reference that resolves to another document here.
     *
     * A link to a document outside the corpus is dropped rather than drawn to a stub, because a
     * map of `docs/` that invents nodes for `../README.md` is no longer a map of `docs/`. A
     * document's link to itself is dropped too — a self-loop renders as a dot and means nothing.
     */
    fun edgesOf(sources: List<DocSource>): List<LayeredEdge> {
        val known = sources.map { idOf(it.path) }.toSet()
        val seen = mutableSetOf<Pair<String, String>>()
        val out = mutableListOf<LayeredEdge>()
        for (s in sources) {
            val from = idOf(s.path)
            for (to in linksOf(s)) {
                if (to == from || to !in known) continue
                if (!seen.add(from to to)) continue
                out += LayeredEdge(from, to, "cites")
            }
        }
        return out
    }

    /** The seed the graph view reads, in the same shape as [ConceptGraph.layoutSeed]. */
    fun layoutSeed(sources: List<DocSource>): Map<String, Any?> =
        layeredLayoutSeed(nodesOf(sources), edgesOf(sources), LAYERS)

    /** Documents nothing links to and which link to nothing — the corpus's loose ends. */
    fun orphans(sources: List<DocSource>): List<String> {
        val touched = edgesOf(sources).flatMap { listOf(it.from, it.to) }.toSet()
        return sources.map { idOf(it.path) }.filter { it !in touched }.sorted()
    }
}
