@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.polyglot

import borg.trikeshed.collections.s_
import borg.trikeshed.parse.evidence.TypeEvidence
import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.j
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.lib.j

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
    val children: Series<SourceFragment> = Join.emptySeriesOf(),
    val meta: NodeMeta = NodeMeta(),
) {
    /** RowVec projection for the Cursor algebra. */
    fun toRowVec(): RowVec {
        val values: Series<Any?> = s_[
            lang.label,
            span.a,
            span.b,
            kind.name,
            name ?: "",
            TypeEvidence.deduceMemento(evidence).label,
            meta.visibility ?: "",
            meta.mutability ?: "",
            meta.lifetime ?: "",
            meta.async,
            meta.generic,
            meta.extern
        ]
        val metas: Series<`ColumnMeta↻`> =
            (s_[/*column metas*/
                "lang" j IOMemento.IoString,
                "spanStart" j IOMemento.IoInt,
                "spanEnd" j IOMemento.IoInt,
                "kind" j IOMemento.IoString,
                "name" j IOMemento.IoString,
                "deducedType" j IOMemento.IoString,
                "meta_visibility" j IOMemento.IoString,
                "meta_mutability" j IOMemento.IoString,
                "meta_lifetime" j IOMemento.IoString,
                "meta_async" j IOMemento.IoBoolean,
                "meta_generic" j IOMemento.IoBoolean,
                "meta_extern" j IOMemento.IoBoolean,
            ] as Series<ColumnMeta>) α { it.leftIdentity }
        return values j metas
    }

    /** Flatten to a depth-first RowVec sequence. */
    fun flatten(): Sequence<RowVec> = sequence<RowVec> {
        yield(toRowVec())
        children.view.forEach { yieldAll(it.flatten()) }
    }

    /** Project children as lazy joins (index into parent's span). */
    fun childSpans(): Series<Twin<Int>> =
        children α { it.span }
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
    val diagnostics: Series<String> = Join.emptySeriesOf(),
) {
    fun toCursor(): borg.trikeshed.cursor.Cursor {
        val rows = root.flatten().toList()
        return rows.toSeries()
    }
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
