package borg.trikeshed.tilting.leaf

import kotlin.test.Test
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

/** Read the configured corpus through the common userspace file channel. */
fun leafCorpus(): ByteArray? {
    val system = SystemOperations.default
    val path = (system.getProperty("leaf.corpus") ?: system.getenv("LEAF_CORPUS"))?.takeIf { it.isNotBlank() } ?: return null
    return FileChannel.open(path, StandardOpenOption.READ).use { channel ->
        val size = channel.size()
        require(size in 0..Int.MAX_VALUE.toLong()) { "Leaf corpus is too large" }
        val bytes = ByteArray(size.toInt())
        val buffer = ByteBuffer(bytes)
        while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "Leaf corpus ended early" }
        bytes
    }
}

/** Runs [leafPermutation] over the configured corpus. */
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
