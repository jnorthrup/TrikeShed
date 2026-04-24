package borg.trikeshed.couch.miniduck.columnar

interface IndexPlugin {
    fun openIndexCursor(blockHead: Long, codec: String): IndexCursor
}
