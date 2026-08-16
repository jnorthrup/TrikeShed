package borg.trikeshed.tspy

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits

/**
 * Configuration for the GraalPy Context in TrikeShed's tspy module.
 */
object ContextConfig {
    /**
     * Path to the Python module sources, specifically pointing to the tspy bootstrap module.
     */
    const val PYTHON_PATH = "utils/tspy/src/python"

    /**
     * Statement limit for sandboxed eval.
     */
    const val STATEMENT_LIMIT = 10000L
}

class TspyPolyglotHost : AutoCloseable {

    private val context: Context = Context.newBuilder("python")
        .allowHostAccess(HostAccess.NONE)
        .option("python.Path", ContextConfig.PYTHON_PATH)
        .resourceLimits(
            ResourceLimits.newBuilder()
                .statementLimit(ContextConfig.STATEMENT_LIMIT, null)
                .build()
        )
        .build()

    fun eval(sourceCode: String): org.graalvm.polyglot.Value {
        return context.eval("python", sourceCode)
    }

    override fun close() {
        context.close()
    }
}
