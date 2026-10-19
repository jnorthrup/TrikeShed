package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.relaxfactory.RequestFactoryProxy
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A borrowed provider facet. Its host owns its lifetime; an invocation never closes it. */
class LcncService<T>(key: LcncServiceKey<T>, val value: T) : AbstractCoroutineContextElement(key)

abstract class LcncServiceKey<T>(private val name: String) : CoroutineContext.Key<LcncService<T>> {
    operator fun invoke(value: T): LcncService<T> = LcncService(this, value)

    suspend fun require(): T = checkNotNull(currentCoroutineContext()[this]) {
        "Missing LCNC service: $name"
    }.value

    override fun toString(): String = name
}

object ProjectCorpusKey : LcncServiceKey<ProjectCorpus>("ProjectCorpusKey")
object PromptReadsKey : LcncServiceKey<PromptReads>("PromptReadsKey")
object AgentRunsKey : LcncServiceKey<AgentRuns>("AgentRunsKey")
object AgentRunIdKey : LcncServiceKey<() -> String>("AgentRunIdKey")
object RequestFactoryProxyKey : LcncServiceKey<RequestFactoryProxy>("RequestFactoryProxyKey")
object SurfaceCallKey : LcncServiceKey<SurfaceNodes.Call>("SurfaceCallKey")

/** Required services and their supplied defaults, without exposing provider values. */
interface LcncServiceBinding {
    val requiredKeys: Series<CoroutineContext.Key<*>>
    val providedKeys: Series<CoroutineContext.Key<*>>

    /** Borrow the composed bindings for observation without dispatching the node or closing providers. */
    suspend fun <T> withServices(body: suspend () -> T): T
}

class LcncOwnerJob(val job: Job?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LcncOwnerJob>
}

/**
 * Install the caller's element or the host's default and resolve the actual provider inside
 * that context. This borrows even lifecycle-bearing elements: LcncNodeElement owns the
 * operation scope, while the host remains responsible for shared service open/drain/close.
 */
fun <E : CoroutineContext.Element> boundLcnc(
    defaultElement: E,
    body: suspend (E, LcncNode, Map<String, Any?>) -> Map<String, Any?>,
): LcncNodeRunner {
    val key = serviceKey(defaultElement)
    return boundLcnc(defaultElement, LcncNodeRunner { node, inputs ->
        body(checkNotNull(currentCoroutineContext()[key]), node, inputs)
    })
}

/** Compose bindings without re-entering the delegate's public invocation boundary. */
fun <E : CoroutineContext.Element> boundLcnc(defaultElement: E, delegate: LcncNodeRunner): LcncNodeRunner =
    object : LcncNodeRunner, LcncServiceBinding {
        private val key = serviceKey(defaultElement)
        override val requiredKeys: Series<CoroutineContext.Key<*>> =
            prependKey(key, (delegate as? LcncServiceBinding)?.requiredKeys)
        override val providedKeys: Series<CoroutineContext.Key<*>> =
            prependKey(key, (delegate as? LcncServiceBinding)?.providedKeys)

        override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> =
            withBinding { delegate.execute(node, inputs) }

        override suspend fun sourceDebugging(node: LcncNode, inputs: Map<String, Any?>): Boolean =
            withBinding { delegate.sourceDebugging(node, inputs) }

        override fun outputSources(node: LcncNode, inputs: Map<String, Any?>): Map<String, String> =
            delegate.outputSources(node, inputs)

        override val isPure: Boolean get() = delegate.isPure

        override fun retainedOutputs(node: LcncNode, inputs: Map<String, Any?>,
                                     retainedInputs: Map<String, Any?>, outputs: Map<String, Any?>): Map<String, Any?> =
            delegate.retainedOutputs(node, inputs, retainedInputs, outputs)

        override fun retainedOrigins(node: LcncNode, inputs: Map<String, Any?>,
                                     inputOrigins: Map<String, LcncBindingOrigins>, outputs: Map<String, Any?>,
                                     terms: LcncBindingTerms): Map<String, LcncBindingOrigins> =
            delegate.retainedOrigins(node, inputs, inputOrigins, outputs, terms)

        override suspend fun <T> withServices(body: suspend () -> T): T = withBinding {
            val services = delegate as? LcncServiceBinding
            if (services == null) body() else services.withServices(body)
        }

        private suspend fun <T> withBinding(body: suspend () -> T): T {
            val current = currentCoroutineContext()
            val bound = current[key] ?: defaultElement
            val ownerJob = current[LcncOwnerJob]?.job ?: current[Job]
            return lcncContext(bound + LcncOwnerJob(ownerJob), body)
        }
    }

/** Preserve the body failure without changing withContext's child cancellation and join. */
internal suspend fun <T> lcncContext(context: CoroutineContext, body: suspend () -> T): T {
    var original: Throwable? = null
    try {
        return withContext(context) {
            try { body() } catch (failure: Throwable) {
                original = failure
                throw failure
            }
        }
    } catch (failure: Throwable) {
        // Coroutine debug stack recovery can copy a throwable at the context boundary.
        throw original ?: failure
    }
}

// Element erases its key's result type; each element's own key supplies that type identity.
@Suppress("UNCHECKED_CAST")
private fun <E : CoroutineContext.Element> serviceKey(element: E): CoroutineContext.Key<E> =
    element.key as CoroutineContext.Key<E>

private fun prependKey(
    key: CoroutineContext.Key<*>,
    rest: Series<CoroutineContext.Key<*>>?,
): Series<CoroutineContext.Key<*>> = (1 + (rest?.size ?: 0)) j { i ->
    if (i == 0) key else rest!![i - 1]
}
