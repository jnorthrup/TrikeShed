package borg.trikeshed.miniduck.columnar

import borg.trikeshed.test.TODOError

class Lz4Index : IndexPlugin {
    override fun openIndexCursor(blockHead: Long, codec: String): IndexCursor {
        throw TODOError("Lz4Index not yet implemented")
    }
}
