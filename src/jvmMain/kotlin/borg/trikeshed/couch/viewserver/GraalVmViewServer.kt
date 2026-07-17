package borg.trikeshed.couch.viewserver

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits

class GraalVmViewServer : AutoCloseable {

    private val context: Context = Context.newBuilder("js")
        .allowHostAccess(HostAccess.NONE)
        .resourceLimits(ResourceLimits.newBuilder().statementLimit(10000, null).build())
        .build()

    fun evalJs(expr: String): String = context.eval("js", expr).toString()

    override fun close() {
        context.close()
    }
}
