package borg.trikeshed.userspace.context

import borg.trikeshed.context.AsyncContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * CCEK (Coroutine -> Context -> Element -> Key) DSL Builder.
 * Canonicalizes the guardrails for defining and composing architecture elements.
 *
 * Reduces cognitive load by handling context merging, initialization, and
 * safe teardown of AsyncContextElements.
 */
class CcekContextBuilder {
    private var elements = mutableListOf<AsyncContextElement>()

    /**
     * Adds an Element to the context and automatically transitions it to the OPEN state.
     */
    suspend fun <E : AsyncContextElement> element(factory: () -> E): E {
        val el = factory()
        el.open()
        elements.add(el)
        return el
    }

    /**
     * Merges the configured elements into a single CoroutineContext.
     */
    fun build(): CoroutineContext {
        return elements.fold<AsyncContextElement, CoroutineContext>(EmptyCoroutineContext) { acc, el -> acc + el }
    }
}

/**
 * Builds a TrikeShed structured asynchronous coroutine context.
 * Automatically opens all elements added via `element { ... }`.
 */
suspend fun buildReactorContext(block: suspend CcekContextBuilder.() -> Unit): CoroutineContext {
    val builder = CcekContextBuilder()
    builder.block()
    return builder.build()
}

/**
 * Gracefully drains and closes all AsyncContextElements found in the context.
 */
suspend fun closeReactorContext(context: CoroutineContext, keys: List<CoroutineContext.Key<out AsyncContextElement>>) {
    for (key in keys) {
        val element = context[key]
        if (element != null && element.state.isAtLeast(borg.trikeshed.context.ElementState.OPEN)) {
            element.drain()
            element.close()
        }
    }
}
