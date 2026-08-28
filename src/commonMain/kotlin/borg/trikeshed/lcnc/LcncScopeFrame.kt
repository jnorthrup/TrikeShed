package borg.trikeshed.lcnc

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
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LcncScopeFrame>

    /** Whether [name] is bound anywhere on the chain — distinguishes bound-null from unbound. */
    fun hasBinding(name: String): Boolean =
        bindings.containsKey(name) || (parent?.hasBinding(name) ?: false)

    /** Nearest-ring-wins binding lookup, walking outward. */
    fun binding(name: String): Any? =
        if (bindings.containsKey(name)) bindings[name] else parent?.binding(name)

    /** Warm base: a node's outputs, resolved outward through the enclosing rings. */
    fun outputsOf(nodeId: String): Map<String, Any?>? =
        outputs[nodeId] ?: parent?.outputsOf(nodeId)

    /** Ring depth — 0 at the root. */
    val depth: Int get() = (parent?.depth ?: -1) + 1
}
