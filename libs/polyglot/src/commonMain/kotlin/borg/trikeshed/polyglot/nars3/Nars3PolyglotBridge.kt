package borg.trikeshed.polyglot.nars3

import borg.trikeshed.lib.*
import borg.trikeshed.collections.s_
import borg.trikeshed.polyglot.*
import borg.trikeshed.parse.kursive.NarsiveElement
import borg.trikeshed.parse.kursive.NarsiveElementKind
import borg.trikeshed.parse.kursive.nars3.*

/**
 * Bridges Polyglot UniversalAst fragments with NARS3 Machine Atoms.
 * This allows syntax trees to be processed as part of the NARS3 exploration.
 */
object Nars3PolyglotBridge {

    fun fragmentToAtom(fragment: SourceFragment, budget: Nars3Budget): Nars3Atom {
        val kindLabel = fragment.kind.name
        val element = NarsiveElement(NarsiveElementKind.UNKNOWN, 0 j 0, kindLabel.toSeries())
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
        val atoms = mutableListOf<Nars3Atom>()
        val defaultBudget = Nars3Budget(0.5f, 0.5f, 0.5f)

        fun walk(node: SourceFragment) {
            atoms.add(fragmentToAtom(node, defaultBudget))
            node.children.view.forEach { walk(it) }
        }

        walk(ast.root)
        return atoms.toSeries()
    }
}
