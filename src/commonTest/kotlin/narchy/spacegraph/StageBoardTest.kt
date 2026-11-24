package narchy.spacegraph

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StageBoardTest {
    val returns = "The bailee must return the goods."
    val delivery = "A bailment is a delivery of goods in trust."
    val restored = "The goods shall be restored by the bailee."
    val clause = _m["subject" j "goods", "modal" j "shall", "verb" j "be_restore", "object" j null]

    /**
     * A stages output written by hand: three claims over four stages, the last ("sense") one the ingest node does not
     * have, and two variants. The first claim is found at every stage and lost at token under the generic variant; the
     * second is lost at token everywhere; the third is lost at clause, and at dependency under parser b.
     */
    val output = _m[
        "board" j _l[
            _m["author" j "person", "claim" j returns, "blame" j null, "stages" j _m[
                "token" j _m["pass" j true, "wanted" j _l["bailee", "goods", "return"], "lacking" j emptyList<String>()],
                "dependency" j _m["pass" j true, "wanted" j _l["return nsubj bailee", "return obj goods"], "lacking" j emptyList<String>()],
                "clause" j _m["pass" j true, "claim" j _l[clause], "lacking" j emptyList<Any?>()],
                "sense" j _m["pass" j true]],
                "variants" j _m["parser: b" j "none", "generic: goods" j "token"]],
            _m["author" j "model", "claim" j delivery, "blame" j "token", "stages" j _m[
                "token" j _m["pass" j false, "wanted" j _l["bailment", "delivery", "goods", "trust"], "lacking" j _l["bailment"],
                    "sentence" j "A delivery of goods in trust."],
                "dependency" j _m["pass" j false, "wanted" j _l["delivery nsubj bailment"], "lacking" j _l["delivery nsubj bailment"]],
                "clause" j _m["pass" j false, "claim" j emptyList<Any?>(), "lacking" j emptyList<Any?>()],
                "sense" j _m["pass" j false]],
                "variants" j _m["parser: b" j "token", "generic: goods" j "token"]],
            _m["author" j "model", "claim" j restored, "blame" j "clause", "stages" j _m[
                "token" j _m["pass" j true, "wanted" j _l["bailee", "goods", "restore"], "lacking" j emptyList<String>()],
                "dependency" j _m["pass" j true, "wanted" j _l["restore nsubj:pass goods"], "lacking" j emptyList<String>()],
                "clause" j _m["pass" j false, "claim" j _l[clause], "lacking" j _l[clause]],
                "sense" j _m["pass" j false]],
                "variants" j _m["parser: b" j "dependency", "generic: goods" j "clause"]],
        ],
        "passage" j _m["parser" j "a", "generic" j emptyList<String>()],
    ]

    val challenges = _l[Challenge("person", returns, true, 1.0), Challenge("model", delivery, true, 0.5, "m"),
        Challenge("model", restored, false, 0.25, "m")]

    val graph = StageBoard.graph(output, challenges, 8.0)
    val all = graph.nodes.view.toList()
    val nodes = all.associateBy { it.id }
    fun kind(n: NodeSpec) = n.data.string("kind")
    fun under(id: String) = all.filter { it.parent == id }
    /** A node's data as the JSON text it was written as. */
    fun json(d: NodeData) = d.document.b.let { bytes -> ByteArray(bytes.size) { bytes[it] } }.decodeToString()
    fun dump(g: GraphSpec) = (g.nodes.view.map { "${it.id}|${it.parent}|${it.label}|${it.transform}|${json(it.data)}" } +
        g.edges.view.map { "${it.id}|${it.source}|${it.target}|${it.kind}|${json(it.data)}" }).joinToString("\n")

    @Test fun eachSettingIsABoardAndEachChallengeRestsWhereItsSettingBlamesIt() {
        val settings = all.filter { it.parent == null && kind(it) == "setting" }
        assertEquals(_l["w0", "w1", "w2"], settings.map { it.id })
        assertEquals(_l["parser: a", "parser: b", "generic: goods"], settings.map { it.label })
        assertEquals(_l["token", "dependency", "clause", "sense"], under("w0").filter { kind(it) == "stage" }.map { it.data.string("stage") })

        val claims = all.filter { kind(it) == "claim" }
        assertEquals(9, claims.size, "one claim a board")
        val rests = _l[_l["w0.found", "w0.s0", "w0.s2"], _l["w1.found", "w1.s0", "w1.s1"], _l["w2.s0", "w2.s0", "w2.s2"]]
        for (w in 0..2) for (c in 0..2) assertEquals(rests[w][c], nodes.getValue("w$w.c$c").parent, "claim $c on board $w")
        assertEquals(_l["true", "false", "true"], (0..2).map { nodes.getValue("w0.c$it").data.string("agrees") }, "the rings as read")
        assertEquals("false", nodes.getValue("w2.c0").data.string("agrees"), "the generic variant loses the person's claim")

        // A link only where a variant blames another stage than the first board does.
        assertEquals(_l["link.c2.w1 w0.c2 w1.c2", "link.c0.w2 w0.c0 w2.c0"], graph.edges.view.map { "${it.id} ${it.source} ${it.target}" })
    }

    @Test fun aStagesEvidenceOpensAsAGraphOfItsPartsToTheDepthTheScaleShows() {
        // The token stage's evidence for the second claim holds what it lacked, and the lacking opens to its items.
        val token = under("w0.s0.c1")
        assertEquals(_s["wanted (4)", "lacking (1)", "sentence: A delivery of goods in trust."], token.map { it.label }.toSet())
        val lacking = token.single { it.label == "lacking (1)" }
        assertEquals(_l["bailment"], under(lacking.id).map { it.label })
        // A lacking clause opens again: the clause, then its subject, modal and verb.
        val clauses = under(under("w0.s2.c2").single { it.label == "lacking (1)" }.id)
        assertEquals(_l["goods shall be_restore"], clauses.map { it.label })
        assertEquals(_l["subject: goods", "modal: shall", "verb: be_restore"], under(clauses.single().id).map { it.label })
        // A variant's board knows only where a claim is blamed: its stages carry no evidence, and are coloured by the blame.
        assertTrue(under("w1.s1.c2").isEmpty())
        assertEquals(_l["true", "false", ""], (0..2).map { nodes.getValue("w1.s$it.c2").data.string("pass") })
        // Seen from far, a stage's evidence is one circle.
        assertTrue(StageBoard.graph(output, challenges, 0.05).nodes.view.none { it.parent == "w0.s0.c1" })
    }

    @Test fun theSameInputGivesTheSameGraph() {
        assertEquals(dump(graph), dump(StageBoard.graph(output, challenges, 8.0)))
    }
}
