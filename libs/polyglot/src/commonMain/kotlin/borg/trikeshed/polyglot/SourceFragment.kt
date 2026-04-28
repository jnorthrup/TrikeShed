package borg.trikeshed.polyglot

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/* ═══════════════════════════════════════════════════════════════════════════
 *  SourceFragment — the universal AST node intermediate representation.
 *
 *  Every external parser (tree-sitter, javap, LSP) emits SourceFragments.
 *  This is the bridge between language-specific ASTs and the Confix/DescriptorFragment
 *  path algebra. A SourceFragment carries:
 *
 *    - [lang]: which language produced this node
 *    - [span]: source byte-range (Twin<Int>)
 *    - [kind]: node classification via TypeEvidence fingerprint
 *    - [children]: nested SourceFragments (lazy, not eagerly flattened)
 *    - [meta]: optional annotations (visibility, mutability, lifetime)
 *
 *  Design bias: preserve provenance. Do not flatten. Child expansion is lazy.
 *  A SourceFragment sequence is materialized into a Cursor<RowVec> via toCursor().
 * ═══════════════════════════════════════════════════════════════════════════ */

/** AST node kind — common denominator across all languages. */
enum class NodeKind {
    // structural
    MODULE,
    NAMESPACE,
    CLASS,
    INTERFACE,
    FUNCTION,
    METHOD,
    VARIABLE,
    PARAMETER,
    FIELD,
    ENUM,
    STRUCT,
    TRAIT,
    IMPL,
    // control flow
    BLOCK,
    RETURN,
    IF,
    LOOP,
    WHILE,
    FOR,
    MATCH,
    TRY,
    THROW,
    // statements / expressions
    ASSIGN,
    STATEMENT,
    EXPRESSION,
    CALL,
    LITERAL,
    BINARY_OP,
    UNARY_OP,
    // types
    TYPE_ANNOTATION,
    TYPE_DECL,
    // module-level
    IMPORT,
    EXPORT,
    // meta
    COMMENT,
    UNKNOWN,
}

/** Optional metadata annotations carried by a SourceFragment. */
data class NodeMeta(
    val visibility: String? = null,   // "public", "private", "pub(crate)", etc.
    val mutability: String? = null,   // "val", "var", "let", "const", "mut"
    val lifetime: String? = null,     // Rust lifetime annotations
    val async: Boolean = false,
    val generic: Boolean = false,
    val extern: Boolean = false,
)

/**
 * A single AST node from any source language.
 *
 * [evidence] is a TypeEvidence sample of the node's source text — this is
 * the character-class fingerprint that feeds classification at every layer
 * of the funnel without reflection or language-specific dispatch.
 */
data class SourceFragment(
    val lang: LangId,
    val span: Twin<Int>,
    val kind: NodeKind,
    val name: String?,
    val evidence: TypeEvidence,
    val children: List<SourceFragment> = emptyList(),
    val meta: NodeMeta = NodeMeta(),
) {
    /** RowVec projection for the Cursor algebra. */
    fun toRowVec(): RowVec = TODO("SourceFragment.toRowVec")

    /** Flatten to a depth-first RowVec sequence. */
    fun flatten(): Sequence<RowVec> = sequence {
        yield(toRowVec())
        for (child in children) yieldAll(child.flatten())
    }

    /** Project children as lazy joins (index into parent's span). */
    fun childSpans(): Series<Twin<Int>> =
        children.size j { i: Int -> children[i].span }
}

/**
 * A universal AST produced by any language parser.
 *
 * Carries a [lang] marker and a root [SourceFragment] tree.
 * Materializes into Cursor<RowVec> via [toCursor].
 */
data class UniversalAst(
    val lang: LangId,
    val root: SourceFragment,
    val diagnostics: List<String> = emptyList(),
) {
    fun toCursor(): borg.trikeshed.cursor.Cursor = TODO("UniversalAst.toCursor")
}

/**
 * Marker interface for external parser backends.
 *
 * Each implementation wraps a specific parser (tree-sitter, javap, go/parser, etc.)
 * and emits a [UniversalAst].
 */
interface LangParser {
    val lang: LangId

    /** Parse source text into a UniversalAst. */
    fun parse(source: Series<Char>): UniversalAst

    /** Parse a single file path. Platform-specific. */
    fun parseFile(path: String): UniversalAst
}
