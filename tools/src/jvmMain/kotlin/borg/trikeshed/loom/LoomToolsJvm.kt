package borg.trikeshed.loom

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/** JVM entry for the loom tools: the first argument names the Rust binary, the rest are its arguments. */
fun main(args: Array<String>) {
    // Rust writes UTF-8 whatever the locale; System.out would encode with stdout.encoding.
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    val name = args.firstOrNull()
    val binary = name?.let { binaries[it] } ?: run {
        System.err.println("usage: <${binaries.keys.joinToString("|")}> ARGS...")
        exitProcess(2)
    }
    try {
        binary.b(args.copyOfRange(1, args.size))
    } catch (failure: IllegalStateException) {
        System.out.flush()
        System.err.write("$name: ${failure.message}\n".encodeToByteArray())
        System.err.flush()
        exitProcess(binary.a)
    }
    System.out.flush()
    exitProcess(0)
}
