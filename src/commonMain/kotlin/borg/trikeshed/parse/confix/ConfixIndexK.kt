@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.isam.meta.IOMemento

// ── ConfixIndexK — facet keys for ConfixIndex FacetedRow ──────────
//
// ConfixIndex = FacetedRow<ConfixIndexK> wraps the flat parse geometry
// produced by Syntax.scan / scan0 into a single faceted row.
//
// Each facet is a typed projection keyed by an OpK subclass:
//
//   Spans         → Series<Twin<Int>>     byte offsets per token
//   Tags          → Series<IOMemento>     type discriminant per token
//   Depths        → Series<Int>           nesting depth per token
//   DirectChildren → (Int) → Series<Int>  parent → child indices
//   TreeCursor    → Cursor                recursive tree of RowVec
//   KeyToChild    → (CharSequence) → Int? key name → token index
//
// ConfixKit already references these facets; this file defines them.

sealed class ConfixIndexK<out R> : OpK<R>() {
    data object Spans          : ConfixIndexK<Series<Twin<Int>>>()
    data object Tags           : ConfixIndexK<Series<IOMemento>>()
    data object Depths         : ConfixIndexK<Series<Int>>()
    data object DirectChildren : ConfixIndexK<(Int) -> Series<Int>>()
    data object TreeCursor     : ConfixIndexK<Cursor>()
    data object KeyToChild     : ConfixIndexK<(CharSequence) -> Int?>()
}
