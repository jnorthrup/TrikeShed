package borg.trikeshed.couch.viewserver

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits

/**
 * GraalVM Polyglot JS View Server — sandboxed context for CouchDB map/reduce.
 *
 * Root-only stub: the original libs/couch ViewStore.define() bridge needs porting.
 * This provides the GraalVM Context creation and sandboxing (HostAccess.NONE,
 * statement limit) which proves the polyglot dependency works.
 */
class GraalVmViewServer : AutoCloseable {

    private val context: Context = Context.newBuilder("js")
        .allowHostAccess(HostAccess.NONE)
        .resourceLimits(ResourceLimits.newBuilder().statementLimit(10000, null).build())
        .build()

    /**
     * Evaluate a JavaScript expression in the sandboxed GraalVM context.
     * Proves the polyglot engine is operational.
     */
    fun evalJs(expr: String): String = context.eval("js", expr).toString()

    override fun close() {
        context.close()
    }
}