package borg.trikeshed.hermes

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineCasIndex
import borg.trikeshed.cas.LineSpine
import borg.trikeshed.collections.LineAperture
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α
import borg.trikeshed.lib.cascade.Count
import borg.trikeshed.lib.cascade.Emit
import borg.trikeshed.lib.cascade.Level
import borg.trikeshed.lib.cascade.groupLevel

/** High-level ontology carried by one canonical, trimmed string in the daily Hermes sleeve. */
enum class HermesOntologyKind(val token: String) {
    READY("ready"),
    BLOCKED("blocked"),
    DEFERRED("deferred"),
}

data class HermesOntologyFact(
    val kind: HermesOntologyKind,
    /** Native blocker for BLOCKED/DEFERRED; upstream|sleeve for READY. */
    val root: String,
    val module: String,
)

typealias HermesOntologyFacts = Series<HermesOntologyFact>

/**
 * Recoverable trimmed strings × (LineCas identity × cascade emits).
 *
 * The LineSpine is the daily structural identity. The emits are the same ontology viewed through
 * semantic prefix depths: kind → blocker/origin → top-level package → full module path.
 */
typealias HermesOntologySpine = Join<Series<String>, Join<LineSpine, Series<Emit<String, Int>>>>

val HermesOntologySpine.lines: Series<String> get() = a
val HermesOntologySpine.lineSpine: LineSpine get() = b.a
val HermesOntologySpine.emits: Series<Emit<String, Int>> get() = b.b
val HermesOntologySpine.cid: ContentId get() = LineCas.spineCid(lineSpine)

private fun HermesOntologyFact.keySegments(): Series<String> {
    val moduleSegments = module.split('.')
    return (2 + moduleSegments.size) j { i: Int ->
        when (i) {
            0 -> kind.token
            1 -> root.ifBlank { "_" }
            else -> moduleSegments[i - 2]
        }
    }
}

private fun HermesOntologyFact.canonicalLine(): String =
    keySegments().view.joinToString("/").trim()

/** Build the deterministic ontology: order-independent input, trimmed-string structural identity. */
fun hermesOntologySpine(facts: HermesOntologyFacts): HermesOntologySpine {
    val ordered = Array(facts.size) { i -> facts[i] }
    ordered.sortBy { it.canonicalLine() }
    val lines: Series<String> = ordered α { it.canonicalLine() }
    val text = buildString {
        lines.view.forEachIndexed { i, line ->
            if (i > 0) append('\n')
            append(line)
        }
    }
    val spine = LineCas.spine(text)
    val emits: Series<Emit<String, Int>> = ordered α { it.keySegments() j 1 }
    return lines j (spine j emits)
}

/** Rehydrate only the trimmed structural spine from a prior report's canonical lines. */
fun trimmedOntologyLineSpine(lines: Series<String>): LineSpine = LineCas.spine(buildString {
    lines.view.forEachIndexed { i, line ->
        if (i > 0) append('\n')
        append(line.trim())
    }
})

/** Semantic zoom, not character-prefix zoom. */
fun HermesOntologySpine.zoom(aperture: LineAperture): Level<String, Int> {
    val depth = when (aperture) {
        LineAperture.L0 -> 1 // ready | blocked | deferred
        LineAperture.L1 -> 2 // blocker or upstream/sleeve
        LineAperture.L2 -> 3 // top-level Python package
        LineAperture.L3 -> Int.MAX_VALUE // complete module path
    }
    return emits.groupLevel(depth, Count)
}

data class HermesOntologyDelta(
    val previousCid: String?,
    val currentCid: String,
    val added: Int,
    val removed: Int,
    val proximity: Double,
)

/** Daily drift measured on trimmed, neighbor-stamped ontology lines. */
fun HermesOntologySpine.deltaFrom(previous: LineSpine?): HermesOntologyDelta {
    if (previous == null) return HermesOntologyDelta(null, cid.hex, lineSpine.size, 0, 0.0)
    val oldIndex = LineCasIndex().also { it.ingestSpine(previous) }
    val currentIndex = LineCasIndex().also { it.ingestSpine(lineSpine) }
    return HermesOntologyDelta(
        previousCid = LineCas.spineCid(previous).hex,
        currentCid = cid.hex,
        added = oldIndex.residualsOf(lineSpine).size,
        removed = currentIndex.residualsOf(previous).size,
        proximity = LineCas.proximity(lineSpine, previous),
    )
}
