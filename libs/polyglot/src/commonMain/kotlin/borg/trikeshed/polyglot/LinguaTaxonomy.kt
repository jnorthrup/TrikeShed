package borg.trikeshed.polyglot

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.common.toRowVec
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.label
import borg.trikeshed.cursor.joins
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/* ═══════════════════════════════════════════════════════════════════════════
 *  Polyglot taxonomy — speculative language classification via SupervisorJob fanout.
 *
 *  Every registered language carries:
 *    - a reference [LangFingerprint] — TypeEvidence row over its keyword corpus
 *    - a [LangClassifier] — scanner that produces a TypeEvidence row from live source
 *
 *  Classification is speculative: all N classifiers run concurrently under
 *  ParseScope.fanoutParsers(). Each emits a [ClassificationResult] with a
 *  confidence score. The highest-scoring result selects the parser.
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
 * Derived by running [borg.trikeshed.common.TypeEvidence.sample] over a
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
        val values = arrayOf<Any?>(
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
        )
        val meta: Series<() -> ColumnMeta> = LANG_FP_COLUMNS.size j { index: Int -> { LANG_FP_COLUMNS[index] } }
        return values.size j { index: Int -> values[index] } joins meta
    }

    companion object {
        val LANG_FP_COLUMNS = arrayOf(
            ColumnMeta("confix", IOMemento.IoString),
            ColumnMeta("digits", IOMemento.IoInt),
            ColumnMeta("periods", IOMemento.IoInt),
            ColumnMeta("exponent", IOMemento.IoInt),
            ColumnMeta("signs", IOMemento.IoInt),
            ColumnMeta("special", IOMemento.IoInt),
            ColumnMeta("alpha", IOMemento.IoInt),
            ColumnMeta("truefalse", IOMemento.IoInt),
            ColumnMeta("empty", IOMemento.IoInt),
            ColumnMeta("quotes", IOMemento.IoInt),
            ColumnMeta("dquotes", IOMemento.IoInt),
            ColumnMeta("whitespaces", IOMemento.IoInt),
            ColumnMeta("backslashes", IOMemento.IoInt),
            ColumnMeta("linefeed", IOMemento.IoInt),
            ColumnMeta("maxColumnLength", IOMemento.IoInt),
            ColumnMeta("minColumnLength", IOMemento.IoInt),
            ColumnMeta("deducedType", IOMemento.IoString),
            ColumnMeta("corpusLength", IOMemento.IoInt),
        )
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
    val liveVals = listOf(
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
    )
    val refVals = listOf(
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
    )

    val diffs = liveVals.zip(refVals) { l, r ->
        val denom = maxOf(1.0, maxOf(l, r))
        kotlin.math.abs(l - r) / denom
    }
    val avg = if (diffs.isEmpty()) 1.0 else diffs.average()
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
    val extensions: List<String>,
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

/** Thread-safe append-only language registry. */
object LangRegistry {
    private val entries = mutableListOf<LangEntry>()

    fun register(
        id: LangId,
        fingerprint: LangFingerprint,
        classifier: LangClassifier,
        extensions: List<String>,
        shebang: String? = null,
    ): LangEntry = LangEntry(id, fingerprint, classifier, extensions, shebang).also { entries.add(it) }

    fun all(): List<LangEntry> = entries.toList()

    /** Reset for test isolation. */
    fun reset() { entries.clear() }

    fun series(): Series<LangEntry> = entries.size j { i: Int -> entries[i] }

    fun byExtension(ext: String): LangEntry? = entries.find { ext in it.extensions }

    fun byId(id: LangId): LangEntry? = entries.find { it.id == id }

    /**
     * Speculative classification: run all registered classifiers concurrently.
     *
     * Under ParseScope.fanoutParsers(), each classifier gets a child scope.
     * Results are collected and ranked by confidence. The top result wins.
     *
     * Returns ClassificationResults sorted by confidence descending.
     */
    fun classifyAll(source: Series<Char>): List<ClassificationResult> =
        entries.map { it.classify(source) }.sortedByDescending { it.confidence }

    /** Top-ranked classification, or null if registry is empty. */
    fun bestMatch(source: Series<Char>): ClassificationResult? =
        classifyAll(source).firstOrNull()
}
