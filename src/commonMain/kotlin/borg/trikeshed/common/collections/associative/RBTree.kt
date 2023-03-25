import borg.trikeshed.lib.Join
import borg.trikeshed.lib.assert
import kotlin.math.max

/** https://codereview.stackexchange.com/q/177924
 * The {@code RedBlackBST} class represents an ordered symbol table of generic key-value pairs.
 * This implementation uses a left-leaning red-black BST.
 * It is based on Robert Sedgewick and Kevin Wayne Java based implementation.
 */
class RBTree<Key : Comparable<Key>, Value>(vararg pairs: Join<Key, Value>) {

    private val RED = true

    private val BLACK = false

    private var root: Node<Key, Value>? = null

    init {
        pairs.forEach { (first, second) -> put(first, second) }
    }

    fun get(key: Key): Value? {
        var x = root
        while (x != null) {
            val cmp = key.compareTo(x.key)
            when {
                cmp < 0 -> x = x.left
                cmp > 0 -> x = x.right
                else -> return x.value
            }
        }
        return null
    }

    private fun isRed(node: Node<Key, Value>?): Boolean {
        return node?.color == RED
    }

    private fun rotateLeft(h: Node<Key, Value>): Node<Key, Value> {
        assert(isRed(h.right))
        val x = h.right
        h.right = x?.left
        x?.left = h
        val left = x?.left
        x?.color = left!!.color
        left.color = RED

        setSizeAfterRotate(x, h)
        return x
    }

    private fun setSizeAfterRotate(x: Node<Key, Value>?, h: Node<Key, Value>) {
        x?.size = h.size
        h.size = size(h.left) + size(h.right) + 1
    }

    private fun rotateRight(h: Node<Key, Value>): Node<Key, Value> {
        assert(isRed(h.left))
        val x = h.left
        h.left = x?.right
        x?.right = h
        val right = x?.right
        x?.color = right!!.color
        right.color = RED
        setSizeAfterRotate(x, h)
        return x
    }

    private fun flipColors(h: Node<Key, Value>): Node<Key, Value> {
        assert((!isRed(h) && isRed(h.left) && isRed(h.right)) || (isRed(h) && !isRed(h.left) && !isRed(h.right)))
        h.color = h.color == false
        h.left?.color = h.left?.color == false
        h.right?.color = h.right?.color == false
        return h
    }

    fun put(key: Key, value: Value) {
        root = put(root, key, value)
        root?.color = BLACK
    }

    private fun put(h: Node<Key, Value>?, key: Key, value: Value): Node<Key, Value> {
        if (h == null) {
            return Node(key, value, RED, 1)
        }
        var node = h
        val cmp = key.compareTo(node.key)
        when {
            cmp < 0 -> node.left = put(node.left, key, value)
            cmp > 0 -> node.right = put(node.right, key, value)
            else -> node.value = value
        }
        if (isRed(node.right) && !isRed(node.left)) node = rotateLeft(node)
        if (isRed(node.left) && isRed(node.left?.left)) node = rotateRight(node)
        if (isRed(node.left) && isRed(node.right)) flipColors(node)
        node.size = size(node.left) + size(node.right) + 1
        return node
    }

    fun deleteMin() {
        require(!isEmpty()) { "BST is empty. Cannot delete the minimum" }

        turnRootRed()
        root = deleteMin(root!!)
        turnRootBlack()
    }

    private fun deleteMin(h: Node<Key, Value>): Node<Key, Value>? {
        var node = h
        if (node.left == null) {
            return null
        }
        val left = node.left
        if (!isRed(left) && !isRed(left?.left)) {
            node = moveRedLeft(node)
        }
        node.left = deleteMin(node.left!!)
        return balance(node)
    }

    fun deleteMax() {
        require(!isEmpty()) { "BST is empty. Cannot delete the maximum" }

        turnRootRed()
        root = deleteMax(root!!)
        turnRootBlack()
    }

    private fun deleteMax(h: Node<Key, Value>): Node<Key, Value>? {
        var node = h
        if (isRed(node.left)) {
            node = rotateRight(node)
        }

        if (node.right == null) {
            return null
        }

        if (!isRed(node.right) && !isRed(node.right?.left)) {
            node = moveRedRight(node)
        }

        node.right = deleteMax(node.right!!)
        return balance(node)
    }

    private fun moveRedRight(h: Node<Key, Value>): Node<Key, Value> {
        var node = h
        flipColors(node)
        if (isRed(node.left?.left)) {
            node = rotateRight(node)
            flipColors(node)
        }
        return node
    }

    private fun turnRootBlack() {
        if (!isEmpty()) {
            root?.color = BLACK
        }
    }

    private fun turnRootRed() {
        if (!isRed(root?.left) && !isRed(root?.right)) { // both are black
            root?.color = RED
        }
    }

    private fun balance(h: Node<Key, Value>): Node<Key, Value> {
        var node = h
        if (isRed(node.right)) node = rotateLeft(node)
        if (isRed(node.left) && isRed(node.left?.left)) node = rotateRight(node)
        if (isRed(node.left) && isRed(node.right)) flipColors(h)
        node.size = size(node.left) + size(node.right) + 1
        return node
    }

    // Assuming that h is red and both h.left and h.left.left
    // are black, make h.left or one of its children red.
    private fun moveRedLeft(h: Node<Key, Value>): Node<Key, Value> {

        assert(isRed(h) && !isRed(h.left) && !isRed(h.left?.left))
        var node = h
        flipColors(node)
        if (isRed(node.right?.left)) {
            node.right = rotateRight(node.right!!)
            node = rotateLeft(node)
            flipColors(node)
        }
        return node
    }

    fun delete(key: Key) {
        if (isEmpty()) {
            throw IllegalStateException("The tree is empty")
        }
        if (!contains(key)) {
            return
        }
        turnRootRed()
        root = delete(root!!, key)
        turnRootBlack()
    }

    private fun delete(h: Node<Key, Value>, key: Key): Node<Key, Value>? {
        var node = h
        if (key < node.key) {
            if (!isRed(node.left) && !isRed(node.left?.left)) {
                node = moveRedLeft(node)
            }
            node.left = delete(node.left!!, key)
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node)
            }
            if (key == node.key && (node.right == null)) {
                return null
            }
            if (!isRed(node.right) && !isRed(node.right?.left)) {
                node = moveRedRight(node)
            }
            if (key == node.key) {
                val x = min(node.right!!)
                node.key = x.key
                node.value = x.value
                node.right = deleteMin(node.right!!)
            } else {
                node.right = delete(node.right!!, key)
            }
        }
        return balance(node)
    }

    fun size(): Int {
        return size(root)
    }

    private fun size(node: Node<Key, Value>?): Int {
        if (node == null) return 0
        return node.size
    }

    fun isEmpty(): Boolean {
        return root == null
    }

    fun contains(key: Key): Boolean {
        return get(key) != null
    }

    fun min(): Key {
        require(!isEmpty()) { "called min() with empty tree" }
        return min(root!!).key
    }

    private fun min(x: Node<Key, Value>): Node<Key, Value> = if (x.left == null) x else min(x.left!!)

    fun max(): Key {
        require(!isEmpty()) { "called max() with empty tree" }
        return max(root!!).key
    }

    private fun max(x: Node<Key, Value>): Node<Key, Value> = if (x.right == null) x else max(x.right!!)

    fun height() = height(root)

    private fun height(x: Node<Key, Value>?): Int {
        if (x == null) return -1
        return 1 + max(height(x.left), height(x.right))
    }

    fun depth(k: Key): Int {
        require(!isEmpty()) { "Checking depth on empty tree" }
        if (!contains(k)) {
            return -1
        }
        return depth(root, k)
    }

    private fun depth(x: Node<Key, Value>?, k: Key): Int {
        if (x == null) {
            return 0
        }
        val cmp = k.compareTo(x.key)
        when {
            cmp > 0 -> return 1 + depth(x.right, k)
            cmp < 0 -> return 1 + depth(x.left, k)
            else -> return 1
        }
    }

    fun select(k: Int): Key {
        require(k >= 0) { "Cannot select element below 0" }
        require(k < size()) { "Selected element cannot be more than the size" }
        val x = select(root!!, k)
        return x.key
    }

    private fun select(x: Node<Key, Value>, k: Int): Node<Key, Value> {
        val t = size(x.left)
        return when {
            t > k -> select(x.left!!, k)
            t < k -> select(x.right!!, k - t - 1)
            else -> x
        }
    }

    fun keys(): Iterable<Key> {
        if (isEmpty()) return emptyList()
        return keys(min(), max())
    }

    fun keys(lo: Key, hi: Key): Iterable<Key> {
        val queue: ArrayDeque<Key> = ArrayDeque<Key>()
        keys(root, queue, lo, hi)
        return queue
    }

    private fun keys(x: Node<Key, Value>?, queue: ArrayDeque<Key>, lo: Key, hi: Key) {
        if (x == null) {
            return
        }
        val cmpLo = lo.compareTo(x.key)
        val cmpHi = hi.compareTo(x.key)
        if (cmpLo < 0) {
            keys(x.left, queue, lo, hi)
        }
        if (cmpLo <= 0 && cmpHi >= 0) {
            queue += x.key
        }
        if (cmpHi > 0) {
            keys(x.right, queue, lo, hi)
        }
    }

    fun floor(key: Key): Key? {
        require(!isEmpty()) { "Called floor() on empty table" }
        return floor(root, key)?.key
    }

    private tailrec fun floor(x: Node<Key, Value>?, key: Key): Node<Key, Value>? {
        if (x == null) {
            return null
        }
        val cmp = key.compareTo(x.key)
        when {
            cmp == 0 -> return x
            cmp < 0 -> return floor(x.left, key)
            else -> {
                return floor(x.right, key) ?: x
            }
        }
    }

    fun rank(key: Key): Int {
        return rank(key, root)
    }

    private fun rank(key: Key, x: Node<Key, Value>?): Int {
        if (x == null) {
            return 0
        }
        val cmp = key.compareTo(x.key)
        when {
            cmp < 0 -> return rank(key, x.left)
            cmp > 0 -> return 1 + size(x.left) + rank(key, x.right)
            else -> return size(x.left)
        }
    }

    fun rangeCount(lo: Key, hi: Key): Int {
        if (lo > hi) return 0
        return rank(hi) - rank(lo) + if (contains(hi)) 1 else 0
    }

    fun levelTraverse(): List<List<Key>> {
        require(!isEmpty()) { "Cannot level traverse empty tree" }
        val res = mutableListOf<List<Key>>()
        levelTraverse(root!!, res)
        return res
    }

    private fun levelTraverse(n: Node<Key, Value>, list: MutableList<List<Key>>) {
        val q = mutableListOf<Node<Key, Value>>()
        q += (n)
        var levelList = arrayListOf<Key>()
        list.add(levelList)
        var curHeight = depth(n.key)

        do {
            val node = q.removeFirstOrNull() ?: break
            val height = depth(node.key)
            if (height != curHeight) {
                curHeight = height
                levelList = arrayListOf<Key>()
                list.add(levelList)
            }
            levelList.add(node.key)
            node.left?.let { q += it }
            node.right?.let { q += (it) }

        } while (true)
    }

    internal fun is23(): Boolean {
        return is23(root)
    }

    private fun is23(x: Node<Key, Value>?): Boolean {
        if (x == null) return true
        if (isRed(x.right)) return false
        if (x !== root && isRed(x) && isRed(x.left))
            return false
        return is23(x.left) && is23(x.right)
    }

    internal fun isBalanced(): Boolean {
        require(!isEmpty()) { "Cannot check empty tree for balance" }
        val black =
            generateSequence(root) { node -> node.left }
                .filter { node -> !isRed(node) }.count()
        return isBalanced(root, black)
    }

    private fun isBalanced(x: Node<Key, Value>?, black: Int): Boolean {
        if (x == null) {
            return black == 0
        }
        var blackCopy = black
        if (!isRed(x)) {
            blackCopy -= 1
        }
        return isBalanced(x.left, blackCopy) && isBalanced(x.right, blackCopy)
    }

    data class Node<Key : Comparable<Key>, Value>(
        var key: Key, var value: Value,
        var left: Node<Key, Value>?, var right: Node<Key, Value>?,
        var size: Int,
        var color: Boolean,
    ) {
        constructor(key: Key, value: Value, color: Boolean, size: Int) : this(key, value, null, null, size, color)
    }
}
