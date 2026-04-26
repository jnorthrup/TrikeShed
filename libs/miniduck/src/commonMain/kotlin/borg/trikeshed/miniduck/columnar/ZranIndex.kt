package borg.trikeshed.miniduck.columnar

import borg.trikeshed.test.TODOError

class ZranIndex : IndexPlugin {
    override fun openIndexCursor(blockHead: Long, codec: String): IndexCursor {
        throw TODOError("ZranIndex not yet implemented")
    }
}
