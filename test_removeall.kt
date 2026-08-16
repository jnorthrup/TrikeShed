fun main() {
    val map = mutableMapOf("a" to 1, "ab" to 2, "c" to 3)
    map.entries.removeAll { it.key.startsWith("a") }
    println(map)
}
