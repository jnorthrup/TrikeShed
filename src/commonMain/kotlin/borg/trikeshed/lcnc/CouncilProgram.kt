package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries

/**
 * One panel of the council: a named charge argued by M persona seats.
 * Personas are free strings — the default five are named stances, but a
 * convening config may seat anything ("maritime lien specialist", …).
 */
data class PanelSpec(
    val name: String,
    val charge: String,
    val personas: List<String> = CouncilProgram.DEFAULT_PERSONAS,
)

/**
 * A convening config — the council's geometry AS DATA (design brief
 * `design/legal-council-3x5.md`: "variable geometry actual"). The default
 * convening is 3 panels x 5 experts x 2 rounds with the clarify and
 * mistrial rings armed. [CouncilProgram.build] is the one author that
 * turns this into a drawn LcncProgram.
 */
data class CouncilConfig(
    val caseId: String = "default",
    val panels: List<PanelSpec> = CouncilProgram.DEFAULT_PANELS,
    val rounds: Int = 2,
    val clarify: Boolean = true,
    val mistrial: Boolean = true,
    val roster: List<String> = CouncilProgram.DEFAULT_ROSTER,
    val synthesisModel: String? = null,
    val rulingModel: String? = null,
) {
    companion object {
        /** The shipped default: 3 panels x 5 personas x 2 rounds. */
        val DEFAULT_3x5: CouncilConfig = CouncilConfig()
    }
}

/**
 * CouncilProgram — the ONE geometry author for the legal council. A
 * convening config goes in; a fully drawn concentric LcncProgram comes out:
 * three (N) panel RINGS of five (M) expert seats each, unrolled rebuttal
 * rounds, per-panel synthesis, a council ruling seat, guarded clarify and
 * mistrial rings, and a `council.record` sink that lands the verdict on the
 * record. The can and the atoms are the same substance: `preset-council`
 * ships `build(CouncilConfig.DEFAULT_3x5)` verbatim, and the pure
 * `council.convene` node calls the same builder for re-geometry — byte
 * identity between the two is test-pinned.
 *
 * Pure geometry: only LcncNode/LcncWire/LcncProgram are touched here — no
 * contract lookups, no runners — so this file builds independently of the
 * council vocabulary landing in LcncContracts.
 */
object CouncilProgram {

    /** The five default stances seated on every default panel. */
    val DEFAULT_PERSONAS: List<String> = listOf(
        "doctrinal formalist — the text and structure of the governing law control; work the elements and definitions strictly",
        "precedent analyst — reason from decided cases and close analogies; follow or distinguish, never ignore",
        "consequentialist — weigh the outcomes and incentives this ruling would create beyond the instant dispute",
        "procedural/evidence skeptic — probe the record itself: sufficiency, admissibility, burden and standard of proof",
        "devil's advocate — argue the strongest credible reading AGAINST the panel's emerging consensus",
    )

    /** The three default panel charges. */
    val DEFAULT_PANELS: List<PanelSpec> = listOf(
        PanelSpec("merits", "statutory construction & elements of the claim"),
        PanelSpec("adversary", "procedure, evidence sufficiency & standard of proof"),
        PanelSpec("remedies", "remedy, proportionality & risk"),
    )

    /**
     * The 11 chat-capable model ids inlined in OroborosDaemon's `lcncModelMux`
     * (OroborosDaemon.kt, `val lcncModelMux = modelmux.ModelMux(keyMux) {…}`,
     * ~line 993) — mirrored BY CONVENTION, same ids, same order. A model
     * added to one list belongs in both.
     */
    val DEFAULT_ROSTER: List<String> = listOf(
        "deepseek-ai/deepseek-v4-pro",
        "nvidia/nemotron-3-super-120b-a12b",
        "mistralai/mistral-large-2-instruct",
        "z-ai/glm-5.2",
        "moonshotai/kimi-k2.6",
        "openai/gpt-oss-120b",
        "minimaxai/minimax-m3",
        "poolside/laguna-xs-2.1",
        "gpt-4o-mini",
        "llama-3.3-70b-versatile",
        "deepseek-chat",
    )

    private const val PREAMBLE =
        "You sit on a convened legal council. Argue only from the evidence and record provided; cite it specifically."

    private const val RULING_CHARGE =
        "weigh the panel positions against the evidence and rule on the record"

    private const val RULING_JSON_LINE =
        "End with exactly one JSON line: {\"disposition\":\"<granted|denied|remanded|...>\"," +
            "\"needsClarification\":<true|false>,\"clarificationQuestion\":\"<question, or empty>\"," +
            "\"mistrial\":<true|false>}"

    // Deterministic grid: column x = 40 + col*260, row y = 40 + row*140 —
    // nothing ever lands at the origin.
    private fun colX(col: Int): Double = 40.0 + col * 260.0
    private fun rowY(row: Int): Double = 40.0 + row * 140.0

    /**
     * Draw the council for [config]. Loud bounds: panels 1..8, personas per
     * panel 1..9, rounds 1..4. The emitted program is deterministic — same
     * config, same bytes — and carries no kanban graph: the concentric walk
     * IS the orchestration.
     */
    fun build(config: CouncilConfig): LcncProgram {
        require(config.panels.size in 1..8) {
            "council panels out of bounds: ${config.panels.size} (allowed 1..8)"
        }
        for (p in config.panels) require(p.personas.size in 1..9) {
            "council personas out of bounds for panel '${p.name}': ${p.personas.size} (allowed 1..9)"
        }
        require(config.rounds in 1..4) {
            "council rounds out of bounds: ${config.rounds} (allowed 1..4)"
        }
        require(config.roster.isNotEmpty()) { "council roster must not be empty" }

        val roster = config.roster
        val synthModel = config.synthesisModel ?: roster[0]
        val rulingModel = config.rulingModel ?: roster[3 % roster.size]
        val rounds = config.rounds

        fun seat(
            id: String, panel: String, seatName: String, role: String, round: Int,
            charge: String, persona: String?, system: String, model: String,
            maxTokens: Int, temperature: String, x: Double, y: Double,
        ): LcncNode {
            val params = linkedMapOf(
                "panel" to panel,
                "seat" to seatName,
                "role" to role,
                "round" to round.toString(),
                "charge" to charge,
            )
            if (persona != null) params["persona"] = persona
            params["system"] = system
            params["model"] = model
            params["maxTokens"] = maxTokens.toString()
            params["temperature"] = temperature
            params["contextId"] = "council/${config.caseId}/$panel/$seatName"
            params["caseId"] = config.caseId
            return LcncNode(id, "council.seat", params, x, y)
        }

        fun fold(id: String, label: String, x: Double, y: Double, numbered: Boolean = false): LcncNode {
            val params = linkedMapOf("label" to label)
            if (numbered) params["numbered"] = "true"
            return LcncNode(id, "text.fold", params, x, y)
        }

        val nodes = ArrayList<LcncNode>()
        val wires = ArrayList<LcncWire>()

        // ── root lane, left to right at y=40 ─────────────────────────────
        var lane = 0
        fun laneX(): Double = colX(0) + (lane++) * 260.0

        nodes.add(LcncNode("in.doc", "scope.in", params = mapOf("name" to "document"), x = laneX(), y = 40.0))
        nodes.add(LcncNode("in.case", "scope.in", params = mapOf("name" to "caseId?", "default" to config.caseId), x = laneX(), y = 40.0))
        nodes.add(LcncNode("ingest", "legal.ingest", params = mapOf("brief" to "document", "maxTokens" to "2048"), x = laneX(), y = 40.0))
        nodes.add(LcncNode("evidence", "legal.evidence", params = mapOf("scope" to "corpus", "maxFacts" to "64"), x = laneX(), y = 40.0))
        wires.add(LcncWire("in.doc", "value", "ingest", "text?"))
        wires.add(LcncWire("ingest", "documentCid", "evidence", "documentCid?"))
        wires.add(LcncWire("ingest", "brief", "evidence", "brief?"))

        // ── panel rings — each panel IS a ring of M seats x R rounds ─────
        var expertOffset = 0
        val rulingSystem = "You preside over the council: $RULING_CHARGE. " +
            "The panel positions and the evidence follow. $RULING_JSON_LINE"

        config.panels.forEachIndexed { p, spec ->
            val pTok = "p${p + 1}"
            val ringId = "panel.$pTok"
            val m = spec.personas.size
            val children = ArrayList<LcncNode>()

            children.add(LcncNode("$pTok.in.brief", "scope.in", params = mapOf("name" to "brief"), x = colX(0), y = rowY(0)))
            children.add(LcncNode("$pTok.in.doc", "scope.in", params = mapOf("name" to "documentCid?", "default" to ""), x = colX(0), y = rowY(1)))
            wires.add(LcncWire("evidence", "brief", ringId, "brief"))
            wires.add(LcncWire("ingest", "documentCid", ringId, "documentCid"))

            val expertModels = (0 until m).map { e -> roster[(expertOffset + e) % roster.size] }
            expertOffset += m

            // rounds unroll: round 1 spitballs the brief; each later round
            // rebuts the fold of the round before it.
            for (r in 1..rounds) {
                val seatCol = 1 + (r - 1) * 2
                val foldCol = 2 + (r - 1) * 2
                for (e in 0 until m) {
                    val seatName = if (r == 1) "e${e + 1}" else "e${e + 1}r$r"
                    val persona = spec.personas[e]
                    val system = if (r == 1) {
                        PREAMBLE +
                            "\nPanel charge (${spec.name}): ${spec.charge}." +
                            "\nYour persona: $persona." +
                            "\nSpitball freely — argue your strongest independent read; cite only the evidence provided."
                    } else {
                        PREAMBLE +
                            "\nPanel charge (${spec.name}): ${spec.charge}." +
                            "\nYour persona: $persona." +
                            "\nThe full round-${r - 1} record follows, your own take included. " +
                            "Rebut, refine, or concede point by point."
                    }
                    children.add(seat(
                        id = "$pTok.$seatName", panel = pTok, seatName = seatName,
                        role = if (r == 1) "expert" else "rebuttal", round = r,
                        charge = spec.charge, persona = persona, system = system,
                        model = expertModels[e], maxTokens = 500,
                        temperature = if (r == 1) "0.8" else "0.6",
                        x = colX(seatCol), y = rowY(e),
                    ))
                    wires.add(
                        if (r == 1) LcncWire("$pTok.in.brief", "value", "$pTok.$seatName", "prompt")
                        else LcncWire("$pTok.fold.r${r - 1}", "text", "$pTok.$seatName", "prompt"),
                    )
                }
                children.add(fold("$pTok.fold.r$r", "Panel ${spec.name} — round $r", colX(foldCol), rowY(0)))
                if (r == 1) wires.add(LcncWire("$pTok.in.brief", "value", "$pTok.fold.r1", "parts"))
                for (e in 0 until m) {
                    val seatName = if (r == 1) "e${e + 1}" else "e${e + 1}r$r"
                    wires.add(LcncWire("$pTok.$seatName", "labeled", "$pTok.fold.r$r", "parts"))
                }
            }

            val synthCol = 1 + rounds * 2
            children.add(seat(
                id = "$pTok.synth", panel = pTok, seatName = "synth", role = "synthesis", round = rounds,
                charge = spec.charge, persona = null,
                system = PREAMBLE +
                    "\nPanel charge (${spec.name}): ${spec.charge}." +
                    "\nReduce the expert record to the panel position: majority themes; " +
                    "preserve numbered dissents with seat attribution.",
                model = synthModel, maxTokens = 700, temperature = "0.2",
                x = colX(synthCol), y = rowY(0),
            ))
            wires.add(LcncWire("$pTok.fold.r$rounds", "text", "$pTok.synth", "prompt"))

            children.add(fold("$pTok.fold.rec", "Panel ${spec.name} — transcript", colX(synthCol + 1), rowY(0)))
            for (r in 1..rounds) wires.add(LcncWire("$pTok.fold.r$r", "text", "$pTok.fold.rec", "parts"))
            wires.add(LcncWire("$pTok.synth", "labeled", "$pTok.fold.rec", "parts"))

            children.add(LcncNode("$pTok.fold.turns", "record.fold", x = colX(synthCol + 1), y = rowY(1)))
            for (r in 1..rounds) for (e in 0 until m) {
                val seatName = if (r == 1) "e${e + 1}" else "e${e + 1}r$r"
                wires.add(LcncWire("$pTok.$seatName", "record", "$pTok.fold.turns", "parts"))
            }
            wires.add(LcncWire("$pTok.synth", "record", "$pTok.fold.turns", "parts"))

            val outCol = synthCol + 2
            children.add(LcncNode("$pTok.out.pos", "scope.out", params = mapOf("name" to "position"), x = colX(outCol), y = rowY(0)))
            children.add(LcncNode("$pTok.out.rec", "scope.out", params = mapOf("name" to "transcript"), x = colX(outCol), y = rowY(1)))
            children.add(LcncNode("$pTok.out.turns", "scope.out", params = mapOf("name" to "turns"), x = colX(outCol), y = rowY(2)))
            wires.add(LcncWire("$pTok.synth", "labeled", "$pTok.out.pos", "value"))
            wires.add(LcncWire("$pTok.fold.rec", "text", "$pTok.out.rec", "value"))
            wires.add(LcncWire("$pTok.fold.turns", "turns", "$pTok.out.turns", "value"))

            children.add(LcncNode("$pTok.note", "note",
                params = mapOf("text" to
                    "panel $pTok (${spec.name}): ${spec.charge}\n" +
                    "$m seats x $rounds rounds; models:\n" +
                    expertModels.mapIndexed { e, mm -> "  e${e + 1} $mm" }.joinToString("\n") +
                    "\nsynth $synthModel (temp 0.2)"),
                x = colX(outCol), y = rowY(3)))

            nodes.add(LcncNode(ringId, "scope", x = 260.0, y = 220.0 + p * 580.0, children = children.toSeries()))
        }

        // ── convergence: positions → ruling diet → ruling seat → parse ───
        nodes.add(fold("fold.positions", "Panel positions", laneX(), 40.0, numbered = true))
        for (p in config.panels.indices) {
            wires.add(LcncWire("panel.p${p + 1}", "position", "fold.positions", "parts"))
        }

        // The judge's diet is evidence AND positions — exactly two parts.
        nodes.add(fold("fold.ruling", "Record before the council", laneX(), 40.0))
        wires.add(LcncWire("evidence", "brief", "fold.ruling", "parts"))
        wires.add(LcncWire("fold.positions", "text", "fold.ruling", "parts"))

        nodes.add(seat(
            id = "ruling", panel = "council", seatName = "ruling", role = "ruling", round = 1,
            charge = RULING_CHARGE, persona = null, system = rulingSystem,
            model = rulingModel, maxTokens = 900, temperature = "0.1",
            x = laneX(), y = 40.0,
        ))
        wires.add(LcncWire("fold.ruling", "text", "ruling", "prompt"))

        nodes.add(LcncNode("parse", "ruling.parse", x = laneX(), y = 40.0))
        wires.add(LcncWire("ruling", "content", "parse", "text"))

        val belowY = 220.0 + config.panels.size * 580.0

        // ── clarify — a GUARDED ring, structural bound of 1 (one ring, no
        //    loop — mirrors the dead FSM's deliberate-clarify maxIterations=1)
        if (config.clarify) {
            val cl = ArrayList<LcncNode>()
            cl.add(LcncNode("cl.in.q", "scope.in", params = mapOf("name" to "question"), x = colX(0), y = rowY(0)))
            cl.add(LcncNode("cl.in.pos", "scope.in", params = mapOf("name" to "positions"), x = colX(0), y = rowY(1)))
            cl.add(LcncNode("cl.in.brief", "scope.in", params = mapOf("name" to "brief"), x = colX(0), y = rowY(2)))
            cl.add(fold("cl.fold.q", "Clarification requested by the presiding seat", colX(1), rowY(0)))
            wires.add(LcncWire("cl.in.q", "value", "cl.fold.q", "parts"))
            wires.add(LcncWire("cl.in.brief", "value", "cl.fold.q", "parts"))
            wires.add(LcncWire("cl.in.pos", "value", "cl.fold.q", "parts"))
            config.panels.forEachIndexed { p, spec ->
                val id = "cl.p${p + 1}"
                cl.add(seat(
                    id = id, panel = "council", seatName = "clarify${p + 1}", role = "clarify", round = 1,
                    charge = spec.charge, persona = null,
                    system = "A clarification was requested on the record. You answer for panel " +
                        "${spec.name} (${spec.charge}). Answer the presiding question directly " +
                        "from the evidence and positions provided.",
                    model = roster[0], maxTokens = 500, temperature = "0.3",
                    x = colX(2), y = rowY(p),
                ))
                wires.add(LcncWire("cl.fold.q", "text", id, "prompt"))
            }
            cl.add(fold("cl.fold.rec", "Clarified record before the council", colX(3), rowY(0)))
            wires.add(LcncWire("cl.in.pos", "value", "cl.fold.rec", "parts"))
            for (p in config.panels.indices) wires.add(LcncWire("cl.p${p + 1}", "labeled", "cl.fold.rec", "parts"))
            cl.add(seat(
                id = "cl.ruling", panel = "council", seatName = "ruling-final", role = "ruling", round = 2,
                charge = RULING_CHARGE, persona = null,
                system = rulingSystem + " This is the final round — you MUST rule: set needsClarification to false.",
                model = rulingModel, maxTokens = 900, temperature = "0.1",
                x = colX(4), y = rowY(0),
            ))
            wires.add(LcncWire("cl.fold.rec", "text", "cl.ruling", "prompt"))
            cl.add(LcncNode("cl.parse", "ruling.parse", x = colX(5), y = rowY(0)))
            wires.add(LcncWire("cl.ruling", "content", "cl.parse", "text"))
            cl.add(LcncNode("cl.fold.turns", "record.fold", x = colX(5), y = rowY(1)))
            for (p in config.panels.indices) wires.add(LcncWire("cl.p${p + 1}", "record", "cl.fold.turns", "parts"))
            wires.add(LcncWire("cl.ruling", "record", "cl.fold.turns", "parts"))
            cl.add(LcncNode("cl.out.verdict", "scope.out", params = mapOf("name" to "verdict"), x = colX(6), y = rowY(0)))
            cl.add(LcncNode("cl.out.text", "scope.out", params = mapOf("name" to "text"), x = colX(6), y = rowY(1)))
            cl.add(LcncNode("cl.out.turns", "scope.out", params = mapOf("name" to "turns"), x = colX(6), y = rowY(2)))
            wires.add(LcncWire("cl.parse", "verdict", "cl.out.verdict", "value"))
            wires.add(LcncWire("cl.ruling", "content", "cl.out.text", "value"))
            wires.add(LcncWire("cl.fold.turns", "turns", "cl.out.turns", "value"))

            nodes.add(LcncNode("clarify", "scope", x = 40.0, y = belowY, children = cl.toSeries()))
            wires.add(LcncWire("parse", "needsClarification", "clarify", "when?"))
            wires.add(LcncWire("parse", "clarificationQuestion", "clarify", "question"))
            wires.add(LcncWire("fold.positions", "text", "clarify", "positions"))
            wires.add(LcncWire("evidence", "brief", "clarify", "brief"))
        }

        // ── mistrial — a GUARDED ring: proceedings void, loudly ──────────
        if (config.mistrial) {
            val mi = ArrayList<LcncNode>()
            mi.add(LcncNode("m.in.text", "scope.in", params = mapOf("name" to "text"), x = colX(0), y = rowY(0)))
            mi.add(fold("m.fold", "MISTRIAL — proceedings void", colX(1), rowY(0)))
            wires.add(LcncWire("m.in.text", "value", "m.fold", "parts"))
            mi.add(LcncNode("m.out", "scope.out", params = mapOf("name" to "record"), x = colX(2), y = rowY(0)))
            wires.add(LcncWire("m.fold", "text", "m.out", "value"))

            nodes.add(LcncNode("mistrial", "scope", x = 340.0, y = belowY, children = mi.toSeries()))
            wires.add(LcncWire("parse", "mistrial", "mistrial", "when?"))
            wires.add(LcncWire("parse", "text", "mistrial", "text"))
        }

        // ── coalesce: a clarified ruling wins over the original; a skipped
        //    ring's yield stays absent, so the original stands untouched.
        nodes.add(LcncNode("pick.verdict", "coalesce", x = laneX(), y = 40.0))
        if (config.clarify) wires.add(LcncWire("clarify", "verdict", "pick.verdict", "a?"))
        wires.add(LcncWire("parse", "verdict", "pick.verdict", "b"))
        nodes.add(LcncNode("pick.text", "coalesce", x = laneX(), y = 40.0))
        if (config.clarify) wires.add(LcncWire("clarify", "text", "pick.text", "a?"))
        wires.add(LcncWire("ruling", "content", "pick.text", "b"))

        // ── the record: CAS + blackboard + couch + kif + case lifecycle ──
        nodes.add(LcncNode("record", "council.record", params = mapOf("caseId" to config.caseId), x = laneX(), y = 40.0))
        wires.add(LcncWire("pick.verdict", "value", "record", "verdict"))
        for (p in config.panels.indices) wires.add(LcncWire("panel.p${p + 1}", "transcript", "record", "transcript"))
        wires.add(LcncWire("fold.positions", "text", "record", "transcript"))
        wires.add(LcncWire("pick.text", "value", "record", "transcript"))
        if (config.clarify) wires.add(LcncWire("clarify", "text", "record", "transcript"))
        if (config.mistrial) wires.add(LcncWire("mistrial", "record", "record", "transcript"))
        for (p in config.panels.indices) wires.add(LcncWire("panel.p${p + 1}", "turns", "record", "turns"))
        wires.add(LcncWire("ruling", "record", "record", "turns"))
        if (config.clarify) wires.add(LcncWire("clarify", "turns", "record", "turns"))
        wires.add(LcncWire("in.case", "value", "record", "caseId?"))
        wires.add(LcncWire("ingest", "documentCid", "record", "documentCid?"))

        nodes.add(LcncNode("out.ruling", "scope.out", params = mapOf("name" to "ruling"), x = laneX(), y = 40.0))
        wires.add(LcncWire("record", "report", "out.ruling", "value"))

        val seatCount = config.panels.sumOf { it.personas.size } * rounds + config.panels.size + 1
        val expertCounts = config.panels.map { it.personas.size }
        nodes.add(LcncNode("note", "note",
            params = mapOf("text" to
                "preset-council — ${config.panels.size} panels x " +
                "${expertCounts.distinct().singleOrNull() ?: expertCounts.joinToString("/")} experts x $rounds rounds\n" +
                config.panels.mapIndexed { p, s -> "p${p + 1} ${s.name}: ${s.charge}" }.joinToString("\n") +
                "\nsynth=$synthModel  ruling=$rulingModel  roster=${roster.size} models" +
                "\n~$seatCount serial council.seat calls per convening — expect minutes, not seconds" +
                (if (config.clarify) "\nclarify ring (+${config.panels.size + 1} seats) sits only when the ruling asks" else "") +
                "\ncoexists with preset-tribunal / preset-legal-tribunal (mux.chat untouched);" +
                "\ncouncil.convene re-draws this geometry from a convening config"),
            x = laneX(), y = 40.0))

        fun countNodes(ns: Series<LcncNode>): Int {
            var n = 0
            for (i in 0 until ns.size) {
                n += 1 + countNodes(ns[i].children)
            }
            return n
        }

        val nodeSeries = nodes.toSeries()
        return LcncProgram(
            name = "preset-council",
            nodes = nodeSeries,
            wires = wires.toSeries(),
            view = LcncView(x = 20.0, y = 20.0, zoom = 0.5),
            seq = countNodes(nodeSeries),
        )
    }
}
