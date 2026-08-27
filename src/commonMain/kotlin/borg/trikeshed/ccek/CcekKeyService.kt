package borg.trikeshed.ccek

import kotlin.coroutines.CoroutineContext

/**
 * Simple KeyedService that carries a CCEK key string as a coroutine context element.
 * Used by RequestFactory dispatch to bind X-CCEK-Key into the coroutine context.
 */
data class CcekKeyService(val keyString: String) : KeyedService {
    companion object Key : CoroutineContext.Key<CcekKeyService>
    override val key: CoroutineContext.Key<*> get() = Key
    override fun toString(): String = "CcekKeyService($keyString)"
}
