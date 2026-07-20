package borg.trikeshed.cas

import borg.trikeshed.job.ContentId

interface CasReplicationHook {
    suspend fun onPut(cid: ContentId, payload: ByteArray) {}

    object NoOp : CasReplicationHook {
        override suspend fun onPut(cid: ContentId, payload: ByteArray) {}
    }
}
