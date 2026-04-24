package borg.trikeshed.couch.process

actual class ProcessShell {
    actual fun exec(command: String, args: List<String>): ProcessResult {
        // Not supported on JS platform — provide a clear runtime failure if called
        throw UnsupportedOperationException("ProcessShell.exec is not supported on JS")
    }

    actual fun exec(command: String, vararg args: String): ProcessResult = exec(command, args.toList())
}
