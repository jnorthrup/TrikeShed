package borg.trikeshed.sctp

import borg.trikeshed.lib.Series

class JvmLiburingFacadeSeam : LiburingFacadeSeam {
    override fun submitBatch(batch: Series<ByteArray>) {
        // Implementation stub for JVM
    }

    override fun completeBatch(): Int {
        // Implementation stub for JVM
        return 0
    }
}
