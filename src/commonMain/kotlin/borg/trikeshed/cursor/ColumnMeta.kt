package borg.trikeshed.cursor

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

/**
 * Column metadata as a Join of name and type memento.
 */
typealias ColumnMeta = Join<CharSequence, TypeMemento>
typealias `ColumnMeta↻` = ()->ColumnMeta

/** Factory for ColumnMeta as Join<CharSequence, TypeMemento> */
fun ColumnMeta(name: CharSequence, type: TypeMemento): ColumnMeta = name j type

// mix-in for name
val ColumnMeta.name: CharSequence get() = this.a

// mix-in for type
val ColumnMeta.type: TypeMemento get() = this.b
