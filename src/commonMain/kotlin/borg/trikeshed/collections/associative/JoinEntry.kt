@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.common.collections.associative


import borg.trikeshed.lib.Join
import kotlin.jvm.JvmInline

@JvmInline
value class JoinEntry<A, B>(val join: Join<A, B>) : Map.Entry<A, B> {
    override val key: A get() = join.a
    override val value: B get() = join.b
    override fun toString(): String = join.run { "(${_root_ide_package_.borg.trikeshed.lib.Join.a}, ${_root_ide_package_.borg.trikeshed.lib.Join.b})" }
}


inline val <A, B> Join<A, B>.entry: borg.trikeshed.common.collections.associative.JoinEntry<A, B>
    get() = _root_ide_package_.borg.trikeshed.common.collections.associative.JoinEntry(
        this
    )

