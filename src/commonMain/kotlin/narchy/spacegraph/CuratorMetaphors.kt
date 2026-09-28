package narchy.spacegraph

import narchy.spacegraph.graphics.spi.Rgba

/**
 * What each LCNC node is, told as a thing in a garden-workshop: the curator's statements become
 * presses, looms, star charts and oracles, rings become boughs, yields ripen as fruit. The table is
 * data: a type (or its dotted family) names its metaphor, the verb it does on begin, and what it
 * leaves on end. Unknown types are leaves.
 */
object CuratorMetaphors {
    enum class Shape { SEED, LEAF, BOUGH, FRUIT, STAR, PRISM, EYE, SCROLL, WHEEL, HARBOR }

    class Metaphor(val name: String, val begin: String, val end: String, val color: Rgba, val shape: Shape, val gloss: String)

    private val table: Map<String, Metaphor> = linkedMapOf(
        "program" to Metaphor("trunk", "wakes", "sleeps", Rgba(132, 104, 76), Shape.BOUGH,
            "A program is a tree: every run climbs its trunk into the same boughs, so a second production lights the paths the first one grew."),
        "scope" to Metaphor("bough", "unfurls", "rests", Rgba(94, 150, 104), Shape.BOUGH,
            "A ring is a bough: its statements grow on it as leaves, and what it yields ripens at its tip."),
        "scope.in" to Metaphor("root hair", "drinks", "has drunk", Rgba(150, 118, 84), Shape.SEED,
            "A binding reaches down into the enclosing soil for a value by name."),
        "scope.out" to Metaphor("fruit", "sets", "ripens", Rgba(232, 140, 64), Shape.FRUIT,
            "A yield: the one thing that leaves the ring."),
        "merge" to Metaphor("confluence", "gathers", "runs as one", Rgba(84, 166, 196), Shape.WHEEL,
            "Two branches that never both flow meet here; whichever ran fills the river."),
        "coalesce" to Metaphor("graft", "binds", "holds", Rgba(84, 166, 196), Shape.WHEEL,
            "The clarified stock grafted over the original."),
        "text" to Metaphor("seed", "is sown", "sprouts", Rgba(196, 184, 120), Shape.SEED, "A literal: the program's own seed."),
        "json" to Metaphor("seed", "is sown", "sprouts", Rgba(196, 184, 120), Shape.SEED, "A literal: the program's own seed."),
        "wiki.read" to Metaphor("library", "lends its shelves", "has lent", Rgba(160, 130, 196), Shape.SCROLL,
            "The wiki's pages come down off the shelf as one book."),
        "project.extract" to Metaphor("quarry", "cuts stone", "stone cut", Rgba(150, 150, 140), Shape.PRISM,
            "Raw document text hewn out of an ingested project."),
        "book.curate" to Metaphor("press", "sets type", "pages bound", Rgba(206, 172, 96), Shape.SCROLL,
            "Sections become typed norms: every clause set in SUMO-classed type."),
        "norm.clauses" to Metaphor("loom", "weaves clauses", "cloth off the loom", Rgba(200, 120, 150), Shape.WHEEL,
            "Modal sentences woven into bearer, force and predication."),
        "constellation.join" to Metaphor("star chart", "a book rises", "stars align", Rgba(236, 214, 120), Shape.STAR,
            "A book joins the sky: restated norms brighten, opposed ones cross, open premises glow as questions."),
        "constellation.ask" to Metaphor("oracle", "is asked", "speaks", Rgba(150, 196, 236), Shape.EYE,
            "What a SUMO class holds: said of it, or inherited down is-a and revised with its own voice."),
        "constellation.render" to Metaphor("echo", "speaks back", "hears itself", Rgba(120, 200, 190), Shape.EYE,
            "Norms read back aloud and re-parsed; a norm that returns changed is drift."),
        "constellation.settle" to Metaphor("tide", "ebbs", "settles", Rgba(90, 140, 210), Shape.HARBOR,
            "Answered questions wash off the board."),
        "constellation.brief" to Metaphor("herald", "unrolls a scroll", "proclaims", Rgba(226, 180, 90), Shape.SCROLL,
            "The frontier told as news: conflicts, open questions, restated norms."),
        "rdf" to Metaphor("prism", "splits the light", "casts a spectrum", Rgba(180, 150, 236), Shape.PRISM,
            "Triples bent through a SPARQL pattern into rows."),
        "nal" to Metaphor("scales", "weighs evidence", "comes to rest", Rgba(210, 200, 170), Shape.WHEEL,
            "NARS truth: frequency and confidence from counted sources."),
        "nl" to Metaphor("scales", "weighs evidence", "comes to rest", Rgba(210, 200, 170), Shape.WHEEL,
            "Sentences to evidence to eternal rules."),
        "kanban" to Metaphor("harbor", "a ship docks", "manifest filed", Rgba(110, 160, 200), Shape.HARBOR,
            "Work leaves the garden as cards on the board."),
        "board" to Metaphor("harbor master", "reads the manifest", "has read", Rgba(110, 160, 200), Shape.HARBOR,
            "The board as it stands."),
        "wiki.propose" to Metaphor("scribe", "drafts", "seals", Rgba(236, 150, 110), Shape.SCROLL,
            "A model reads the wiki and proposes one skill; the only statement here that spends tokens."),
        "display" to Metaphor("window", "opens", "shows", Rgba(120, 130, 150), Shape.EYE, "A value put on view."),
        "book.section" to Metaphor("page", "is read", "is read", Rgba(206, 172, 96), Shape.SCROLL,
            "One section of a book, cut where the book's own numbering says; its statements hang from it."),
        "book.statement" to Metaphor("clause", "is said", "is said", Rgba(245, 214, 140), Shape.LEAF,
            "One statement: a bearer, a force, a predicate, an object. Its arcs run to the concepts it relates."),
        "concept" to Metaphor("atom", "is named", "is named", Rgba(120, 190, 240), Shape.FRUIT,
            "A concept the text rests on, filed under its SUMO class; it grows with every statement that names it."),
    )

    val default = Metaphor("leaf", "buds", "unfolds", Rgba(120, 180, 120), Shape.LEAF, "A statement.")

    /** The metaphor of [type]: exact, then its dotted family (`constellation.join` → `constellation`), then a leaf. */
    fun of(type: String): Metaphor {
        table[type]?.let { return it }
        var t = type
        while ('.' in t) { t = t.substringBeforeLast('.'); table[t]?.let { return it } }
        return default
    }
}
