package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.TypeMemento
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.first

interface ColMeta {
    val name: String
    val type: TypeMemento

    companion object {
        fun <T> join(a: ColMeta, b: ColMeta) = Join(a, b)
    }
}