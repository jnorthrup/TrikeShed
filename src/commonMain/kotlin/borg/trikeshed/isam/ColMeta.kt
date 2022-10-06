package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IOMemento

interface ColMeta {
    val name: String
    val type: IOMemento
}