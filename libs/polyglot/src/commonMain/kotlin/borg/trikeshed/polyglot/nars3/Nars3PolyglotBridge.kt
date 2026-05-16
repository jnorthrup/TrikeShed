package borg.trikeshed.polyglot.nars3

import borg.trikeshed.lib.*
import borg.trikeshed.collections.s_
import borg.trikeshed.polyglot.*
import borg.trikeshed.parse.narsive.NarsiveElement
import borg.trikeshed.parse.narsive.NarsiveElementKind
import borg.trikeshed.parse.nars3.*

/**
 * Bridges Polyglot UniversalAst fragments with NARS3 Machine Atoms.
 * This allows syntax trees to be processed as part of the NARS3 exploration.
 */
object Nars3PolyglotBridge {

    fun nodeKindToNarsiveElementKind(kind: NodeKind): NarsiveElementKind = when (kind) {
        NodeKind.MODULE -> NarsiveElementKind.TASK
        NodeKind.NAMESPACE -> NarsiveElementKind.TASK
        NodeKind.CLASS -> NarsiveElementKind.COMPOUND_TERM
        NodeKind.INTERFACE -> NarsiveElementKind.COMPOUND_TERM
        NodeKind.FUNCTION -> NarsiveElementKind.OPERATION
        NodeKind.METHOD -> NarsiveElementKind.OPERATION
        NodeKind.VARIABLE -> NarsiveElementKind.VARIABLE
        NodeKind.PARAMETER -> NarsiveElementKind.VARIABLE
        NodeKind.FIELD -> NarsiveElementKind.VARIABLE
        NodeKind.ENUM -> NarsiveElementKind.COMPOUND_TERM
        NodeKind.STRUCT -> NarsiveElementKind.COMPOUND_TERM
        NodeKind.TRAIT -> NarsiveElementKind.COMPOUND_TERM
        NodeKind.IMPL -> NarsiveElementKind.COMPOUND_TERM
        NodeKind.BLOCK -> NarsiveElementKind.COMPOUND_TERM
        NodeKind.RETURN -> NarsiveElementKind.STATEMENT
        NodeKind.IF -> NarsiveElementKind.STATEMENT
        NodeKind.LOOP -> NarsiveElementKind.STATEMENT
        NodeKind.WHILE -> NarsiveElementKind.STATEMENT
        NodeKind.FOR -> NarsiveElementKind.STATEMENT
        NodeKind.MATCH -> NarsiveElementKind.STATEMENT
        NodeKind.TRY -> NarsiveElementKind.STATEMENT
        NodeKind.THROW -> NarsiveElementKind.STATEMENT
        NodeKind.ASSIGN -> NarsiveElementKind.STATEMENT
        NodeKind.STATEMENT -> NarsiveElementKind.STATEMENT
        NodeKind.EXPRESSION -> NarsiveElementKind.TERM
        NodeKind.CALL -> NarsiveElementKind.OPERATION
        NodeKind.LITERAL -> NarsiveElementKind.TERM
        NodeKind.BINARY_OP -> NarsiveElementKind.OPERATION
        NodeKind.UNARY_OP -> NarsiveElementKind.OPERATION
        NodeKind.TYPE_ANNOTATION -> NarsiveElementKind.TERM
        NodeKind.TYPE_DECL -> NarsiveElementKind.TERM
        NodeKind.IMPORT -> NarsiveElementKind.STATEMENT
        NodeKind.EXPORT -> NarsiveElementKind.STATEMENT
        NodeKind.COMMENT -> NarsiveElementKind.UNKNOWN
        NodeKind.UNKNOWN -> NarsiveElementKind.UNKNOWN
    }

    fun fragmentToAtom(fragment: SourceFragment, budget: Nars3Budget): Nars3Atom {
        val kindLabel = fragment.kind.name
        val element = NarsiveElement(nodeKindToNarsiveElementKind(fragment.kind), 0 j 0, kindLabel.toSeries())
        val isChannelized = fragment.children.size > 0

        return if (isChannelized) {
             ChannelizedAtom(
                id = "polyglot-${fragment.kind.name}-${fragment.span.a}",
                knowledge = s_[element],
                resources = budget
            )
        } else {
             LocalAtom(
                id = "polyglot-${fragment.kind.name}-${fragment.span.a}",
                knowledge = s_[element],
                resources = budget
            )
        }
    }

    fun astToArenaChain(ast: UniversalAst): Series<Nars3Atom> {
        val atoms: SeriesBuffer<Nars3Atom> = SeriesBuffer()
        val defaultBudget = Nars3Budget(0.5f, 0.5f, 0.5f)

        fun walk(node: SourceFragment) {
            atoms.add(fragmentToAtom(node, defaultBudget))
            node.children.view.forEach { walk(it) }
        }

        walk(ast.root)
        return atoms.size j { atoms[it] }
    }
}
