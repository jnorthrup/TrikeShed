package borg.trikeshed.tilting.leaf

import java.io.File

/**
 * `java -cp ... borg.trikeshed.tilting.leaf.LeafPermutationMainKt <corpus>`: the permutation's JVM cells; the same
 * entry point compiled by GraalVM native-image gives the graal-native cells.
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: error("usage: leafPermutation <file of concatenated 4096-byte pages>")
    leafPermutation(File(path).readBytes()) { println(it) }
}
