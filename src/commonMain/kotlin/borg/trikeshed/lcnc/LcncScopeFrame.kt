package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The ring frame — a CCEK citizen BY DEFINITION: CCEK is
 * CoroutineContext.Element.Key, and this is one more Element with a typed
 * Key. E's ONLY job is holding state: this ring's named bindings (the
 * envelope installed at entry) and its node outputs (the warm base). The
 * companion object below is the K — const, never a String; every transform
 * routes through it.
 *
 * Ring entry is `withContext(frame)`, so any suspend runner in the subtree —
 * arbitrarily deep — reads `currentCoroutineContext()[LcncScopeFrame]` with
 * zero plumbing: enclosing state reaches inner code through the context
 * machinery, not the grammar.
 *
 * Lineage note: composition is right-biased replacement — the child frame
 * bumps the parent under this Key in the child's context value. The [parent]
 * pointer is THIS Element's chosen lineage (Job-style), one policy, not
 * machine law; immutable context values make ring-exit restoration free.
 */
class LcncScopeFrame(
    /** Named bindings installed at ring entry — the specialization envelope. */
    val bindings: Map<String, Any?>,
    /** This ring's node outputs — the warm base inner rings read through the chain. */
    val outputs: MutableMap<String, Map<String, Any?>> = LinkedHashMap(),
    /** Identity — the same cid chain ProgramNavigator dives: cache prefix · task address · route. */
    val chain: FrameIdChain,
    /** The enclosing ring; null at the root. Nearest ring shadows on lookup. */
    val parent: LcncScopeFrame? = null,
    val bindingSources: Map<String, String> = emptyMap(),
    /** Bound keys permit retention until an actual typed read removes them. Entry projection fills safe values. */
    val retainedBindings: MutableMap<String, Any?> = LinkedHashMap(bindings),
    /** Finalized after the actual walk, in dependency order. */
    val retainedOutputs: MutableMap<String, Map<String, Any?>> = LinkedHashMap(),
    val retainedReturns: MutableMap<String, Any?> = LinkedHashMap(),
    val retainedBindingOrigins: MutableMap<String, LcncBindingOrigins> = LinkedHashMap(),
    val retainedOutputOrigins: MutableMap<String, Map<String, LcncBindingOrigins>> = LinkedHashMap(),
    val retainedReturnOrigins: MutableMap<String, LcncBindingOrigins> = LinkedHashMap(),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LcncScopeFrame>

    /** The exact output owner observed by a fed wire; later walks never re-resolve its identity. */
    internal fun outputOwner(nodeId: String): LcncScopeFrame? =
        if (outputs.containsKey(nodeId)) this else parent?.outputOwner(nodeId)

    /** Entry projection preserves typed-read removals already made on this actual binding owner. */
    internal fun retainBindings(projected: Map<String, Any?>, origins: Map<String, LcncBindingOrigins> = emptyMap()) {
        val permitted = retainedBindings.keys.toSet()
        retainedBindings.clear()
        retainedBindingOrigins.clear()
        for ((name, value) in projected) if (name in permitted && value !== LcncProjection.Absent) {
            retainedBindings[name] = value
            retainedBindingOrigins[name] = origins[name] ?: emptySeriesOf()
        }
    }

    /** Whether [name] is bound anywhere on the chain — distinguishes bound-null from unbound. */
    fun hasBinding(name: String): Boolean =
        bindings.containsKey(name) || (parent?.hasBinding(name) ?: false)

    /** Nearest-ring-wins binding lookup, walking outward. */
    fun binding(name: String): Any? =
        if (bindings.containsKey(name)) bindings[name] else parent?.binding(name)

    fun bindingOwner(name: String): LcncScopeFrame? =
        if (bindings.containsKey(name)) this else parent?.bindingOwner(name)

    /** Warm base: a node's outputs, resolved outward through the enclosing rings. */
    fun outputsOf(nodeId: String): Map<String, Any?>? =
        outputs[nodeId] ?: parent?.outputsOf(nodeId)

    fun retainedOutputsOf(nodeId: String): Map<String, Any?>? =
        if (outputs.containsKey(nodeId)) retainedOutputs[nodeId] else parent?.retainedOutputsOf(nodeId)

    /** Ring depth — 0 at the root. */
    val depth: Int get() = (parent?.depth ?: -1) + 1
}
