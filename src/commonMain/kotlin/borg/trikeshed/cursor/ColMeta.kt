package borg.trikeshed.cursor

import borg.trikeshed.lib.Join


typealias ColMeta = Join<String, TypeMemento>

//mix-in for name
val ColMeta.name: String get() = this.a

//mix-in for type
val ColMeta.type: TypeMemento get() = this.b
