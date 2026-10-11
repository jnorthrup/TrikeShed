package borg.trikeshed.loom

import platform.posix.*
import kotlin.system.exitProcess

fun loomMeshMain(args: Array<String>) = status("loom-mesh", args)

fun loomRegistryMain(args: Array<String>) = status("loom-registry", args)

fun loomKoboldStageMain(args: Array<String>) = status("loom-kobold-stage", args)

fun loomLeaseMain(args: Array<String>) = status("loom-lease", args)

/** Runs the binary [name]: an [IllegalStateException] goes to stderr behind `<name>: ` and exits with its status. */
fun status(name: String, args: Array<String>) {
    val binary = binaries.getValue(name)
    try {
        binary.b(args)
    } catch (failure: IllegalStateException) {
        fflush(stdout)
        fputs("$name: ${failure.message}\n", stderr)
        fflush(stderr)
        exitProcess(binary.a)
    }
    exitProcess(0)
}
