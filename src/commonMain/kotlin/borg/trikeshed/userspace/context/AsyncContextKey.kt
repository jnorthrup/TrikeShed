package borg.trikeshed.userspace.context

import kotlin.coroutines.CoroutineContext
import borg.trikeshed.userspace.btrfs.BtrfsCodecElement

sealed class AsyncContextKey {
    object BtrfsCodecKey : AsyncContextKey(), CoroutineContext.Key<BtrfsCodecElement>
}
