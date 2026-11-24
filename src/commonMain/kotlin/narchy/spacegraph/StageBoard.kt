package narchy.spacegraph

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import kotlin.math.*

/**
 * The `ingest.stages` board as a space graph: one board for each setting the stages read under, side by side.
 *
 * A board's stages are its rows, top to bottom in the order a text passes them, named as the rows' own `stages` name
 * them, so a stage the node gains shows here unchanged. A claim has a column. At each stage it has a circle of that
 * stage's evidence for it, green where the stage found the claim, red where the stage lost it, grey where the board
 * does not say. The claim itself rests on the circle of the stage blamed for it, or in the found row below the last
 * stage. Its disc is coloured by its author, a person or a model; its ring says whether the engine's answer (found at
 * every stage, or lost) agrees with the outcome its challenge knows, and is grey when no challenge knows one.
 *
 * The first board is the node's own reading; each further board is the same board under one variant (other generic
 * subjects, another parser). Where a claim rests at another stage under a variant, its disc on the first board is
 * linked to its disc there: the setting moves it.
 *
 * Evidence is a space graph of its own: a map or a list is a circle of circles, one for each part, packed by
 * [CirclePacking], and a part with parts opens the same way again, down to where a circle would be smaller than
 * [MIN_PX] at the scale the graph is built for. Rows report evidence for the node's own reading only: a variant's board
 * knows the stage blamed, and so that the stages before it passed.
 */
object StageBoard {
    /** World units: a claim's column, the radius of a stage's evidence for a claim, of a claim's ring and of its author's disc. */
    const val COLUMN = 120.0
    const val EVIDENCE = 44.0
    const val CLAIM = 17.0
    const val AUTHOR = 13.0
    /** A stage's row: its evidence circles with a claim resting on one. */
    const val ROW = 2 * EVIDENCE + 2 * CLAIM + 24.0
    /** A board's margin, where its stages are named; its head, where its setting and scores stand; its found row. */
    const val MARGIN = 150.0
    const val HEAD = 70.0
    const val FOUND = 2 * CLAIM + 34.0
    /** Between two boards; a line of the key. */
    const val GAP = 90.0
    const val LINE = 26.0
    /** The smallest circle drawn, in screen pixels: below it, its container carries it. */
    const val MIN_PX = 3.0

    /** Evidence: the stage found the claim, lost it, or the board does not say; evidence a stage lacked. */
    const val PASSED = "#bbf7d0"
    const val LOST = "#fecaca"
    const val UNREPORTED = "#e2e8f0"
    const val LACKING = "#fee2e2"
    /** A claim's ring: the engine's answer agrees with the known outcome, disagrees with it, or no challenge knows one. */
    const val AGREES = "#15803d"
    const val DISAGREES = "#b91c1c"
    const val UNKNOWN = "#94a3b8"
    /** A claim's disc: its author. */
    const val PERSON = "#2563eb"
    const val MODEL = "#d97706"
    /** A link between boards: a setting moves the claim. */
    const val MOVED = "#7c3aed"

    /**
     * A piece of evidence: a value, or a whole whose [parts] are packed by [CirclePacking] about its center, in its own
     * units, and set low in it so that the top of the whole is left to its own label.
     */
    class Part(val key: String, val text: String, val parts: List<Part>) {
        val x = DoubleArray(parts.size)
        val y = DoubleArray(parts.size)
        val radius: Double = if (parts.isEmpty()) (0.3 * text.length + 1.5).coerceIn(2.0, 9.0) else {
            val rim = CirclePacking.pack(DoubleArray(parts.size) { parts[it].radius + 0.5 }, x, y)
            for (i in y.indices) y[i] -= 0.38 * rim
            1.38 * rim + 1.0
        }
    }

    fun scalar(value: Any?) = value != null && value !is Map<*, *> && value !is List<*>
    fun word(value: Any?) = value is String || value is Number

    /** [value] under [key] as evidence: a map's entries and a list's elements are its parts, anything else a value. A part of a list is named by its words. */
    fun part(key: String, value: Any?): Part = when (value) {
        is Map<*, *> -> Part(key, key.ifEmpty { value.values.filter(::word).joinToString(" ") },
            value.entries.filter { it.value != null }.map { part(it.key.toString(), it.value) })
        is List<*> -> Part(key, if (key.isEmpty()) value.filter(::scalar).joinToString(" · ") else "$key (${value.size})", value.map { part("", it) })
        else -> Part(key, if (key.isEmpty()) value.toString() else "$key: $value", emptyList())
    }

    /**
     * [text] as a box [w] by [h] screen pixels shows it from its corner: in the font that fits it whole, down to 7 pixels,
     * then cut to the width; empty when less than a word of it fits.
     */
    fun label(text: String, w: Double, h: Double, largest: Double): Join<String, Double> {
        val tallest = min(largest, min(22.0, h - 3) / 0.9)
        val font = min(tallest, max(7.0, (w - 14) / (0.56 * text.length)))
        val chars = ((w - 14) / (0.56 * font)).toInt()
        if (text.isEmpty() || font < 5 || chars < min(text.length, 6)) return "" j 0.0
        return (if (text.length <= chars) text else text.take(chars - 1) + "…") j font
    }

    fun weight(x: Double) = (round(x * 100) / 100).toString()

    /** The stages output in [value]: [value] itself when it holds a board, else the output of a run record that does. */
    @Suppress("UNCHECKED_CAST")
    fun output(value: Any?): Map<String, Any?> {
        val m = value as? Map<*, *> ?: throw IllegalArgumentException("neither a stages output nor a run record")
        if (m["board"] is List<*>) return m as Map<String, Any?>
        val outputs = m["outputs"] as? Map<*, *> ?: throw IllegalArgumentException("no board and no outputs")
        return outputs.values.firstOrNull { (it as? Map<*, *>)?.get("board") is List<*> } as? Map<String, Any?>
            ?: throw IllegalArgumentException("no output holds a board")
    }

    /** The boards of [output], the stages node's `{board, blame, passage}`, against [challenges], drawn to the depth [scale] (screen pixels a unit) shows. */
    @Suppress("UNCHECKED_CAST")
    fun graph(output: Map<String, Any?>, challenges: List<Challenge>, scale: Double): GraphSpec {
        require(scale.isFinite() && scale > 0) { "a scale is a positive number of screen pixels a unit" }
        val board = (output["board"] as? List<*> ?: throw IllegalArgumentException("a stages output holds a board")).map { it as Map<String, Any?> }
        val stages = LinkedHashSet<String>().also { s -> for (row in board) (row["stages"] as? Map<*, *>)?.keys?.forEach { s.add(it.toString()) } }.toList()
        val settings = Setting.of(output)
        val answers = Challenge.answers(board, challenges)
        val nodes = ArrayList<NodeSpec>()
        val edges = ArrayList<EdgeSpec>()
        val width = MARGIN + board.size * COLUMN + 20
        val height = HEAD + stages.size * ROW + FOUND + 10
        val top = height / 2

        fun put(id: String, parent: String?, x: Double, y: Double, w: Double, h: Double, text: String, data: Map<String, Any?>,
                shown: String = text, max: Double = 13.0) {
            val (drawn, font) = label(shown, w * scale, h * scale, max)
            nodes.add(NodeSpec(id, NodeKind.ShapeNode, text, Transform(Vec3(x, y)),
                NodeData.of(_m["width" j w, "height" j h, "text" j drawn, "fontSize" j font] + data), parent))
        }

        fun evidence(p: Part, id: String, parent: String, x: Double, y: Double, r: Double, color: String, data: Map<String, Any?>, max: Double = 11.0) {
            put(id, parent, x, y, 2 * r, 2 * r, p.text, _m["kind" j "evidence", "key" j p.key, "shape" j "circle", "color" j color] + data, max = max)
            val f = r / p.radius
            // Leaves first: a whole drawn after its sibling values keeps its label, which stands at its corner, on top.
            for (i in p.parts.indices.sortedBy { p.parts[it].parts.isNotEmpty() }) {
                val q = p.parts[i]
                if (q.radius * f * scale >= MIN_PX) evidence(q, "$id.$i", id, p.x[i] * f, p.y[i] * f, q.radius * f,
                    if (color == LACKING || q.key == "lacking" && q.parts.isNotEmpty()) LACKING else if (q.parts.isEmpty()) "#f8fafc" else "#ffffff", emptyMap())
            }
        }

        // The key: what the colours say, then each claim with its author and the outcome its challenge knows.
        val across = settings.size * width + (settings.size - 1) * GAP
        val middle = (settings.size - 1) * (width + GAP) / 2
        val keyHeight = (board.size + 1) * LINE + 16
        val keyTop = keyHeight / 2 - 8
        put("key", null, middle, top + 24 + keyHeight / 2, across, keyHeight, "", _m["kind" j "key", "color" j "#ffffff"])
        val legend = _l["person" j PERSON, "model" j MODEL, "agrees" j AGREES, "disagrees" j DISAGREES, "no known outcome" j UNKNOWN,
            "stage passes" j PASSED, "stage loses" j LOST, "not reported" j UNREPORTED, "a setting moves it" j MOVED]
        val cell = (across - 16) / legend.size
        legend.forEachIndexed { i, (name, color) ->
            put("key.legend.$i", "key", -across / 2 + 8 + cell * (i + 0.5), keyTop - LINE / 2, cell - 6, LINE - 6, name,
                _m["kind" j "key", "color" j color, "textColor" j (if (color in _s[PASSED, LOST, UNREPORTED]) "#17212b" else "#ffffff")])
        }
        answers.forEachIndexed { c, (row, challenge) ->
            fun said(states: Boolean) = if (states) "states it" else "does not state it"
            val known = challenge?.let { said(it.states) + ", confidence " + weight(it.confidence) } ?: "no known outcome"
            val persons = if (challenge?.author == Expectation.MODEL) challenges.filter { it.author == Expectation.PERSON && it.claim == challenge.claim } else emptyList()
            val held = persons.joinToString("") { p -> " · a person: " + said(p.states) + if (p.states == challenge?.states) ", agrees" else ", differs" }
            put("key.c$c", "key", 0.0, keyTop - LINE * (c + 1.5), across - 16, LINE - 4,
                "${c + 1} · ${challenge?.origin ?: row["author"]} · ${row["claim"]} · $known$held", _m["kind" j "key", "claim" j c, "color" j "#ffffff"])
        }

        settings.forEachIndexed { w, setting ->
            val node = "w$w"
            put(node, null, w * (width + GAP), 0.0, width, height, setting.name, _m["kind" j "setting", "color" j "#ffffff", "opacity" j 0.0], shown = "")
            put("$node.name", node, 0.0, top - 18, width - 16, 26.0, setting.name, _m["kind" j "setting", "color" j "#f8fafc"])
            val scores = Challenge.score(answers, setting).map { s -> "${s.origin}: agrees ${weight(s.agreed)} · disagrees ${weight(s.disagreed)}" }
                .ifEmpty { _l["no challenge knows an outcome: nothing is scored"] }
            val each = (width - 16) / scores.size
            scores.forEachIndexed { k, t -> put("$node.score.$k", node, -width / 2 + 8 + each * (k + 0.5), top - 46, each - 4, 22.0, t, _m["kind" j "score", "color" j "#ffffff"]) }
            fun band(id: String, y: Double, h: Double, name: String, kind: String, stage: String?) {
                put(id, node, 0.0, y, width - 8, h, name, _m["kind" j kind, "stage" j stage, "color" j "#ffffff", "opacity" j 0.0], shown = "")
                put("$id.name", id, -(width - 8) / 2 + 8 + (MARGIN - 24) / 2, 0.0, MARGIN - 24, 26.0, name, _m["kind" j kind, "stage" j stage, "color" j "#f8fafc"])
            }
            stages.forEachIndexed { s, stage -> band("$node.s$s", top - HEAD - s * ROW - ROW / 2, ROW, stage, "stage", stage) }
            band("$node.found", top - HEAD - stages.size * ROW - FOUND / 2, FOUND, "found at every stage", "found", null)

            answers.forEachIndexed { c, (row, challenge) ->
                val x = -width / 2 + MARGIN + c * COLUMN + COLUMN / 2
                val blamed = setting.blame(row)
                val rank = if (blamed == null) stages.size else stages.indexOf(blamed)
                require(rank >= 0) { "${setting.name}: '$blamed' is not a stage of the board" }
                val read = if (w == 0) row["stages"] as? Map<*, *> else null
                stages.forEachIndexed { s, stage ->
                    val found = read?.get(stage) as? Map<*, *>
                    val passed = found?.get("pass") as? Boolean ?: if (s < rank) true else if (s == rank) false else null
                    val root = Part(stage, when (passed) { true -> "✓"; false -> "✗"; null -> "" },
                        found?.entries?.filter { it.key != "pass" && it.value != null }?.map { part(it.key.toString(), it.value) }.orEmpty())
                    evidence(root, "$node.s$s.c$c", "$node.s$s", x, -ROW / 2 + 12 + EVIDENCE, EVIDENCE,
                        when (passed) { true -> PASSED; false -> LOST; null -> UNREPORTED }, _m["stage" j stage, "claim" j c, "pass" j passed], max = 16.0)
                }
                val agrees = challenge?.let { it.states == (blamed == null) }
                val claim = "$node.c$c"
                put(claim, if (blamed == null) "$node.found" else "$node.s$rank", x, if (blamed == null) 0.0 else -ROW / 2 + 12 + 2 * EVIDENCE + CLAIM,
                    2 * CLAIM, 2 * CLAIM, row["claim"].toString(), _m["kind" j "claim", "claim" j c, "blame" j blamed, "agrees" j agrees, "shape" j "circle",
                        "color" j when (agrees) { true -> AGREES; false -> DISAGREES; null -> UNKNOWN }], shown = "")
                put("$claim.author", claim, 0.0, 0.0, 2 * AUTHOR, 2 * AUTHOR, (challenge?.origin ?: row["author"]).toString(),
                    _m["kind" j "author", "shape" j "circle", "textColor" j "#ffffff",
                        "color" j (if ((challenge?.author ?: row["author"]) == Expectation.PERSON) PERSON else MODEL)],
                    shown = "${c + 1}" + when (agrees) { true -> "✓"; false -> "✗"; null -> "" }, max = 12.0)
            }
        }

        // A link from a claim on the first board to the same claim on a variant's board, where the variant blames another stage.
        for (w in 1 until settings.size) answers.forEachIndexed { c, (row, _) ->
            val from = settings[0].blame(row)
            val to = settings[w].blame(row)
            if (from != to) edges.add(EdgeSpec("link.c$c.w$w", "w0.c$c", "w$w.c$c", EdgeKind.CurvedEdge,
                NodeData.of(_m["color" j MOVED, "thickness" j 2.5, "arrowhead" j "target", "label" j "${c + 1}: ${from ?: "found"} → ${to ?: "found"}"])))
        }
        return GraphSpec(nodes.toSeries(), edges.toSeries())
    }

    /** [graph] fitted to [viewport]: built at the scale the fit gives, with the camera on it. */
    fun fit(output: Map<String, Any?>, challenges: List<Challenge>, viewport: Viewport): GraphSpec {
        val camera = SpaceGraph(graph(output, challenges, 1.0)).fit(viewport, 24.0)
        return graph(output, challenges, camera.zoom).copy(camera = camera)
    }
}
