package borg.trikeshed.lib

interface VersionedSeries {
    val version: Long?  // null means external object Identity as version
}