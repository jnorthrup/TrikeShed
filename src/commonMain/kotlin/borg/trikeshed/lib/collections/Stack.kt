package borg.trikeshed.lib.collections


class Stack<T>(src: List<T> = listOf(), private val list: MutableList<T> = ListCowView(src)) {

    fun push(t: T) = apply { list.add(t) }
    fun pop(): T = list.removeAt(list.size - 1)
    fun peek(): T = list[list.size - 1]


    override fun toString(): String {
        return "Stack(list=$list)"
    }

    fun clone(): Stack<T> {
        return Stack(list)
    }
}
