package borg.trikeshed.mlir

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import mlnixc.mlnixClose
import mlnixc.mlnixError
import mlnixc.mlnixFree
import mlnixc.mlnixOpen
import mlnixc.mlnixRoundTrip
import platform.posix.exit

/** A module on stdin, printed as `mlnix-opt` prints it; a module that does not parse or verify prints its diagnostics to stderr and exits 1. */
@OptIn(ExperimentalForeignApi::class)
fun mlirMain() {
    val text = StringBuilder()
    while (true) text.append(readlnOrNull() ?: break).append('\n')
    val session = mlnixOpen()
    val printed = mlnixRoundTrip(session, text.toString(), text.length.toULong())
    if (printed == null) {
        println(mlnixError(session)!!.toKString())
        mlnixClose(session)
        exit(1)
        return
    }
    print(printed.toKString())
    mlnixFree(printed)
    mlnixClose(session)
}
