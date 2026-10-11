package borg.trikeshed.loom

import borg.trikeshed.lib.*

/**
 * The ported Rust binaries by name: the exit status of a failure, and the entry, which returns the exit status of a
 * success. An entry prints its output and fails with an [IllegalStateException] carrying the Rust error text.
 * [supervisor] is the platform's seam for `loom-mesh --config` and `loom-registry serve`.
 */
fun binaries(supervisor: Supervisor): Map<String, Join<Int, (Array<String>) -> Int>> = mapOf(
    "loom-mesh" to (1 j { args: Array<String> -> loomMesh(args, supervisor) }),
    "loom-registry" to (2 j { args: Array<String> -> loomRegistry(args, supervisor); 0 }),
    "loom-kobold-stage" to (1 j { args: Array<String> -> loomKoboldStage(args); 0 }),
    "loom-lease" to (2 j { args: Array<String> -> loomLease(args); 0 }),
)
