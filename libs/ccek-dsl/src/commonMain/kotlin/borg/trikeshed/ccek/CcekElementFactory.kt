package borg.trikeshed.ccek

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.context.AsyncContextKey
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * CCEK DSL Factory — Unified builder for Element creation, reuse, and replacement.
 *
 * This replaces the manual "coroutine → context → key → element" assembly pattern
 * with a type-safe, composable factory that handles:
 * - Creation: build new element with lifecycle (CREATED → OPEN → ACTIVE)
 * - Reuse: get existing element from context or create if absent
 * - Replacement: swap element in context with new instance
 *
 * Former manual assembly restricted to: FULL UPDATE TODO
 *
 * Design: Follows kernel algebra (Join/Series/α) and analytical DSL factory patterns.
 * All operations return Join-shaped configuration for algebraic composition.
 */

// ═════════════════════════════════════════════════════════════════════════════
// TYPES: ElementSpec, ReusePolicy, ReplacementPolicy
// ════════════════════════════════════════════════════════════════════════════

/** Policy for how elements are created/reused in context. */
sealed class ReusePolicy<E : AsyncContextElement> {
    /** Always create fresh instance. */
    data class Fresh(val factory: () -> E) : ReusePolicy<E>()
    
    /** Get existing from context, or create via factory. */
    data class ReuseOrCreate(val factory: () -> E) : ReusePolicy<E>()
    
    /** Get existing from context, throw if absent. */
    data class ReuseOnly<E : AsyncContextElement>() : ReusePolicy<E>()
}

/** Policy for element replacement during context update. */
sealed class ReplacementPolicy<E : AsyncContextElement> {
    /** Never replace existing element. */
    object Never : ReplacementPolicy<AsyncContextElement>()
    
    /** Replace if predicate matches (e.g., config changed). */
    data class If(val predicate: (E) -> Boolean) : ReplacementPolicy<E>()
    
    /** Always replace with fresh instance from factory. */
    data class Always(val factory: () -> E) : ReplacementPolicy<E>()
}

/** Specification for a single element in the CCEK context. */
@DslMarker
annotation class CcekDslMarker

@CcekDslMarker
class ElementSpec<E : AsyncContextElement>(
    val key: CoroutineContext.Key<E>,
    val reuse: ReusePolicy<E> = ReusePolicy.Fresh({ error("No factory provided for $key") }),
    val replacement: ReplacementPolicy<E> = ReplacementPolicy.Never,
    val dependencies: List<CoroutineContext.Key<out AsyncContextElement>> = emptyList(),
) {
    companion object {
        inline fun <reified E : AsyncContextElement>(
            key: CoroutineContext.Key<E>,
            crossinline factory: () -> E,
        ): ElementSpec<E> = ElementSpec(key, ReusePolicy.Fresh(factory))
    }
    
    fun reuse(factory: () -> E): ElementSpec<E> =
        copy(reuse = ReusePolicy.ReuseOrCreate(factory))
    
    fun reuseOnly(): ElementSpec<E> =
        copy(reuse = ReusePolicy.ReuseOnly())
    
    fun replaceIf(predicate: (E) -> Boolean): ElementSpec<E> =
        copy(replacement = ReplacementPolicy.If(predicate))
    
    fun replaceAlways(factory: () -> E): ElementSpec<E> =
        copy(replacement = ReplacementPolicy.Always(factory))
    
    fun dependsOn(vararg keys: CoroutineContext.Key<out AsyncContextElement>): ElementSpec<E> =
        copy(dependencies = dependencies + keys.toList())
}

// ════════════════════════════════════════════════════════════════════════════
// FACTORY: CcekElementFactory — the main entry point
// ════════════════════════════════════════════════════════════════════════════

class CcekElementFactory {
    private val specs = mutableMapOf<CoroutineContext.Key<*>, ElementSpec<out AsyncContextElement>>()
    
    @CcekDslMarker
    inline fun <reified E : AsyncContextElement> elementSpec(
        key: CoroutineContext.Key<E>,
        configure: ElementSpec<E>.() -> Unit = {},
    ): CcekElementFactory {
        val spec = ElementSpec(key).apply(configure)
        specs[key] = spec
        return this
    }
    
    @CcekDslMarker
    inline fun <reified E : AsyncContextElement> element(
        key: CoroutineContext.Key<E>,
        crossinline factory: () -> E,
    ): CcekElementFactory = elementSpec(key) { reuse = ReusePolicy.Fresh(factory) }
    
    @CcekDslMarker
    inline fun <reified E : AsyncContextElement> reuse(
        key: CoroutineContext.Key<E>,
        crossinline factory: () -> E,
    ): CcekElementFactory = elementSpec(key) { reuse = ReusePolicy.ReuseOrCreate(factory) }
    
    @CcekDslMarker
    inline fun <reified E : AsyncContextElement> require(
        key: CoroutineContext.Key<E>,
    ): CcekElementFactory = elementSpec(key) { reuse = ReusePolicy.ReuseOnly() }
    
    @CcekDslMarker
    inline fun <reified E : AsyncContextElement> replace(
        key: CoroutineContext.Key<E>,
        crossinline factory: () -> E,
    ): CcekElementFactory = elementSpec(key) { 
        reuse = ReusePolicy.Fresh(factory)
        replacement = ReplacementPolicy.Always(factory)
    }
    
    @CcekDslMarker
    inline fun <reified E : AsyncContextElement> replaceIf(
        key: CoroutineContext.Key<E>,
        crossinline predicate: (E) -> Boolean,
    ): CcekElementFactory = elementSpec(key) {
        reuse = ReusePolicy.ReuseOrCreate { error("Replace path requires existing element") }
        replacement = ReplacementPolicy.If(predicate)
    }
    
    suspend fun build(): CoroutineContext = buildContext(EmptyCoroutineContext)
    
    suspend fun update(context: CoroutineContext): CoroutineContext = buildContext(context)
    
    private suspend fun buildContext(baseContext: CoroutineContext): CoroutineContext {
        var context = baseContext
        val created = mutableMapOf<CoroutineContext.Key<*>, AsyncContextElement>()
        val orderedSpecs = topologicalSort(specs)
        
        for (entry in orderedSpecs) {
            val key = entry.key
            val spec = entry.value
            val existing = context[key] as AsyncContextElement?
            
            val element = when (spec.reuse) {
                is ReusePolicy.Fresh -> {
                    val fresh = spec.reuse.factory()
                    fresh.open()
                    created[key] = fresh
                    fresh
                }
                is ReusePolicy.ReuseOrCreate -> {
                    if (existing != null) existing
                    else {
                        val fresh = spec.reuse.factory()
                        fresh.open()
                        created[key] = fresh
                        fresh
                    }
                }
                is ReusePolicy.ReuseOnly -> existing ?: throw IllegalStateException("Required element $key not found in context")
            }
            context += element
        }
        
        for (entry in orderedSpecs) {
            val key = entry.key
            val spec = entry.value
            val existing = context[key] as AsyncContextElement?
            
            when (spec.replacement) {
                is ReplacementPolicy.If -> {
                    val elem = existing ?: continue
                    if (spec.replacement.predicate(elem)) {
                        elem.drain()
                        val fresh = spec.reuse as? ReusePolicy.Fresh ?: spec.reuse as? ReusePolicy.ReuseOrCreate ?: error("No factory for replacement")
                        val freshElem = fresh.factory()
                        freshElem.open()
                        context = context.minusKey(key) + freshElem
                        created[key] = freshElem
                    }
                }
                is ReplacementPolicy.Always -> {
                    val elem = existing ?: continue
                    elem.drain()
                    val fresh = spec.replacement.factory()
                    fresh.open()
                    context = context.minusKey(key) + fresh
                    created[key] = fresh
                }
                else -> {}
            }
            created[key]?.let { if (it.state == ElementState.CREATED) it.open() }
        }
        return context
    }
    
    private fun topologicalSort(map: Map<CoroutineContext.Key<*>, ElementSpec<out AsyncContextElement>>): List<Map.Entry<CoroutineContext.Key<*>, ElementSpec<out AsyncContextElement>>> {
        val result = mutableListOf<Map.Entry<CoroutineContext.Key<*>, ElementSpec<out AsyncContextElement>>>()
        val visited = mutableSetOf<CoroutineContext.Key<*>>()
        val visiting = mutableSetOf<CoroutineContext.Key<*>>()
        fun visit(key: CoroutineContext.Key<*>) {
            if (key in visited) return
            if (key in visiting) return
            visiting.add(key)
            map[key]?.dependencies?.forEach { visit(it) }
            visiting.remove(key)
            visited.add(key)
            map[key]?.let { result.add(key to it) }
        }
        for (entry in map) visit(entry.key)
        return result
    }
}

suspend fun CcekElementFactory.configure(block: CcekElementFactory.() -> Unit): CoroutineContext =
    also(block).build()

suspend fun ccekContext(block: CcekElementFactory.() -> Unit): CoroutineContext =
    CcekElementFactory().run { block(); build() }

// ════════════════════════════════════════════════════════════════════════════
// REACTOR CHOREOGRAPHICS — Composable reactor pipelines
// ════════════════════════════════════════════════════════════════════════════

@CcekDslMarker
class ReactorPipeline<T : AsyncContextElement>(
    private val stages: MutableList<ElementSpec<out AsyncContextElement>> = mutableListOf()
) {
    @CcekDslMarker
    inline fun <reified E : AsyncContextElement> stage(
        key: CoroutineContext.Key<E>,
        crossinline factory: () -> E,
    ): ReactorPipeline<T> = apply { stages.add(ElementSpec(key, ReusePolicy.Fresh(factory))) }
    
    @CcekDslMarker
    inline fun <reified E : AsyncContextElement> reuseStage(
        key: CoroutineContext.Key<E>,
        crossinline factory: () -> E,
    ): ReactorPipeline<T> = apply { stages.add(ElementSpec(key, ReusePolicy.ReuseOrCreate(factory))) }
    
    @CcekDslMarker
    inline fun <reified E : AsyncContextElement> replaceStage(
        key: CoroutineContext.Key<E>,
        crossinline factory: () -> E,
    ): ReactorPipeline<T> = apply { 
        stages.add(ElementSpec(key, ReusePolicy.Fresh(factory), ReplacementPolicy.Always(factory))) 
    }
    
    fun toFactory(): CcekElementFactory = CcekElementFactory().apply {
        specs.putAll(stages.associate { it.key to it })
    }
    fun build(): CoroutineContext = toFactory().build()
}

suspend fun reactorPipeline(block: ReactorPipeline<*>.() -> Unit): CoroutineContext =
    ReactorPipeline().apply(block).build()

// ════════════════════════════════════════════════════════════════════════════
// PREDEFINED KEY REGISTRY
// ════════════════════════════════════════════════════════════════════════════

object CcekKeyRegistry {
    val NioUserspace = borg.trikeshed.userspace.context.AsyncContextKey.NioUserspaceKey
    val Liburing = borg.trikeshed.userspace.context.AsyncContextKey.LiburingKey
    val FanoutDispatcher = borg.trikeshed.userspace.context.AsyncContextKey.FanoutDispatcherKey
    val BtrfsCodec = borg.trikeshed.userspace.context.AsyncContextKey.BtrfsCodecKey
    
    val KeyPool = KeyPoolKey
    val OperationalDataPool = OperationalDataPoolKey
    val Coordinator = CoordinatorKey
    val GepaOptimizer = GepaOptimizerKey
    
    val all: Series<CoroutineContext.Key<out AsyncContextElement>> = 
        (8).j { i -> when (i) {
            0 -> NioUserspace
            1 -> Liburing
            2 -> FanoutDispatcher
            3 -> BtrfsCodec
            4 -> KeyPool
            5 -> OperationalDataPool
            6 -> Coordinator
            7 -> GepaOptimizer
        }}
}

private object KeyPoolKey : CoroutineContext.Key<Any>
private object OperationalDataPoolKey : CoroutineContext.Key<Any>
private object CoordinatorKey : CoroutineContext.Key<Any>
private object GepaOptimizerKey : CoroutineContext.Key<Any>

// ═════════════════════════════════════════════════════════════════════════════
// EXTENSIONS
// ════════════════════════════════════════════════════════════════════════════

infix fun CoroutineContext.plus(element: AsyncContextElement): CoroutineContext = this + element

fun CoroutineContext.getOrInstall<E : AsyncContextElement>(
    key: CoroutineContext.Key<E>,
    factory: () -> E,
): E {
    val existing = this[key] ?: return factory().also { it.open(); return this.plus(it) as E }
    return existing
}

suspend fun CoroutineContext.drainAndCloseElements(vararg keys: CoroutineContext.Key<out AsyncContextElement>) {
    for (key in keys) {
        val element = this[key] ?: continue
        if (element.state.isAtLeast(ElementState.OPEN) && element.state.isLessThan(ElementState.CLOSED)) {
            element.drain()
            element.close()
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// FULL UPDATE TODO — Legacy manual assembly pattern
// ════════════════════════════════════════════════════════════════════════════

@Deprecated("Use CcekElementFactory DSL instead. See FULL UPDATE TODO above.", ReplaceWith(
    "ccekContext { element(Key) { Factory() } }"
))
@CcekDslMarker
fun deprecatedManualAssembly(vararg elements: AsyncContextElement): CoroutineContext =
    EmptyCoroutineContext.also { ctx ->
        for (e in elements) { e.open(); ctx.plus(e) }
    }
