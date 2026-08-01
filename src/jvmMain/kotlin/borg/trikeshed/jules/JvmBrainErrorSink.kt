package borg.trikeshed.jules

import java.io.File

/**
 * JVM-only filesystem adapter for the optional brain-error audit trail.
 * HTTP and TLS remain common reactor HTX behavior.
 */
class JvmBrainErrorSink(forgeDir: File) : BrainErrorSink {
    private val errorLog = File(forgeDir, "brain-errors.jsonl").also { it.parentFile?.mkdirs() }

    override fun append(entry: String) {
        errorLog.appendText(entry + "\n")
    }
}
