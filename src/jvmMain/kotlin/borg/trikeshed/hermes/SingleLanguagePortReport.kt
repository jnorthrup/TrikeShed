package borg.trikeshed.hermes

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.pointcut.VmFacet

/**
 * TDD-red porting analysis for single-language polyglot.
 *
 * Each VmFacet (GRAAL_PYTHON, GRAAL_JS) is a single-language isolate.
 * The reporting that used to be "python/js sleeve report" is now this:
 * for each facet, how far is hermes from running as a single-language polyglot?
 *
 * RED means: blockedNative > 0 OR blockedTransitive > 0 OR significantGaps not empty.
 * The test suite asserts GREEN and fails, driving the port.
 */
data class SingleLanguagePortReport(
    val facet: VmFacet,
    val modules: Int,
    val ready: Int,
    val blockedNative: Int,
    val blockedTransitive: Int,
    val significantGaps: List<HermesSignificantGap>,
    val banlistEntries: Int,
    val sleeveSpineCid: String,
    val upstreamSpineCid: String,
    val ontologySpineCid: String,
) {
    val blockedTotal: Int get() = blockedNative + blockedTransitive
    val readyRatio: Double get() = if (modules == 0) 1.0 else ready.toDouble() / modules
    val isGreen: Boolean get() = blockedTotal == 0 && significantGaps.isEmpty()
    val isRed: Boolean get() = !isGreen
    val verdict: String get() = if (isGreen) "GREEN — single-language ${facet.id} polyglot ready" else "RED — ${blockedTotal} blocked, ${significantGaps.size} gaps need Jules replacement parts"

    fun toMap(): Map<String, Any?> = mapOf(
        "facet" to facet.id,
        "verdict" to verdict,
        "green" to isGreen,
        "red" to isRed,
        "modules" to modules,
        "ready" to ready,
        "blockedNative" to blockedNative,
        "blockedTransitive" to blockedTransitive,
        "blockedTotal" to blockedTotal,
        "readyRatio" to readyRatio,
        "banlistEntries" to banlistEntries,
        "sleeveSpineCid" to sleeveSpineCid,
        "upstreamSpineCid" to upstreamSpineCid,
        "ontologySpineCid" to ontologySpineCid,
        "significantGaps" to significantGaps.map { it.toMap() },
        "replacementHint" to if (isRed) "PyQt/tkinter → braille ui (BrailleUi) — window/tk approximation via U+2800; NiceGUI→braille for computronium demo; native → host delegate or Jules part" else null,
    )
}

object SingleLanguagePortAnalyzer {
    fun fromInventory(facet: VmFacet, inv: HermesPortInventory): SingleLanguagePortReport {
        val gaps = inv.significantGaps()
        val gapList = (0 until gaps.size).map { gaps[it] }
        return SingleLanguagePortReport(
            facet = facet,
            modules = inv.modules.size,
            ready = inv.ready,
            blockedNative = inv.blockedNative,
            blockedTransitive = inv.blockedTransitive,
            significantGaps = gapList,
            banlistEntries = inv.banlist.size,
            sleeveSpineCid = inv.sleeveSpineCid,
            upstreamSpineCid = inv.upstreamSpineCid,
            ontologySpineCid = inv.ontology.cid.hex,
        )
    }

    fun summarize(reports: List<SingleLanguagePortReport>): Map<String, Any?> = mapOf(
        "facets" to reports.map { it.toMap() },
        "allGreen" to reports.all { it.isGreen },
        "anyRed" to reports.any { it.isRed },
        "totalBlocked" to reports.sumOf { it.blockedTotal },
    )
}
