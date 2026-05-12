@file:Suppress("EnumEntryName", "UNCHECKED_CAST")

package borg.trikeshed.polyglot

import borg.trikeshed.collections.s_
import borg.trikeshed.parse.evidence.TypeEvidence
import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.j
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlin.math.abs

/* ═══════════════════════════════════════════════════════════════════════════
 *  Polyglot taxonomy — speculative language classification.
 *
 *  Every registered language carries:
 *    - a reference [LangFingerprint] — TypeEvidence row over its keyword corpus
 *    - a [LangClassifier] — scanner that produces a TypeEvidence row from live source
 *
 *  Classification is speculative: all N classifiers run sequentially. Each emits
 *  a [ClassificationResult] with a confidence score. The highest-scoring result
 *  selects the parser.
 *
 *  The registry emits Series<RowVec> so it participates in Cursor algebra
 *  directly. Fingerprints are additive TypeEvidence rows, not scalar scores.
 * ═══════════════════════════════════════════════════════════════════════════ */

/** Stable identifier for a source language. */
enum class LangId(val label: String) {
    KOTLIN("kotlin"),
    JAVA("java"),
    PYTHON("python"),
    RUST("rust"),
    GO("go"),
    C("c"),
    CPP("cpp"),
    TYPESCRIPT("typescript"),
    JAVASCRIPT("javascript"),
    SWIFT("swift"),
    ZIG("zig"),
    HASKELL("haskell"),
    SCALA("scala"),
    ;

    companion object {
        fun of(label: String): LangId? = entries.find { it.label == label }
    }
}

/**
 * Reference fingerprint: a TypeEvidence row over the language's keyword + operator corpus.
 *
 * Derived by running [TypeEvidence.sample] over a
 * concatenation of every keyword, operator symbol, and common delimiter for
 * the language. This is the "template" that live source scans are compared against.
 *
 * The fingerprint IS a RowVec — 17 columns matching TypeEvidence.toRowVec().
 * This means classification is Cursor-algebraic: compare rows, not scalars.
 */
data class LangFingerprint(
    val evidence: TypeEvidence,
    /** Total character count of the keyword corpus sample. */
    val corpusLength: Int,
) {
    /** RowVec projection — same columns as TypeEvidence.toRowVec(). */
    fun toRowVec(): RowVec {
        val ev = evidence
        val values: Series<Any?> = s_[
            ev.confix,
            ev.digits.toInt(),
            ev.periods.toInt(),
            ev.exponent.toInt(),
            ev.signs.toInt(),
            ev.special.toInt(),
            ev.alpha.toInt(),
            ev.truefalse.toInt(),
            ev.empty.toInt(),
            ev.quotes.toInt(),
            ev.dquotes.toInt(),
            ev.whitespaces.toInt(),
            ev.backslashes.toInt(),
            ev.linefeed.toInt(),
            ev.maxColumnLength.toInt(),
            if (ev.minColumnLength == UShort.MAX_VALUE) 0 else ev.minColumnLength.toInt(),
            TypeEvidence.deduceMemento(ev).label,
            corpusLength
        ]
        val meta: Series<`ColumnMeta↻`> =
            (LANG_FP_COLUMNS.entries α { it.columnMeta.leftIdentity }) as Series<`ColumnMeta↻`>
        return values j meta
    }


    enum class LANG_FP_COLUMNS(ioMemento: IOMemento) {
        confix(IOMemento.IoString),
        digits(IOMemento.IoInt),
        periods(IOMemento.IoInt),
        exponent(IOMemento.IoInt),
        signs(IOMemento.IoInt),
        special(IOMemento.IoInt),
        alpha(IOMemento.IoInt),
        truefalse(IOMemento.IoInt),
        empty(IOMemento.IoInt),
        quotes(IOMemento.IoInt),
        dquotes(IOMemento.IoInt),
        whitespaces(IOMemento.IoInt),
        backslashes(IOMemento.IoInt),
        linefeed(IOMemento.IoInt),
        maxColumnLength(IOMemento.IoInt),
        minColumnLength(IOMemento.IoInt),
        deducedType(IOMemento.IoString),
        corpusLength(IOMemento.IoInt), ;

        val columnMeta: ColumnMeta = name j ioMemento
    }
}

/**
 * Scans live source text and produces a TypeEvidence row.
 *
 * Each registered language provides one classifier. The classifier runs
 * the source text through the same TypeEvidence.sample() pipeline used
 * for the reference fingerprint, producing a row that can be compared
 * directly (column-by-column delta) against the reference.
 */
fun interface LangClassifier {
    /** Scan source text and return a TypeEvidence row. */
    fun classify(source: Series<Char>): TypeEvidence
}

/**
 * Result of a speculative language classification.
 *
 * [evidence] is the live TypeEvidence row from the source scan.
 * [confidence] is the match score against the reference fingerprint (0.0–1.0).
 * Higher confidence = better match.
 */
data class ClassificationResult(
    val lang: LangId,
    val evidence: TypeEvidence,
    /** 0.0–1.0 match confidence against this language's reference fingerprint. */
    val confidence: Double,
)

/**
 * Compute confidence as the normalized inverse delta between two TypeEvidence rows.
 *
 * For each of the 14 numeric counters, compute |live - ref| / max(live, ref, 1).
 * Average across all counters, then invert: 1.0 - averageDelta.
 * Returns 0.0–1.0 where 1.0 is a perfect match.
 */
fun confidence(live: TypeEvidence, ref: TypeEvidence): Double {
    val liveVals: Series<Double> = s_[
        live.digits.toDouble(),
        live.periods.toDouble(),
        live.exponent.toDouble(),
        live.signs.toDouble(),
        live.special.toDouble(),
        live.alpha.toDouble(),
        live.truefalse.toDouble(),
        live.empty.toDouble(),
        live.quotes.toDouble(),
        live.dquotes.toDouble(),
        live.whitespaces.toDouble(),
        live.backslashes.toDouble(),
        live.linefeed.toDouble(),
        live.maxColumnLength.toDouble(),
        if (live.minColumnLength == UShort.MAX_VALUE) 0.0 else live.minColumnLength.toDouble()
    ]

    var liveSum = 0.0
    liveVals.view.forEach { liveSum += it }
    if (liveSum == 0.0) return 0.0

    val refVals: Series<Double> = s_[
        ref.digits.toDouble(),
        ref.periods.toDouble(),
        ref.exponent.toDouble(),
        ref.signs.toDouble(),
        ref.special.toDouble(),
        ref.alpha.toDouble(),
        ref.truefalse.toDouble(),
        ref.empty.toDouble(),
        ref.quotes.toDouble(),
        ref.dquotes.toDouble(),
        ref.whitespaces.toDouble(),
        ref.backslashes.toDouble(),
        ref.linefeed.toDouble(),
        ref.maxColumnLength.toDouble(),
        if (ref.minColumnLength == UShort.MAX_VALUE) 0.0 else ref.minColumnLength.toDouble()
    ]

    val diffs: Series<Double> = (liveVals zip refVals) α { (l: Double, r: Double) ->
        val denom = maxOf(1.0, maxOf(l, r))
        abs(l - r) / denom
    }
    val avg = if (diffs.isEmpty()) 1.0 else {
        var sum = 0.0
        diffs.view.forEach { sum += it }
        sum / diffs.size
    }
    val score = 1.0 - avg
    return when {
        score < 0.0 -> 0.0
        score > 1.0 -> 1.0
        else -> score
    }
}

/**
 * A registered language with its fingerprint, classifier, and parser hint.
 *
 * The registry is a Series<LangEntry> projected/filtered/joined through
 * Cursor algebra. Add entries via [register].
 */
data class LangEntry(
    val id: LangId,
    val fingerprint: LangFingerprint,
    val classifier: LangClassifier,
    /** Canonical file extensions (e.g. [".kt", ".kts"]) — fallback, not primary. */
    val extensions: Series<String>,
    /** Canonical shebang line prefix, or null. */
    val shebang: String?,
) {
    /**
     * Run classification on live source text.
     * Returns the TypeEvidence row + confidence against the reference fingerprint.
     */
    fun classify(source: Series<Char>): ClassificationResult {
        val ev = classifier.classify(source)
        val conf = confidence(ev, fingerprint.evidence)
        return ClassificationResult(id, ev, conf)
    }
}

interface LangRegistry {
    fun register(
        id: LangId,
        fingerprint: LangFingerprint,
        classifier: LangClassifier,
        extensions: Series<String>,
        shebang: String? = null,
    ): LangEntry

    fun all(): Series<LangEntry>
    fun reset()
    fun series(): Series<LangEntry>
    fun byExtension(ext: String): LangEntry?
    fun byId(id: LangId): LangEntry?
    fun classifyAll(source: Series<Char>): Series<ClassificationResult>
    fun bestMatch(source: Series<Char>): ClassificationResult?
}

/** Thread-safe append-only language registry implementation. */
class ConcurrentLangRegistry : LangRegistry {
    private val entries = mutableListOf<LangEntry>()
    private var sealed = false

    fun seal() {
        sealed = true
    }

    override fun register(
        id: LangId,
        fingerprint: LangFingerprint,
        classifier: LangClassifier,
        extensions: Series<String>,
        shebang: String?,
    ): LangEntry {
        if (sealed) throw IllegalStateException("LangRegistry is sealed and cannot be modified")
        return LangEntry(id, fingerprint, classifier, extensions, shebang).also { entries.add(it) }
    }

    override fun all(): Series<LangEntry> = entries.toSeries()

    /** Reset for test isolation. */
    override fun reset() {
        if (sealed) throw IllegalStateException("LangRegistry is sealed and cannot be reset")
        entries.clear()
    }

    override fun series(): Series<LangEntry> = entries.size j { i: Int -> entries[i] }

    override fun byExtension(ext: String): LangEntry? = entries.find { entry ->
        entry.extensions.view.contains(ext)
    }

    override fun byId(id: LangId): LangEntry? = entries.find { it.id == id }

    /**
     * Speculative classification: run all registered classifiers sequentially.
     *
     * Results are collected and ranked by confidence. The top result wins.
     *
     * Returns ClassificationResults sorted by confidence descending.
     */
    override fun classifyAll(source: Series<Char>): Series<ClassificationResult> {
        val results = entries.map { it.classify(source) }.sortedByDescending { it.confidence }
        return results.toSeries()
    }

    /** Top-ranked classification, or null if registry is empty. */
    override fun bestMatch(source: Series<Char>): ClassificationResult? =
        classifyAll(source).firstOrNull()
}
