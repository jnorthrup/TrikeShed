package borg.trikeshed.common.collections.associative

import kotlin.math.max

class TreeSet<E : Comparable<E>> : NavigableSet<E> {
    private var root: Node<E>? = null
    override var size: Int = 0

    private class Node<E>(var element: E) {
        var left: Node<E>? = null
        var right: Node<E>? = null
        var height: Int = 1
    }

    override fun add(element: E): Boolean {
        val oldSize = size
        root = insert(root, element)
        return size != oldSize
    }

    private fun insert(node: Node<E>?, element: E): Node<E> {
        if (node == null) {
            size++
            return Node(element)
        }

        when {
            element < node.element -> node.left = insert(node.left, element)
            element > node.element -> node.right = insert(node.right, element)
            else -> return node // Element already exists
        }

        return balance(node)
    }

    private fun balance(node: Node<E>): Node<E> {
        updateHeight(node)
        val balance = getBalance(node)

        return when {
            balance > 1 && getBalance(node.left) >= 0 -> rightRotate(node)
            balance > 1 && getBalance(node.left) < 0 -> {
                node.left = leftRotate(node.left!!)
                rightRotate(node)
            }
            balance < -1 && getBalance(node.right) <= 0 -> leftRotate(node)
            balance < -1 && getBalance(node.right) > 0 -> {
                node.right = rightRotate(node.right!!)
                leftRotate(node)
            }
            else -> node
        }
    }

    private fun rightRotate(y: Node<E>): Node<E> {
        val x = y.left!!
        val T2 = x.right

        x.right = y
        y.left = T2

        updateHeight(y)
        updateHeight(x)

        return x
    }

    private fun leftRotate(x: Node<E>): Node<E> {
        val y = x.right!!
        val T2 = y.left

        y.left = x
        x.right = T2

        updateHeight(x)
        updateHeight(y)

        return y
    }

    private fun updateHeight(node: Node<E>) {
        node.height = max(height(node.left), height(node.right)) + 1
    }

    private fun height(node: Node<E>?): Int = node?.height ?: 0

    private fun getBalance(node: Node<E>?): Int = if (node == null) 0 else height(node.left) - height(node.right)

    override fun remove(element: E): Boolean {
        val oldSize = size
        root = delete(root, element)
        return size != oldSize
    }

    private fun delete(node: Node<E>?, element: E): Node<E>? {
        if (node == null) return null

        when {
            element < node.element -> node.left = delete(node.left, element)
            element > node.element -> node.right = delete(node.right, element)
            else -> {
                if (node.left == null || node.right == null) {
                    val temp = node.left ?: node.right
                    if (temp == null) {
                        return null
                    } else {
                        size--
                        return temp
                    }
                } else {
                    val temp = minValueNode(node.right!!)
                    node.element = temp.element
                    node.right = delete(node.right, temp.element)
                }
            }
        }

        return balance(node)
    }

    private fun minValueNode(node: Node<E>): Node<E> {
        var current = node
        while (current.left != null) {
            current = current.left!!
        }
        return current
    }

    override fun clear() {
        root = null
        size = 0
    }

    override fun comparator(): Comparator<E> = Comparator { a, b -> a.compareTo(b) }

    override fun subSet(fromElement: E, toElement: E): SortedSet<E> {
        return subSet(fromElement, true, toElement, false)
    }

    override fun headSet(toElement: E): SortedSet<E> {
        return headSet(toElement, false)
    }

    override fun tailSet(fromElement: E): SortedSet<E> {
        return tailSet(fromElement, true)
    }

    override fun first(): E {
        if (isEmpty()) throw NoSuchElementException()
        return minValueNode(root!!).element
    }

    override fun last(): E {
        if (isEmpty()) throw NoSuchElementException()
        return maxValueNode(root!!).element
    }

    private fun maxValueNode(node: Node<E>): Node<E> {
        var current = node
        while (current.right != null) {
            current = current.right!!
        }
        return current
    }

    override fun lower(e: E): E? {
        var node = root
        var result: E? = null
        while (node != null) {
            if (e <= node.element) {
                node = node.left
            } else {
                result = node.element
                node = node.right
            }
        }
        return result
    }

    override fun floor(e: E): E? {
        var node = root
        var result: E? = null
        while (node != null) {
            when {
                e < node.element -> node = node.left
                e > node.element -> {
                    result = node.element
                    node = node.right
                }
                else -> return node.element
            }
        }
        return result
    }

    override fun ceiling(e: E): E? {
        var node = root
        var result: E? = null
        while (node != null) {
            when {
                e > node.element -> node = node.right
                e < node.element -> {
                    result = node.element
                    node = node.left
                }
                else -> return node.element
            }
        }
        return result
    }

    override fun higher(e: E): E? {
        var node = root
        var result: E? = null
        while (node != null) {
            if (e >= node.element) {
                node = node.right
            } else {
                result = node.element
                node = node.left
            }
        }
        return result
    }

    override fun pollFirst(): E? {
        if (isEmpty()) return null
        val first = first()
        remove(first)
        return first
    }

    override fun pollLast(): E? {
        if (isEmpty()) return null
        val last = last()
        remove(last)
        return last
    }

    override fun isEmpty(): Boolean = size == 0

    override fun contains(element: E): Boolean {
        var current = root
        while (current != null) {
            when {
                element < current.element -> current = current.left
                element > current.element -> current = current.right
                else -> return true
            }
        }
        return false
    }

    override fun descendingSet(): NavigableSet<E> {
        // Implementing a full descending set is beyond the scope of this example
        throw UnsupportedOperationException("descendingSet not implemented")
    }

    override fun descendingIterator(): Iterator<E> {
        // Implementing a full descending iterator is beyond the scope of this example
        throw UnsupportedOperationException("descendingIterator not implemented")
    }

    override fun subSet(
        fromElement: E,
        fromInclusive: Boolean,
        toElement: E,
        toInclusive: Boolean
    ): NavigableSet<E> {
        // Implementing a full subset is beyond the scope of this example
        throw UnsupportedOperationException("subSet not implemented")
    }

    override fun headSet(toElement: E, inclusive: Boolean): NavigableSet<E> {
        // Implementing a full headSet is beyond the scope of this example
        throw UnsupportedOperationException("headSet not implemented")
    }

    override fun tailSet(fromElement: E, inclusive: Boolean): NavigableSet<E> {
        // Implementing a full tailSet is beyond the scope of this example
        throw UnsupportedOperationException("tailSet not implemented")
    }

    override fun iterator(): MutableIterator<E> = object : MutableIterator<E> {
        private val stack = mutableListOf<Node<E>>()
        private var current = root

        init {
            pushLeft(current)
        }

        private fun pushLeft(node: Node<E>?) {
            var current = node
            while (current != null) {
                stack.add(current)
                current = current.left
            }
        }

        override fun hasNext(): Boolean = stack.isNotEmpty()

        override fun next(): E {
            if (!hasNext()) throw NoSuchElementException()
            val node = stack.removeAt(stack.size - 1)
            pushLeft(node.right)
            return node.element
        }

        override fun remove() {
            throw UnsupportedOperationException("remove not implemented")
        }
    }


    override fun containsAll(elements: Collection<E>): Boolean = elements.all { contains(it) }

    override fun addAll(elements: Collection<E>): Boolean {
        var changed = false
        for (element in elements) {
            changed = add(element) || changed
        }
        return changed
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        val elementsSet = elements.toSet()
        val iterator = iterator()
        var changed = false
        while (iterator.hasNext()) {
            if (iterator.next() !in elementsSet) {
                iterator.remove()
                changed = true
            }
        }
        return changed
    }

    override fun removeAll(elements: Collection<E>): Boolean {
        var changed = false
        for (element in elements) {
            changed = remove(element) || changed
        }
        return changed
    }
}

