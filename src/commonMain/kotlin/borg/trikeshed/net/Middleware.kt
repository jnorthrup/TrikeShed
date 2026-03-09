package borg.trikeshed.net

import kotlin.coroutines.CoroutineContext

// Middleware function type
typealias Middleware<T> = suspend (T, suspend (T) -> T) -> T

// Create a middleware registry
class MiddlewareRegistry<T>(
    private val middlewares: List<Middleware<T>>
) : CoroutineContext.Element {
    override val key = Key

    suspend fun process(input: T, core: suspend (T) -> T): T {
        return middlewares.foldRight(core) { middleware, next ->
            { value -> middleware(value, next) }
        }(input)
    }

    companion object Key : CoroutineContext.Key<MiddlewareRegistry<*>>
}
