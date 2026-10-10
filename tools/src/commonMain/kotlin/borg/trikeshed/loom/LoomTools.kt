package borg.trikeshed.loom

import borg.trikeshed.lib.*

/**
 * The ported Rust binaries by name: the exit status of a failure, and the entry. An entry prints
 * its output and fails with an [IllegalStateException] carrying the Rust error text.
 */
val binaries: Map<String, Join<Int, (Array<String>) -> Unit>> = mapOf(
    "loom-mesh" to (1 j ::loomMesh),
    "loom-registry" to (2 j ::loomRegistry),
)
