package borg.trikeshed.graal

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import org.xvm.activejs.ccek.PointcutEventProducer

/**
 * GraalPointcutHarness - extends pointcut capturing to any Graal polyglot language.
 *
 * It uses Context.newBuilder() to manage the evaluation environment for multiple languages.
 */
class GraalPointcutHarness(private val pointcutProducer: PointcutEventProducer? = null) {

    val context: Context = Context.newBuilder()
        .build()

    fun eval(languageId: String, source: String): Any? {
        return context.eval(languageId, source)?.asHostObject()
    }

    fun close() {
        context.close()
    }
}
