package borg.trikeshed.narsese

import borg.trikeshed.jules.BrainClient
import borg.trikeshed.job.CasStore
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncScopeFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Legal domain LCNC nodes — bolted onto existing infrastructure:
 * - `legal.ingest`: real citation extraction via eyecite (Free Law Project,
 *   BSD) run as a plain OS subprocess, plus LLM-propose/gate for
 *   HOLDING/ELEMENT/PARTY spans, grounded by the same deterministic
 *   substring-against-source check [ConstructionPatternGate] uses.
 *
 * eyecite's real dependency closure (`regex`, `lxml`, `pyahocorasick`,
 * `fast-diff-match-patch`) is native-extension code, which rules out
 * running it *inside* a `vm.*` Graal guest — those contexts set
 * `allowNativeAccess(false)` unconditionally
 * ([borg.trikeshed.graal.subvm.InProcessIsolate]). It does not rule out
 * eyecite at all: nothing about citation extraction needs guest-language
 * isolation, so [runEyecite] shells out to a real CPython interpreter in
 * `<project>/.venv` (`python3 -m venv .venv && .venv/bin/pip install
 * eyecite`), exactly the way a JVM app calls any other external tool.
 * Citation extraction is deterministic parsing of the literal input text —
 * every match is by construction a substring of it — but results are still
 * folded through [groundList] for defense in depth and to merge cleanly
 * with the LLM's own citation proposals (which catch prose mentions
 * eyecite's reporter-citation patterns don't, e.g. a bare case name with
 * no adjacent reporter cite).
 *
 * Precedent: Multi-Agent Debate (MAD) literature for the tribunal
 * loop/join shape; RARR/FEVER-style propose-then-verify-against-source
 * for why grounding is a deterministic gate, not a prompt instruction.
 */
object LegalNodes {

    /**
     * `legal.ingest` — LLM proposes CITATION/HOLDING/ELEMENT/PARTY spans;
     * every proposal is gate-checked against the source document before
     * being accepted — the same claim-check discipline
     * [ConstructionPatternGate] applies to causal constructions. A
     * proposal whose text does not literally occur in `text` is refused,
     * not silently kept, matching the "never invent a CID or a party
     * absent from the cited text" instruction with code instead of trust.
     */
    fun ingestRunner(
        brain: BrainClient,
        muxContext: CoroutineContext,
        cas: CasStore,
        kifSink: (String) -> Unit = {},
    ): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        // Inputs are keyed by the wire's literal toPort (LcncRunner.gather()
        // does not strip the `?`), and — same as mux.chat's prompt — a
        // human-oversight brief can arrive as a root frame binding instead
        // of a wire when this node is the trial's entry point. Precedence:
        // wired input > param > brief binding, matching mux.chat exactly.
        val text = ((inputs["text"] as? String)
            ?: (inputs["text?"] as? String)
            ?: node.params["text"]?.takeIf { it.isNotBlank() }
            ?: node.params["brief"]?.takeIf { it.isNotBlank() }?.let { briefName ->
                currentCoroutineContext()[LcncScopeFrame]?.binding(briefName)?.toString()
            }
        )?.takeIf { it.isNotBlank() }
        require(text != null) { "legal.ingest: no text wired, in params, or bound as '${node.params["brief"] ?: "<brief>"}'" }

        // Step 1: real citation extraction — eyecite, run as a subprocess
        // (see class doc for why it's a subprocess and not a vm.* guest).
        val eyeciteCitations = runEyecite(text)
        val citationsHint = if (eyeciteCitations.isEmpty()) "(none found)"
            else eyeciteCitations.joinToString("\n") { c ->
                val case = (c["case"] as? String).orEmpty()
                val reporter = (c["reporter"] as? String).orEmpty()
                val page = (c["page"] as? String).orEmpty()
                "  - ${c["text"]}  [$case $reporter $page]".trimEnd()
            }

        // Step 2: LLM propose for CITATION/HOLDING/ELEMENT/PARTY spans.
        // Same anti-hallucination pattern as ConstructionPatternGate:
        // LLM proposes, deterministic code checks substrings against source.
        // Citations are asked for too — eyecite catches reporter-format
        // cites precisely; the LLM catches prose case mentions eyecite's
        // patterns don't (a bare case name with no adjacent reporter cite).
        val maxTokens = node.params["maxTokens"]?.toIntOrNull() ?: 2048
        val proposePrompt = buildString {
            appendLine("LEGAL DOCUMENT (cite evidenceCid verbatim):")
            appendLine(text.take(8000))
            appendLine()
            appendLine("CITATIONS FOUND BY EYECITE (verified, format-based — trust these; find ADDITIONAL prose citations eyecite would miss):")
            appendLine(citationsHint)
            appendLine()
            appendLine("Extract: {\"citations\":[{\"party\":string,\"case\":string,\"reporter\":string,\"page\":string}],")
            appendLine("\"holdings\":[{\"text\":string,\"type\":\"holding|element|standard\",\"polarity\":boolean,\"evidenceCid\":\"sha256:...\"}],")
            appendLine("\"parties\":[{\"name\":string,\"role\":\"plaintiff|defendant|court|statute\"}]}")
            appendLine("Every \"case\" and \"text\" and \"name\" value must be copied verbatim from the document above.")
            appendLine("Never invent a CID or a party absent from the cited text.")
        }

        val raw = withContext(muxContext) {
            brain.chat(
                messages = listOf(
                    "system" to "You are a legal document analyst. Extract structured legal data from the document. Never fabricate citations, holdings, or parties not present in the source text.",
                    "user" to proposePrompt,
                ),
                maxTokens = maxTokens,
                temperature = 0.0,
                contextId = "legal.ingest:${node.id}",
            )
        }

        // Step 3: gate-check (grounding, not CID resolution — legal.ingest
        // has no evidenceCid per span yet, only the whole document's text).
        // Every proposed span must literally occur in the source; anything
        // that doesn't is refused, mirroring ConstructionPatternGate's
        // normalize-and-substring check rather than trusting the prompt.
        // eyecite's citations go through the same gate as a defense-in-depth
        // check (they should always pass — they're substrings by
        // construction) and to dedupe cleanly against the LLM's citations.
        val parsed = parseLegalExtraction(raw)
        val (groundedEyeciteCitations, _) = groundList(eyeciteCitations, text) { it["text"] as? String }
        val (groundedLlmCitations, refusedCitations) = groundList(parsed["citations"], text) { it["case"] as? String }
        val groundedCitations = dedupeCitations(groundedEyeciteCitations + groundedLlmCitations)
        val (groundedHoldings, refusedHoldings) = groundList(parsed["holdings"], text) { it["text"] as? String }
        val (groundedParties, refusedParties) = groundList(parsed["parties"], text) { it["name"] as? String }

        // Step 4: CID-anchor the document and assert only what survived the
        // gate into the KIF bank — the same "signals must be CID-anchored"
        // invariant Task 2's design calls for, generalized from a single
        // objectCid field to the document CID every emitted assertion
        // hangs off. Predicate names follow LKIF-Core/LegalRuleML's
        // (instance/cites/holding) rather than an invented vocabulary, so
        // the bank stays interoperable with SparqlKifMcpServer's existing
        // RDF/Turtle bridge without a second translation layer later.
        val documentCid = cas.put(text.encodeToByteArray())
        emitKif(documentCid.hex, groundedCitations, groundedHoldings, groundedParties, kifSink)

        mapOf(
            "documentCid" to documentCid.value,
            "citations" to groundedCitations,
            "elements" to mapOf(
                "holdings" to groundedHoldings,
                "parties" to groundedParties,
                "refused" to (refusedCitations + refusedHoldings + refusedParties),
            ),
            // A text rendering of the grounded extraction — this is what
            // feeds a mux.chat seat's `prompt?` port (text-kind), so counsel
            // reads a brief, not a raw structured map.
            "brief" to renderBrief(groundedCitations, groundedHoldings, groundedParties),
        )
    }

    /**
     * Assert the grounded (never the refused) extraction into the KIF bank,
     * one expression per fact, atom identity keyed off `documentCid` so
     * repeated ingestion of the same document doesn't mint drifting atoms.
     */
    internal fun emitKif(
        documentCid: String,
        citations: List<Map<String, Any?>>,
        holdings: List<Map<String, Any?>>,
        parties: List<Map<String, Any?>>,
        kifSink: (String) -> Unit,
    ) {
        val docAtom = "doc_$documentCid"
        kifSink("(instance $docAtom LegalDocument)")
        for (c in citations) {
            val case = (c["case"] as? String)?.takeIf { it.isNotBlank() } ?: continue
            kifSink("(cites $docAtom ${kifAtom(case)})")
        }
        for (h in holdings) {
            val text = (h["text"] as? String)?.takeIf { it.isNotBlank() } ?: continue
            val predicate = when (h["type"] as? String) {
                "element" -> "legalElement"
                "standard" -> "standardOfProof"
                else -> "holding"
            }
            kifSink("($predicate $docAtom ${kifString(text)})")
        }
        for (p in parties) {
            val name = (p["name"] as? String)?.takeIf { it.isNotBlank() } ?: continue
            val role = kifAtom((p["role"] as? String)?.takeIf { it.isNotBlank() } ?: "party")
            kifSink("(party $docAtom ${kifAtom(name)} $role)")
        }
    }

    private fun kifAtom(s: String): String = s.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun kifString(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    internal fun renderBrief(
        citations: List<Map<String, Any?>>,
        holdings: List<Map<String, Any?>>,
        parties: List<Map<String, Any?>>,
    ): String = buildString {
        appendLine("Grounded citations:")
        if (citations.isEmpty()) appendLine("  (none found)")
        else for (c in citations) appendLine("  - ${c["case"] ?: ""} ${c["reporter"] ?: ""} ${c["page"] ?: ""}".trim())
        appendLine("Grounded holdings/elements:")
        if (holdings.isEmpty()) appendLine("  (none found)")
        else for (h in holdings) appendLine("  - [${h["type"] ?: "holding"}] ${h["text"] ?: ""}")
        appendLine("Parties:")
        if (parties.isEmpty()) appendLine("  (none found)")
        else for (p in parties) appendLine("  - ${p["name"] ?: ""} (${p["role"] ?: ""})")
    }.trim()

    /**
     * The grounding gate: keep only entries whose [key] value is a
     * substring of [source] under the same lowercase/whitespace-collapse
     * normalization [ConstructionPatternGate.validateLine] uses. Returns
     * (accepted, refused) — refusals are reported, never silently dropped.
     */
    internal fun groundList(
        raw: Any?,
        source: String,
        key: (Map<String, Any?>) -> String?,
    ): Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> {
        @Suppress("UNCHECKED_CAST")
        val items = (raw as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
        val normalizedSource = " ${source.lowercase().replace(Regex("\\s+"), " ").trim()} "
        val accepted = mutableListOf<Map<String, Any?>>()
        val refused = mutableListOf<Map<String, Any?>>()
        for (item in items) {
            val candidate = key(item)?.trim().orEmpty()
            val grounded = candidate.isNotEmpty() && candidate.lowercase() in normalizedSource
            if (grounded) accepted += item
            else refused += mapOf("item" to item, "reason" to "not present in source document")
        }
        return accepted to refused
    }

    /**
     * `legal.evidence` — the evidence-bank injection §3/§5 flagged as
     * missing: queries [kif] (the daemon's shared, live KifKnowledgeBase —
     * the same bank `legal.ingest`'s kifSink now asserts into, not just the
     * write-only blackboard landing) for every fact recorded against this
     * document's atom, and folds it into the brief `argue` actually reads.
     * Never fails the run: an unrecognized/missing documentCid just yields
     * an unmodified brief (evidence bank empty, not an error).
     */
    fun evidenceRunner(kif: KifKnowledgeBase): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val documentCid = ((inputs["documentCid"] ?: inputs["documentCid?"]) as? String)
            ?: node.params["documentCid"].orEmpty()
        val brief = ((inputs["brief"] ?: inputs["brief?"]) as? String)
            ?: node.params["brief"].orEmpty()
        val docHex = documentCid.removePrefix("sha256:").trim()
        val evidence = if (docHex.isEmpty()) "" else queryEvidence(kif, "doc_$docHex")
        val combined = if (evidence.isBlank()) brief else buildString {
            append(brief)
            appendLine()
            appendLine()
            appendLine("Evidence bank (prior KIF facts for doc_$docHex):")
            append(evidence)
        }
        mapOf("brief" to combined)
    }

    /** Union of the predicate shapes [emitKif] mints, rendered as readable lines — not a general SPARQL surface, just enough to close the read-back loop. */
    internal fun queryEvidence(kif: KifKnowledgeBase, docAtom: String): String {
        fun text(v: String?): String = v?.trim('"').orEmpty()
        val lines = mutableListOf<String>()
        kif.query(KifExpr.parse("(cites $docAtom ?case)")).forEach { lines += "- cites: ${text(it["?case"])}" }
        kif.query(KifExpr.parse("(holding $docAtom ?text)")).forEach { lines += "- holding: ${text(it["?text"])}" }
        kif.query(KifExpr.parse("(legalElement $docAtom ?text)")).forEach { lines += "- element: ${text(it["?text"])}" }
        kif.query(KifExpr.parse("(standardOfProof $docAtom ?text)")).forEach { lines += "- standard of proof: ${text(it["?text"])}" }
        kif.query(KifExpr.parse("(party $docAtom ?name ?role)")).forEach { lines += "- party: ${text(it["?name"])} (${text(it["?role"])})" }
        return lines.joinToString("\n")
    }

    // ── eyecite: a real subprocess call, not a vm.* guest — see class doc ──

    /**
     * The eyecite bridge script — reads the document on stdin, prints a
     * JSON array of citations on stdout. Kept minimal: forward whatever
     * `groups`/`metadata` fields the matched citation type has rather than
     * hand-coding every eyecite citation class (FullCaseCitation,
     * FullLawCitation, SupraCitation, ...) on the Kotlin side.
     */
    private val EYECITE_SCRIPT = """
        import sys, json
        try:
            from eyecite import get_citations
        except Exception as e:
            print(json.dumps({"error": str(e)}))
            sys.exit(0)
        text = sys.stdin.read()
        out = []
        for c in get_citations(text):
            groups = dict(getattr(c, "groups", None) or {})
            md = getattr(c, "metadata", None)
            party = ""
            if md is not None:
                pl = getattr(md, "plaintiff", None)
                de = getattr(md, "defendant", None)
                if pl and de:
                    party = pl + " v. " + de
                elif pl:
                    party = pl
            out.append({
                "type": type(c).__name__,
                "text": c.matched_text(),
                "party": party,
                "reporter": groups.get("reporter", ""),
                "page": groups.get("page", ""),
            })
        print(json.dumps(out))
    """.trimIndent()

    /** `<project>/.venv/bin/python3` — created via `python3 -m venv .venv && .venv/bin/pip install eyecite`. */
    private fun resolveVenvPython(): String? {
        System.getenv("TRIKESHED_VENV_PYTHON")?.takeIf { it.isNotBlank() }?.let { return it }
        val candidate = File(System.getProperty("user.dir"), ".venv/bin/python3")
        return if (candidate.canExecute()) candidate.absolutePath else null
    }

    /**
     * Real eyecite extraction via a plain OS subprocess. Never throws —
     * an unavailable interpreter or a citation-free document both just
     * yield an empty list; legal.ingest degrades to LLM-only extraction.
     */
    internal fun runEyecite(text: String): List<Map<String, Any?>> {
        val python = resolveVenvPython() ?: return emptyList()
        return try {
            val process = ProcessBuilder(python, "-c", EYECITE_SCRIPT).start()
            process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return emptyList()
            }
            if (process.exitValue() != 0) return emptyList()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            parseEyeciteJson(output)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEyeciteJson(raw: String): List<Map<String, Any?>> {
        return try {
            val root = borg.trikeshed.parse.json.JsonSupport.parse(raw)
            (root as? List<*>)?.mapNotNull { item ->
                (item as? Map<*, *>)?.let { m ->
                    mapOf(
                        "text" to (m["text"]?.toString() ?: ""),
                        "case" to (m["party"]?.toString() ?: ""),
                        "reporter" to (m["reporter"]?.toString() ?: ""),
                        "page" to (m["page"]?.toString() ?: ""),
                    )
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Same-case-cite dedup: prefer the eyecite entry (precise reporter/page) when both agree on the case name. */
    private fun dedupeCitations(citations: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val seen = LinkedHashMap<String, Map<String, Any?>>()
        for (c in citations) {
            val key = (c["case"] as? String)?.trim()?.lowercase().orEmpty()
            if (key.isEmpty()) continue
            seen.putIfAbsent(key, c)
        }
        return seen.values.toList()
    }

    // ── legal extraction parsing ────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parseLegalExtraction(raw: String): Map<String, Any?> {
        val json = raw.substringAfter("```json", raw).substringAfter("```", raw)
            .substringBeforeLast("```", raw).trim()
        return try {
            val root = borg.trikeshed.parse.json.JsonSupport.parse(json) as? Map<*, *>
                ?: return mapOf("error" to "non-object JSON", "raw" to raw.take(500))
            mapOf(
                "citations" to (root["citations"] as? List<*> ?: emptyList<Any?>()),
                "holdings" to (root["holdings"] as? List<*> ?: emptyList<Any?>()),
                "parties" to (root["parties"] as? List<*> ?: emptyList<Any?>()),
            )
        } catch (e: Exception) {
            mapOf("error" to e.message, "raw" to raw.take(500))
        }
    }
}
