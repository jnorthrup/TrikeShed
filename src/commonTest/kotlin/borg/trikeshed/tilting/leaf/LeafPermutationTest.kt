package borg.trikeshed.tilting.leaf

import kotlin.test.Test

/** The leaf corpus: concatenated 4096-byte SQLite pages, or null when none is configured. */
expect fun leafCorpus(): ByteArray?

/** Runs [leafPermutation] over the configured corpus (see each platform's [leafCorpus]). */
class LeafPermutationTest {
    @Test
    fun permutation() {
        val corpus = leafCorpus()
        if (corpus == null) {
            println("PERMUTATION ${LeafSpecialization.platform}: no corpus configured")
            return
        }
        leafPermutation(corpus) { println(it) }
    }
}
