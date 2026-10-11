package borg.trikeshed.loom

import borg.trikeshed.lib.*

/**
 * The ported Rust binaries by name: the exit status of a failure, and the entry, which returns the exit status of a
 * success. An entry prints its output and fails with an [IllegalStateException] carrying the Rust error text.
 * [supervisor] is the platform's seam for `loom-mesh --config` and `loom-registry serve`. An entry takes the arguments'
 * bytes (`args_os`); all but loom-mesh read them as their lossy text.
 */
fun binaries(supervisor: Supervisor): Map<String, Join<Int, (List<ByteArray>) -> Int>> = mapOf(
    "loom-mesh" to (1 j { args: List<ByteArray> -> loomMesh(args, supervisor) }),
    "loom-registry" to (2 j { args: List<ByteArray> -> loomRegistry(Array(args.size) { args[it].decodeToString() }, supervisor); 0 }),
    "loom-kobold-stage" to (1 j { args: List<ByteArray> -> loomKoboldStage(Array(args.size) { args[it].decodeToString() }); 0 }),
    "loom-lease" to (2 j { args: List<ByteArray> -> loomLease(Array(args.size) { args[it].decodeToString() }); 0 }),
)
