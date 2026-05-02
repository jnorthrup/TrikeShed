package borg.trikeshed.cpucache

/**
 * Entry point — interrogates CPU cache and prints Confix JSON to stdout.
 *
 * Platform-specific [interrogateCpuCache] is resolved at link time.
 * Output is parseable by TrikeShed's Confix parser (JSON-compatible).
 */
fun main() {
    val topology = interrogateCpuCache()
    println(topology.toConfix())
}
