package borg.trikeshed.cursor

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

/**
 * Column metadata as a Join of name and type memento.
 */
typealias ColumnMeta = Join<String, TypeMemento>

/** Factory for ColumnMeta as Join<String, TypeMemento> */
fun ColumnMeta(name: String, type: TypeMemento): ColumnMeta = name j type

// mix-in for name
val ColumnMeta.name: String get() = this.a

// mix-in for type
val ColumnMeta.type: TypeMemento get() = this.b
