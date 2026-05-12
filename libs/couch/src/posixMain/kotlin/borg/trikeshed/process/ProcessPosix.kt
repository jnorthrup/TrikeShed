package borg.trikeshed.process

actual class ProcessShell {
    actual fun exec(command: CharSequence, args: List<CharSequence>): ProcessResult {
        throw UnsupportedOperationException("Process execution not supported on this platform")
    }

    actual fun exec(command: CharSequence, vararg args: CharSequence): ProcessResult {
        throw UnsupportedOperationException("Process execution not supported on this platform")
    }
}
