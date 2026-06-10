package borg.trikeshed.miniduck.columnar

interface IndexPlugin {
    fun openIndexCursor(blockHead: Long, codec: String): IndexCursor
}
