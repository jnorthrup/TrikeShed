package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * BitTorrent v2 + uTP transport as a reactor element — the daemon-facing
 * contract. The engine (piece wire, tracker HTTP, uTP) is the platform
 * actual's; the daemon names only this shape.
 */
expect class TorrentElement(
    parentJob: Job?,
    reactorContext: CoroutineContext,
) : AsyncContextElement {
    override val key: CoroutineContext.Key<*>
    override suspend fun open()
    override suspend fun close()
}
