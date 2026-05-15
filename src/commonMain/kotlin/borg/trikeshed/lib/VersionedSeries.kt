package borg.trikeshed.lib

interface   VersionedSeries<T> :Series<T>{
    val version: Long
}
