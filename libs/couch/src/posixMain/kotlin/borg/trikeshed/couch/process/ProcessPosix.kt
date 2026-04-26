package borg.trikeshed.couch.process

actual class ProcessShell {
    actual fun exec(command: String, args: List<String>): ProcessResult {
        throw UnsupportedOperationException("Process execution not supported on this platform")
    }

    actual fun exec(command: String, vararg args: String): ProcessResult {
        throw UnsupportedOperationException("Process execution not supported on this platform")
    }
}
