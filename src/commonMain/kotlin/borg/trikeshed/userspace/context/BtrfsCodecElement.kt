package borg.trikeshed.userspace.context

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.nio.platform.spi.CommonPlatformCodec
import borg.trikeshed.userspace.nio.platform.spi.PlatformCodec
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * Btrfs little-endian codec context element.
 *
 * Btrfs on-disk fields are little-endian. This element keeps that codec in
 * CCEK context separately from PlatformCodec.wireCodec, which is network-order.
 */
open class BtrfsCodecElement(
    override val supervisor: CompletableJob = SupervisorJob(),
) : AsyncContextElement(parentJob = null) {
    override val key: CoroutineContext.Key<*> get() = AsyncContextKey.BtrfsCodecKey

    val codec: PlatformCodec = CommonPlatformCodec(littleEndian = true)

    override suspend fun close() {
        if (state.isLessThan(ElementState.CLOSED)) {
            supervisor.cancel()
            state = ElementState.CLOSED
        }
    }
}
