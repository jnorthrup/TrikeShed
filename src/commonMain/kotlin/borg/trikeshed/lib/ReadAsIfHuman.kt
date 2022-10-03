package borg.trikeshed.lib

/***
 * translate human readable numbers to long values, converting to lowercase first
 */
fun String.readAsIfHuman(): Long {
    val s = this.lowercase()
    val last = s.last()
    val n = s.dropLast(1).toLongOrNull() ?: return 0
    return when (last) {
        'k' -> n * 1000
        'm' -> n * 1000 * 1000
        'g' -> n * 1000 * 1000 * 1000
        else -> n
    }
}