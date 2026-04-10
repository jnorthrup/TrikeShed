package borg.literbike.htxke

/**
 * CCEK Scope - Kotlin CoroutineScope translation
 */

class Scope(
    override val coroutineContext: CoroutineContext = EmptyCoroutineContext
) : CoroutineScope {

    companion object {
        fun new(ctx: CoroutineContext = EmptyCoroutineContext): Scope = Scope(ctx)
    }

    fun context(): CoroutineContext = coroutineContext

    fun child(ctx: CoroutineContext = EmptyCoroutineContext): Scope = Scope(ctx)
}
