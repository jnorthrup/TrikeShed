package borg.trikeshed.lib.collections

internal enum class Color {
    RED, BLACK
}

internal class Node<K, V> {
    var key: K? = null
    var value: V? = null
    var left: Node<K, V>? = null
    var right: Node<K, V>? = null
    var parent: Node<K, V>? = null
    var color: Color = Color.BLACK
}
