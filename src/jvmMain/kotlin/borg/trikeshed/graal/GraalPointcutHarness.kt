package borg.trikeshed.graal

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value

/**
 * GraalPointcutHarness - extends pointcut capturing to any Graal polyglot language.
 *
 * It uses Context.newBuilder() to manage the evaluation environment for multiple languages.
 */
class GraalPointcutHarness {
    val context: Context = Context.newBuilder()
        .allowHostAccess(HostAccess.ALL)
        .build()

    fun eval(languageId: String, source: String): Any? {
        return context.eval(languageId, source)?.asHostObject()
    }

    fun close() {
        context.close()
    }
}
