package borg.trikeshed.cursor

import borg.trikeshed.lib.Join


typealias ColumnMeta = Join<String, TypeMemento>

//mix-in for name
val ColumnMeta.name: String get() = this.a

//mix-in for type
val ColumnMeta.type: TypeMemento get() = this.b
