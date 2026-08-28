package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.memory.memoryFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * I1 — deterministic Hermes design distillation.
 *
 * Reads only through [CuratorImpulseFeeder], the already-trusted ledger/state.db seam. The
 * projection is mechanical: five model/tool-interface feature dimensions count explicit corpus
 * markers and retain the evidence session ids. Each feature becomes a deterministic Markdown
 * document; the feature→mux/envelope/CCEK table is another document. [distillTo] writes them
 * through [MemoryStore.put], so they are CAS'd, Line-CAS indexed, and visible in the terrain.
 *
 * No clock, model call, or map iteration participates in identity: identical ledger/transcript
 * snapshots reproduce identical document CIDs.
 */
object HermesDesignDistiller {

    enum class Feature(val slug: String, val title: String) {
        TOOL_CALL_FRAMING("tool-call-framing", "Tool call framing"),
        TOOL_DESCRIPTION_INVOCATION("tool-description-invocation", "Tool description and invocation"),
        RETRY_CONTINUATION("retry-continuation", "Retry and continuation discipline"),
        SESSION_STRUCTURE("session-structure", "Session structure and dialogue order"),
        CURATION_RECORDING("curation-recording", "Curation ledger recording discipline"),
    }

    data class Shape(val muxChannel: String, val envelope: String, val ccekSeat: String)
    data class Evidence(val feature: Feature, val occurrences: Int, val scenarioIds: Series<String>)
    data class Document(val path: String, val description: String, val bytes: ByteArray)
    data class Landing(val path: String, val cid: ContentId)

    fun shape(feature: Feature): Shape = when (feature) {
        Feature.TOOL_CALL_FRAMING -> Shape("tool-frame", "tool-call-specialist", "Element")
        Feature.TOOL_DESCRIPTION_INVOCATION -> Shape("tool-schema", "tool-affordance-specialist", "Key")
        Feature.RETRY_CONTINUATION -> Shape("continuation", "recovery-specialist", "Context")
        Feature.SESSION_STRUCTURE -> Shape("dialogue", "session-specialist", "Coroutine")
        Feature.CURATION_RECORDING -> Shape("curator-ledger", "governance-specialist", "Element")
    }

    /** Pure projection over snapshots already loaded by the trusted feeder. */
    fun project(
        impulses: Series<CuratorImpulse>,
        scenarios: Series<ReplayScenario>,
    ): Series<Document> {
        val evidence = Feature.entries.size j { i: Int -> evidenceFor(Feature.entries[i], impulses, scenarios) }
        val docs = ArrayList<Document>(Feature.entries.size + 1)
        for (i in 0 until evidence.size) {
            val e = evidence[i]
            val s = shape(e.feature)
            val text = buildString {
                append("---\nkind: hermes-design-distillate\nfeature: ").append(e.feature.slug).append("\n---\n\n")
                append("# ").append(e.feature.title).append("\n\n")
                append("Evidence occurrences: ").append(e.occurrences).append("\n\n")
                append("## Feature → shape\n\n")
                append("- mux channel: `").append(s.muxChannel).append("`\n")
                append("- specialization envelope: `").append(s.envelope).append("`\n")
                append("- CCEK seat: `").append(s.ccekSeat).append("`\n\n")
                append("## Evidence sessions\n\n")
                for (n in 0 until e.scenarioIds.size) append("- `").append(e.scenarioIds[n]).append("`\n")
            }
            docs.add(Document("/corpus/hermes/distillate/${e.feature.slug}.md", e.feature.title, text.encodeToByteArray()))
        }
        docs.add(featureTable())
        return docs.size j { i: Int -> docs[i] }
    }

    private fun evidenceFor(
        feature: Feature,
        impulses: Series<CuratorImpulse>,
        scenarios: Series<ReplayScenario>,
    ): Evidence {
        var count = if (feature == Feature.CURATION_RECORDING) impulses.size else 0
        val ids = ArrayList<String>()
        for (s in 0 until scenarios.size) {
            val scenario = scenarios[s]
            var scenarioCount = if (feature == Feature.SESSION_STRUCTURE) scenario.turns.size else 0
            for (t in 0 until scenario.turns.size) {
                val text = scenario.turns[t].text.lowercase()
                scenarioCount += when (feature) {
                    Feature.TOOL_CALL_FRAMING -> tokenCount(text, "tool_call", "tool call", "function_call")
                    // Orthogonal to framing: subtract the counts embedded inside tool_call/
                    // "tool call"/function_call so the two features never double-count.
                    Feature.TOOL_DESCRIPTION_INVOCATION ->
                        (tokenCount(text, "tool", "function", "invoke", "schema") -
                            tokenCount(text, "tool_call", "tool call", "function_call")).coerceAtLeast(0)
                    Feature.RETRY_CONTINUATION -> tokenCount(text, "retry", "continue", "continuation", "resume", "timeout")
                    Feature.SESSION_STRUCTURE, Feature.CURATION_RECORDING -> 0
                }
            }
            if (scenarioCount > 0) {
                count += scenarioCount
                ids.add(scenario.scenarioId)
            }
        }
        val stable = ids.distinct().sorted()
        return Evidence(feature, count, stable.size j { i: Int -> stable[i] })
    }

    private fun tokenCount(text: String, vararg markers: String): Int {
        var n = 0
        for (marker in markers) {
            var at = text.indexOf(marker)
            while (at >= 0) { n++; at = text.indexOf(marker, at + marker.length) }
        }
        return n
    }

    private fun featureTable(): Document {
        val text = buildString {
            append("# Hermes feature → TrikeShed shape\n\n")
            append("| Hermes feature | mux channel | specialization envelope | CCEK seat |\n")
            append("|---|---|---|---|\n")
            for (feature in Feature.entries) {
                val s = shape(feature)
                append('|').append(feature.title).append('|').append(s.muxChannel).append('|')
                    .append(s.envelope).append('|').append(s.ccekSeat).append("|\n")
            }
        }
        return Document(
            "/corpus/hermes/feature-shape.md",
            "Hermes feature to mux, specialization envelope, and CCEK seat mapping",
            text.encodeToByteArray(),
        )
    }

    /** Read live + optional archived Hermes snapshots, project, and land every doc in CAS. */
    suspend fun distillTo(
        liveProfile: File,
        archivedProfile: File?,
        store: MemoryStore,
    ): Series<Landing> {
        val impulses = ArrayList<CuratorImpulse>()
        val scenarios = ArrayList<ReplayScenario>()
        suspend fun load(dir: File) {
            val feeder = CuratorImpulseFeeder(dir)
            val i = feeder.loadImpulses()
            val s = feeder.loadScenarios(i)
            for (n in 0 until i.size) impulses.add(i[n])
            for (n in 0 until s.size) scenarios.add(s[n])
        }
        load(liveProfile)
        if (archivedProfile != null && archivedProfile.isDirectory) load(archivedProfile)
        val iSeries = impulses.size j { i: Int -> impulses[i] }
        val sSeries = scenarios.size j { i: Int -> scenarios[i] }
        val docs = project(iSeries, sSeries)
        return land(docs, store)
    }

    /** Deterministic documents → MemoryStore/CAS/Line-CAS citizens. */
    suspend fun land(docs: Series<Document>, store: MemoryStore): Series<Landing> =
        withContext(Dispatchers.IO) {
            val landed = ArrayList<Landing>(docs.size)
            for (n in 0 until docs.size) {
                val d = docs[n]
                val cid = store.put(memoryFile(d.path, d.description, d.bytes), agentId = "hermes-distiller", kind = "design-distillate")
                landed.add(Landing(d.path, cid))
            }
            landed.size j { i: Int -> landed[i] }
        }

    fun emptyDocuments(): Series<Document> = project(emptySeriesOf(), emptySeriesOf())
}
