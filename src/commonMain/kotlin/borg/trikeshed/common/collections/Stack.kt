package borg.trikeshed.common.collections


class Stack<T>(src: List<T> = listOf(), private val list: MutableList<T> = ListCowView(src)) {

    fun push(t: T): Stack<T> = apply { list.add(t) }
    fun pop(): T = list.removeAt(list.size - 1)
    fun peek(): T = list[list.size - 1]
    fun size(): Int = list.size

    override fun toString(): String {
        return "Stack(list=$list)"
    }

    fun clone(): Stack<T> {
        return Stack(list)
    }

    fun isEmpty(): Boolean = list.isEmpty()
}
