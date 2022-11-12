package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.TypeMemento

interface ColMeta {
    val name: String
    val type: TypeMemento
}