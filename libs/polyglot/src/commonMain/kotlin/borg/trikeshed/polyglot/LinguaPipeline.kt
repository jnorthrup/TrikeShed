package borg.trikeshed.polyglot

import borg.trikeshed.lib.TypeEvidence
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.*
import borg.trikeshed.parse.interop.DescriptorFragment
import borg.trikeshed.parse.interop.rowVecTree

/* ═══════════════════════════════════════════════════════════════════════════
 *  Polyglot pipeline — four-stage funnel from source text to MLIR-ready blocks.
 *
 *  Stage 0: DETECT       Speculative language classification.
 *                         All N LangClassifiers run sequentially. Winner selected by
 *                         TypeEvidence confidence score.
 *  Stage 1: PARSE         Winning LangParser → UniversalAst → SourceFragment tree.
 *  Stage 2: CLASSIFY      TypeEvidence scan per fragment → classified SourceFragment tree.
 *  Stage 3: UNIFY         SourceFragment → DescriptorFragment → Cursor<RowVec>.
 *  Stage 4: MAP           Cursor algebra (filter/project/join) → region blocks.
 *  Stage 5: LOWER         nodeToMlir() mapping → MLIR ops → JIT compilation.
 *
 *  Each stage runs in a ParseScope under SupervisorJob. Stages fan out
 *  concurrently where span independence allows. Classification (stage 0)
 *  runs sequentially across languages, best confidence wins.
 * ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Stage 0: Speculative language detection.
 *
 * Feeds source text to all registered [LangClassifier] instances sequentially.
 * Results are ranked by TypeEvidence confidence against each language's
 * reference fingerprint. The top result selects the parser for stage 1.
 *
 * Returns the best ClassificationResult, or null if registry is empty.
 */
fun detect(registry: LangRegistry, source: Series<Char>): ClassificationResult? =
    registry.bestMatch(source)

/**
 * Stage 0b: Detect with a confidence floor.
 *
 * Returns null if no language exceeds [minConfidence], preventing
 * false-positive parses on unrecognized input.
 */
fun detectOrNull(registry: LangRegistry, source: Series<Char>, minConfidence: Double = 0.3): ClassificationResult? {
    val best = registry.bestMatch(source) ?: return null
    return if (best.confidence >= minConfidence) best else null
}

/**
 * Stage 1: Parse source text through the winning language's parser.
 *
 * The parser is resolved from [LangEntry] by looking up the registered
 * [LangParser] for the detected language. The parser emits a [UniversalAst].
 */
object LangParsers {
    private val parsers = mutableMapOf<LangId, LangParser>()
    fun register(parser: LangParser) { parsers[parser.lang] = parser }
    fun get(lang: LangId): LangParser? = parsers[lang]
}

/**
 * Stage 1: Parse source text through the winning language's parser.
 *
 * The parser is resolved from [LangEntry] by looking up the registered
 * [LangParser] for the detected language. The parser emits a [UniversalAst].
 */
fun parse(lang: LangId, source: Series<Char>): Result<UniversalAst> {
    val parser = LangParsers.get(lang) ?: throw NotImplementedError("No LangParser registered for $lang")
    return try {
        Result.success(parser.parse(source))
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

/**
 * Stage 2: Run TypeEvidence classification over every SourceFragment.
 *
 * Each fragment's source span is sampled through TypeEvidence.sample(),
 * populating [SourceFragment.evidence]. This is the character-class
 * fingerprint layer — XOR bitmask extraction from CHAR_CATEGORY[128].
 */
fun classify(ast: UniversalAst, source: Series<Char>): UniversalAst {
    fun rec(sf: SourceFragment): SourceFragment {
        val sample = source[sf.span.a until sf.span.b]
        val ev = TypeEvidence.sample(sample)
        val children = sf.children α { rec(it) }
        return sf.copy(evidence = ev, children = children)
    }
    return UniversalAst(ast.lang, rec(ast.root), ast.diagnostics)
}

/**
 * Stage 3: Unify SourceFragment tree into DescriptorFragments.
 *
 *   SourceFragment.span   → OpaqueExtent
 *   SourceFragment.kind   → TypeMemento
 *   SourceFragment.evidence → DescriptorFragment.evidence
 *   children preserved, not flattened.
 */
fun unify(ast: UniversalAst): DescriptorFragment {
    fun rec(sf: SourceFragment, depth: Int): DescriptorFragment {
        val evidence = sf.evidence
        val memento = TypeEvidence.deduceMemento(evidence)
        val children = sf.children.view.map { rec(it, depth + 1) }
        return DescriptorFragment(sf.name, depth, memento, evidence, null, children)
    }
    return rec(ast.root, 0)
}

/**
 * Stage 4: Map DescriptorFragment tree into region blocks via ParseScope fanout.
 *
 * Each DescriptorFragment becomes a ParseScope with its OpaqueExtent as the span.
 * Child scopes fan out concurrently under SupervisorJob. Sealed scopes = immutable blocks.
 */
fun mapRegions(fragment: DescriptorFragment): Cursor {
    val rows = fragment.rowVecTree().toList()
    return rows.size j { rows[it] }
}

/**
 * End-to-end pipeline: source text → Region Blocks (Cursor).
 *
 * Four stages, each a suspend function participating in ParseScope fanout.
 * The entire pipeline runs under a single root SupervisorJob scope.
 *
 * Note: Stage 5 (MLIR lowering) has been moved out of this pipeline.
 *
 * Stage 0 (detect) fans out to all N languages. The winner's parser
 * drives stages 1–5. If detection confidence is below [minConfidence],
 * the pipeline returns null (unrecognized input).
 */
suspend fun pipeline(
    registry: LangRegistry,
    source: Series<Char>,
    minConfidence: Double = 0.3,
) {
    val detection = detectOrNull(registry, source, minConfidence)
        ?: return // unrecognized input — no language matched

    parse(detection.lang, source)
        .map { ast -> classify(ast, source) }
        .map { ast -> unify(ast) }
        .map { frag -> mapRegions(frag) }
}
