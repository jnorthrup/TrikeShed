package borg.trikeshed.couch.btrfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.tinybtrfs.BPlusTree
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableJob

class BtrfsSandboxElement(
    val btree: BPlusTree<Long, String> = BPlusTree(order = 16)
) : AsyncContextElement() {
    companion object Key : CoroutineContext.Key<BtrfsSandboxElement>
    override val key: CoroutineContext.Key<*> get() = Key

    // Explicit SupervisorJob orchestration
    override val supervisor: CompletableJob = SupervisorJob(parentJob)
}
