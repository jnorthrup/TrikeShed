package borg.trikeshed.couch.btrfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.tinybtrfs.BPlusTree

class BtrfsSandboxElement(
    val btree: BPlusTree<Long, String> = BPlusTree(order = 16)
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<BtrfsSandboxElement>()
    override val key: AsyncContextKey<BtrfsSandboxElement> get() = Key
}
