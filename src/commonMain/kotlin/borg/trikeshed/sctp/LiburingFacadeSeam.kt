package borg.trikeshed.sctp

import borg.trikeshed.lib.Series

interface LiburingFacadeSeam {
    fun submitBatch(batch: Series<ByteArray>)
    fun completeBatch(): Int
}
