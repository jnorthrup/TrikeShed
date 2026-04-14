package trie

class Node(val pathSeg: String, var leaf: Boolean, val payload: Int, var children: Map<String, Node> = linkedMapOf())

