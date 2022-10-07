package borg.trikeshed.placeholder.nars.parser

/** a node in a trie of Recognizers
 * a node may have children, and may have cycles
 *
 * The coroutine context tracks all terminal nodes that fire successfully.
 *
 * terminal nodes will signal success to the coroutine context.
 *
 * repeating nodes may be terminal and will continuously fire success on each repition until the Job is cancelled
 * or the recognizer fails.
 *
 */
open class RecognizerNode(
    val predicate: RecognizerTier,
    /**adds itself to children */
    repeating: Boolean = false,
    /**
     *
     */
    val terminal: Boolean = false,
    /**
     * negate the  success condition
     */
    var negate: Boolean = false,
    vararg children1: RecognizerNode,
    override var green: Boolean = false

) : Recognizer {
    val children = children1.toMutableList()

    /** if this node is a repeating node, it will be added to the children of the parent */
    init {
        if (repeating) children.add(this)
    }

    fun add(vararg recognize: Byte) {
        val r = RecognizerTier.byDistinct(*recognize)
        val c = children.find { it.predicate == r }
        if (c == null) {
            children.add(RecognizerNode(r))
        } else {
            c.add(*recognize)
        }
    }
}