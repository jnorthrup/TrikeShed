package borg.trikeshed.pointcut

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value

class TspyPolyglotHost : AutoCloseable {

    val context: Context = Context.newBuilder("python")
        .option("python.Path", "utils/tspy/src/python")
        .build()

    fun bootstrap(): Value {
        return context.eval("python", "import tspy\ntspy")
    }

    override fun close() {
        context.close()
    }
}
