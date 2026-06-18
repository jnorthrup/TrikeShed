package borg.trikeshed.usersignals.facets

import borg.trikeshed.lib.*
import borg.trikeshed.mutable.MutableSeries
import borg.trikeshed.usersignals.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@DslMarker
annotation class FacetDsl

/**
 * FacetedSignal — a signal with named, lazily-computed projections (facets).
 *
 * Each facet is a pure function Signal<T> → Signal<R>. Facets compose:
 *   signal.facet("uppercase") .facet("trim") .facet("words")
 *
 * Valhalla shapes:
 *   - FacetKey as inline value class (zero-overhead string interning)
 *   - FacetFn as inline function wrapper (no boxing)
 *   - FacetedSignal as @JvmInline value class over MutableSeries<Bind>
 */
inline  class FacetKey(private val interned: String) {
    companion object {
        private val cache = mutableMapOf<String, FacetKey>()
        inline fun <reified T> get(name: String): FacetKey = cache.getOrPut(name) { FacetKey(name) }
        operator fun invoke(name: String) = get(name)
    }

    override fun toString(): String = interned
}

/**
 * Facet function — lazy projection from one signal type to another.
 * 
 * @param I input signal type
 * @param O output signal type
 * Inline to avoid lambda allocation in hot paths.
 */
inline class FacetFn<I : Signal<*>, O : Signal<*>>(
    private val fn: (I) -> O,
) {
    @Suppress("UNCHECKED_CAST")
    operator fun invoke(input: I): O = fn(input) as O
}

/**
 * FacetedSignal — a signal with named facet projections.
 * 
 * Valhalla: value class wrapper over MutableSeries of facet bindings.
 * Facets are cached after first computation.
 */
inline  class FacetedSignal<I : Signal<*>>(
    private val base: I,
    private val facets: MutableSeries<Pair<FacetKey, FacetSignal<*>>> = mutableSeriesOf(),
) : Signal<*> by base {

    /**
     * Add a new facet projection.
     */
    inline fun <reified O : Signal<*>> facet(key: FacetKey, fn: FacetFn<I, O>): FacetedSignal<I> {
        val facetSignal: Signal<O> = fn(base)
        val updated = facets.cowSnapshot().append(key to FacetedSignal.FacetSignal(facetSignal))
        return FacetedSignal(base, updated)
    }

    /** Access a computed facet by key. */
    inline fun <reified O : Signal<*>> getFacet(key: FacetKey): O? =
        facets.cowSnapshot().sequence()
            .firstOrNull { (it as Pair<FacetKey, FacetedSignal.FacetSignal<*>>).first == key }
            ?.let { (it.second as FacetedSignal.FacetSignal<O>).signal }

    /** Get or compute facet lazily with caching. */
    inline fun <reified O : Signal<*>> facet(key: FacetKey, crossinline fn: (I) -> O): FacetedSignal<I> =
        facet(key, FacetFn(fn))

    /** Materialize all facets into a map. */
    fun materialize(): Map<FacetKey, FacetSignal<*>> =
        facets.sequence().associate { (key, signal) -> key to signal }

    data class FacetSignal<S : Signal<*>>(val signal: S)

    companion object {
        inline fun <reified I : Signal<*>> of(base: I): FacetedSignal<I> = FacetedSignal(base)
    }
}

/**
 * VisualTemplate facet — bind template holes to signals.
 * 
 * Creates a facet that renders a visual template with signal bindings.
 */
inline  class TemplateFacet(
    private val template: VisualTemplate,
    private val bindings: Map<String, Signal<*>>,
) {
    fun render(): TemplateOutput = template.render(bindings)

    /** Extract signal map for a subset of holes. */
    fun project(holes: List<String>): Map<String, Signal<*>> =
        holes.associateWith { name -> bindings[name] ?: Signal.Const(name) }
}

/**
 * DSL for building faceted signals.
 * 
 * Usage:
 *   val fs = facetedSignal(baseSignal) {
 *       facet("upper") { it.map { it.uppercase() } }
 *       facet("words") { it.flatMap { it.split(" ") } }
 *   }
 */
inline fun <reified I : Signal<*>> facetedSignal(
    base: I,
    block: FacetBuilder<I>.() -> Unit,
): FacetedSignal<I> = FacetBuilder(base).apply(block).build()

@FacetDsl
class FacetBuilder<I : Signal<*>>(private val base: I) {
    private val facets = mutableListOf<Pair<FacetKey, FacetedSignal.FacetSignal<*>>>()

    inline fun <reified O : Signal<*>> facet(
        key: FacetKey,
        crossinline fn: (I) -> O,
    ): FacetBuilder<I> {
        facets += key to FacetedSignal.FacetSignal(fn(base))
        return this
    }

    inline fun <reified O : Signal<*>> facet(
        name: String,
        crossinline fn: (I) -> O,
    ): FacetBuilder<I> = facet(FacetKey(name), fn)

    fun build(): FacetedSignal<I> = FacetedSignal(base, mutableSeriesOf(*facets.toTypedArray()))
}

/** Predefined common facets for visual templates. */
object Facets {
    
    /** Uppercase transform. */
    inline fun <reified T, reified I : Signal<T>> FacetedSignal<I>.upper(): FacetedSignal<I> =
        facet(FacetKey("upper")) { 
            @Suppress("UNCHECKED_CAST") (this as Signal<String>).map { it.uppercase() } as I 
        }

    /** Lowercase transform. */
    inline fun <reified T, reified I : Signal<T>> FacetedSignal<I>.lower(): FacetedSignal<I> =
        facet(FacetKey("lower")) { 
            @Suppress("UNCHECKED_CAST") (this as Signal<String>).map { it.lowercase() } as I 
        }

    /** Trim whitespace. */
    inline fun <reified T, reified I : Signal<T>> FacetedSignal<I>.trim(): FacetedSignal<I> =
        facet(FacetKey("trim")) { 
            @Suppress("UNCHECKED_CAST") (this as Signal<String>).map { it.trim() } as I 
        }

    /** Split into words. */
    inline fun <reified T, reified I : Signal<T>> FacetedSignal<I>.words(): FacetedSignal<I> =
        facet(FacetKey("words")) { 
            @Suppress("UNCHECKED_CAST") (this as Signal<String>).flatMap { it.split("\\s+".toRegex()).asSequence() } as I 
        }

    /** Template binding — bind template holes to this signal. */
    inline fun <reified I : Signal<*>> FacetedSignal<I>.bindTemplate(
        template: VisualTemplate,
    ): FacetedSignal<I> = facet(TemplatKey(template.id)) { 
        TemplateFacet(template, mapOf(template.holes.firstOrNull()?.key ?: "content" to this))
    }

    /** Word count aggregate. */
    inline fun <reified I : Signal<*>> FacetedSignal<I>.wordCount(): FacetedSignal<I> =
        facet(CountKey("words")) { 
            @Suppress("UNCHECKED_CAST") (this as Signal<String>).map { it.split("\\s+".toRegex()).size.toString() } as I 
        }
}

// Facet keys for common operations
object FacetKeys {
    val UPPER = FacetKey("upper")
    val LOWER = FacetKey("lower") 
    val TRIM = FacetKey("trim")
    val WORDS = FacetKey("words")
    val WORD_COUNT = FossilKey("word_count")
    val TEMPLATE = FacetKey("template")
    
    @JvmInline value class FossilKey(val interned: String) : FacetKey(interned)
    
    inline fun <reified T> TemplatKey(id: T): FacetKey = FacetKey("template.$id")
}

/** Bind a visual template to signals via facet DSL. */
inline fun <reified I : Signal<*>> FacetedSignal<I>.withTemplate(
    template: VisualTemplate,
    block: TemplateFacetBuilder<I>.() -> Unit,
): FacetedSignal<I> {
    val builder = TemplateFacetBuilder<I>(base, template)
    block(builder)
    return builder.build()
}

@FacetDsl
class TemplateFacetBuilder<I : Signal<*>>(private val base: I, private val template: VisualTemplate) {
    private val bindings = mutableMapOf<String, Signal<*>>()

    inline fun <reified T : Signal<*>> bind(hole: TemplateHole<T>, signal: Signal<T>) {
        bindings[hole.key] = signal
    }

    inline fun <reified T : Signal<*>> bind(key: String, signal: Signal<T>) {
        bindings[key] = signal
    }

    fun build(): FacetedSignal<I> {
        val facetSignal = TemplateFacet(template, bindings.toMap())
        return FacetedSignal(base, mutableSeriesOf(FacetKey("template") to FacetedSignal.FacetSignal(facetSignal)))
    }
}