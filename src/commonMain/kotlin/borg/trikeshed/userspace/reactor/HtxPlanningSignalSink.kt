package borg.trikeshed.userspace.reactor

import borg.trikeshed.ccek.KeyedService
import kotlin.coroutines.CoroutineContext

/**
 * CCEK sink boundary for HTX planning signals.
 *
 * Platform-specific integrations such as Hermes implement this service and the
 * commonMain conduit only depends on this boundary.
 */
interface HtxPlanningSignalSink : KeyedService {
    companion object Key : CoroutineContext.Key<HtxPlanningSignalSink>
    override val key: CoroutineContext.Key<*> get() = Key

    suspend fun accept(signal: HtxPlanningSignal): Boolean
}
