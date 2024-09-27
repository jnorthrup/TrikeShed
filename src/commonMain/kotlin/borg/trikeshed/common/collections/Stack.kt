package borg.trikeshed.common.collections


class Stack<T>(src: List<T> = listOf()) {
    private val list: MutableList<T> = src.toMutableList()

    fun push(t: T): Stack<T> = apply { list.add(t) }
    fun pop(): T = list.removeAt(list.size - 1)
    fun peek(): T = list[list.size - 1]
    fun size(): Int = list.size
    fun isEmpty(): Boolean = list.isEmpty()
    fun clone(): Stack<T> = Stack(list)

    override fun toString(): String = "Stack(list=$list)"
}