package borg.trikeshed.polyglot.graal

import org.graalvm.polyglot.Context
import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer

/**
 * Legacy bridge for evalWithPointcuts - kept for backward compat.
 * New code should use GraalPointcutHarness + context.bindPointcutEmitter() directly.
 */
fun Context.evalWithPointcuts(language: String, source: String, producer: PointcutEventProducer): Any? {
    val harness = GraalPointcutHarness(producer)
    val emitter = PolyglotPointcutEmitter(producer, harness)
    this.getBindings(language).putMember("pointcutEmitter", emitter)
    return this.eval(language, source)?.asHostObject()
}