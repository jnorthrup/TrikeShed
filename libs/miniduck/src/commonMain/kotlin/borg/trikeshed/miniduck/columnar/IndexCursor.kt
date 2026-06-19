package borg.trikeshed.miniduck.columnar

interface IndexCursor {
    fun seek(blockOffset: Long)
    fun next(): Boolean
    fun current(): Long
}
