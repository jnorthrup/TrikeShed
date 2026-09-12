package borg.trikeshed.jules

import borg.trikeshed.common.File


/**
 * JVM-only filesystem adapter for the optional brain-error audit trail.
 * HTTP and TLS remain common reactor HTX behavior.
 */
class JvmBrainErrorSink(forgeDir: File) : BrainErrorSink {
    private val errorLog = forgeDir.resolve("brain-errors.jsonl").also { it.parentFile?.mkdirs() }

    override fun append(entry: String) {
        errorLog.appendText(entry + "\n")
    }
}
